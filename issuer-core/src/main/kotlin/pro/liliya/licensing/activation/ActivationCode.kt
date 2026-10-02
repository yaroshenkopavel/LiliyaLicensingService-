package pro.liliya.licensing.activation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class ActivationCodeClaims(
    val version: Int,
    val codeId: String,
    val productId: String,
    val features: Set<String>,
    val expiresAt: Instant?,
    val maxRedemptions: Int
) {
    init {
        require(version > 0)
        require(codeId.isNotBlank())
        require(productId.isNotBlank())
        require(features.isNotEmpty() && features.none { it.isBlank() })
        require(maxRedemptions > 0)
    }
}
data class ActivationCodeEnvelope(
    val keyId: String,
    val claims: ActivationCodeClaims,
    val signature: ByteArray
) {
    init {
        require(keyId.isNotBlank())
        require(signature.isNotEmpty())
    }

    override fun toString(): String =
        "ActivationCodeEnvelope(keyId=$keyId,claims=<redacted>,signature=<redacted>)"
}

sealed interface ActivationCodeParseResult {
    data class Parsed(val envelope: ActivationCodeEnvelope) : ActivationCodeParseResult
    data object Rejected : ActivationCodeParseResult
}

sealed interface ActivationCodeVerificationResult {
    data class Verified(val claims: ActivationCodeClaims) : ActivationCodeVerificationResult
    data object InvalidSignature : ActivationCodeVerificationResult
    data object UnknownKey : ActivationCodeVerificationResult
    data object Expired : ActivationCodeVerificationResult
    data object Malformed : ActivationCodeVerificationResult
}

fun interface ActivationCodePublicKeyResolver {
    fun resolve(keyId: String): PublicKey?
}
object ActivationCodeCodec {
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    fun encode(envelope: ActivationCodeEnvelope): String {
        val payload = encodeClaims(envelope.claims)
        return listOf(
            PREFIX,
            url.encodeToString(envelope.keyId.toByteArray(Charsets.UTF_8)),
            url.encodeToString(payload),
            url.encodeToString(envelope.signature)
        ).joinToString(".")
    }

    fun parse(encoded: String): ActivationCodeParseResult = try {
        val parts = encoded.trim().split('.')
        if (parts.size != 4 || parts[0] != PREFIX) return ActivationCodeParseResult.Rejected
        val keyId = urlDecoder.decode(parts[1]).toString(Charsets.UTF_8)
        val payload = urlDecoder.decode(parts[2])
        val signature = urlDecoder.decode(parts[3])
        val claims = decodeClaims(payload) ?: return ActivationCodeParseResult.Rejected
        ActivationCodeParseResult.Parsed(ActivationCodeEnvelope(keyId, claims, signature))
    } catch (_: Throwable) {
        ActivationCodeParseResult.Rejected
    }

    fun signingPayload(claims: ActivationCodeClaims): ByteArray = encodeClaims(claims)
    private fun encodeClaims(claims: ActivationCodeClaims): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(claims.version)
                out.writeUTF(claims.codeId)
                out.writeUTF(claims.productId)
                val ordered = claims.features.toSortedSet()
                out.writeInt(ordered.size)
                ordered.forEach(out::writeUTF)
                out.writeBoolean(claims.expiresAt != null)
                claims.expiresAt?.let { out.writeLong(it.toEpochMilli()) }
                out.writeInt(claims.maxRedemptions)
            }
            bytes.toByteArray()
        }

    private fun decodeClaims(payload: ByteArray): ActivationCodeClaims? =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != MAGIC) return null
            val version = input.readInt()
            val codeId = input.readUTF()
            val productId = input.readUTF()
            val featureCount = input.readInt()
            if (featureCount !in 1..MAX_FEATURES) return null
            val features = buildSet {
                repeat(featureCount) { add(input.readUTF()) }
            }
            val expiresAt = if (input.readBoolean()) {
                Instant.ofEpochMilli(input.readLong())
            } else {
                null
            }
            val maxRedemptions = input.readInt()
            if (input.available() != 0) return null
            runCatching {
                ActivationCodeClaims(
                    version = version,
                    codeId = codeId,
                    productId = productId,
                    features = features,
                    expiresAt = expiresAt,
                    maxRedemptions = maxRedemptions
                )
            }.getOrNull()
        }
    private const val PREFIX = "LAC1"
    private const val MAGIC = 0x4c414331
    private const val MAX_FEATURES = 64
}

object ActivationCodeVerifier {
    fun verify(
        encoded: String,
        keys: ActivationCodePublicKeyResolver,
        now: Instant
    ): ActivationCodeVerificationResult {
        val parsed = ActivationCodeCodec.parse(encoded)
        val envelope = when (parsed) {
            is ActivationCodeParseResult.Parsed -> parsed.envelope
            ActivationCodeParseResult.Rejected -> return ActivationCodeVerificationResult.Malformed
        }
        val key = keys.resolve(envelope.keyId)
            ?: return ActivationCodeVerificationResult.UnknownKey
        val payload = ActivationCodeCodec.signingPayload(envelope.claims)
        val valid = try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(payload)
                verify(envelope.signature)
            }
        } catch (_: Throwable) {
            false
        }
        if (!valid) return ActivationCodeVerificationResult.InvalidSignature
        if (envelope.claims.expiresAt?.isBefore(now) == true) {
            return ActivationCodeVerificationResult.Expired
        }
        return ActivationCodeVerificationResult.Verified(envelope.claims)
    }

    fun ecdsaP256PublicKey(x509Der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509Der))

    private const val ALGORITHM = "SHA256withECDSA"
}
