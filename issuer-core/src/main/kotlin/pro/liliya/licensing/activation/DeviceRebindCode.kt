package pro.liliya.licensing.activation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64

data class DeviceRebindCodeClaims(
    val version: Int,
    val codeId: String,
    val subject: String,
    val productId: String,
    val expiresAt: Instant?
) {
    init {
        require(version > 0)
        require(codeId.isNotBlank())
        require(subject.isNotBlank())
        require(productId.isNotBlank())
    }
}

data class DeviceRebindCodeEnvelope(
    val keyId: String,
    val claims: DeviceRebindCodeClaims,
    val signature: ByteArray
) {
    init {
        require(keyId.isNotBlank())
        require(signature.isNotEmpty())
    }
}

sealed interface DeviceRebindCodeVerificationResult {
    data class Verified(val claims: DeviceRebindCodeClaims) :
        DeviceRebindCodeVerificationResult
    data object Invalid : DeviceRebindCodeVerificationResult
    data object Expired : DeviceRebindCodeVerificationResult
}

object DeviceRebindCodeCodec {
    private const val PREFIX = "LDR1"
    private const val MAGIC = 0x4c445231
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun signingPayload(claims: DeviceRebindCodeClaims): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(claims.version)
                out.writeUTF(claims.codeId)
                out.writeUTF(claims.subject)
                out.writeUTF(claims.productId)
                out.writeBoolean(claims.expiresAt != null)
                claims.expiresAt?.let { out.writeLong(it.toEpochMilli()) }
            }
            bytes.toByteArray()
        }

    fun encode(envelope: DeviceRebindCodeEnvelope): String =
        listOf(
            PREFIX,
            url.encodeToString(envelope.keyId.toByteArray(Charsets.UTF_8)),
            url.encodeToString(signingPayload(envelope.claims)),
            url.encodeToString(envelope.signature)
        ).joinToString(".")

    fun parse(encoded: String): DeviceRebindCodeEnvelope? = try {
        val parts = encoded.trim().split('.')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val keyId = decoder.decode(parts[1]).toString(Charsets.UTF_8)
        val payload = decoder.decode(parts[2])
        val signature = decoder.decode(parts[3])
        val claims = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != MAGIC) return null
            val parsed = DeviceRebindCodeClaims(
                version = input.readInt(),
                codeId = input.readUTF(),
                subject = input.readUTF(),
                productId = input.readUTF(),
                expiresAt = if (input.readBoolean()) {
                    Instant.ofEpochMilli(input.readLong())
                } else null
            )
            if (input.available() != 0) return null
            parsed
        }
        DeviceRebindCodeEnvelope(keyId, claims, signature)
    } catch (_: Throwable) {
        null
    }
}

object DeviceRebindCodeVerifier {
    fun verify(
        encoded: String,
        keys: ActivationCodePublicKeyResolver,
        now: Instant
    ): DeviceRebindCodeVerificationResult {
        val envelope = DeviceRebindCodeCodec.parse(encoded)
            ?: return DeviceRebindCodeVerificationResult.Invalid
        val key: PublicKey = keys.resolve(envelope.keyId)
            ?: return DeviceRebindCodeVerificationResult.Invalid
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(DeviceRebindCodeCodec.signingPayload(envelope.claims))
                verify(envelope.signature)
            }
        }.getOrDefault(false)
        if (!valid) return DeviceRebindCodeVerificationResult.Invalid
        val expiresAt = envelope.claims.expiresAt
            ?: return DeviceRebindCodeVerificationResult.Invalid
        if (expiresAt.isBefore(now)) {
            return DeviceRebindCodeVerificationResult.Expired
        }
        return DeviceRebindCodeVerificationResult.Verified(envelope.claims)
    }
}

class DeviceRebindCodeGenerator(
    private val keyId: String,
    private val signer: ActivationCodeSigner,
    private val random: java.security.SecureRandom = java.security.SecureRandom()
) {
    init { require(keyId.isNotBlank()) }

    fun generate(
        subject: String,
        productId: String,
        expiresAt: Instant
    ): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)
        val claims = DeviceRebindCodeClaims(
            version = 1,
            codeId = "device-rebind-v1:$token",
            subject = subject,
            productId = productId,
            expiresAt = expiresAt
        )
        val signature = signer.sign(DeviceRebindCodeCodec.signingPayload(claims))
        require(signature.isNotEmpty())
        return DeviceRebindCodeCodec.encode(
            DeviceRebindCodeEnvelope(keyId, claims, signature)
        )
    }
}
