package pro.liliya.licensing.activation

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

fun interface ActivationCodeSigner {
    fun sign(payload: ByteArray): ByteArray
}

class ActivationCodeGenerator(
    private val keyId: String,
    private val signer: ActivationCodeSigner,
    private val random: SecureRandom = SecureRandom()
) {
    init {
        require(keyId.isNotBlank())
    }

    fun generate(
        productId: String,
        features: Set<String>,
        expiresAt: Instant?,
        maxRedemptions: Int = 1
    ): String {
        val claims = ActivationCodeClaims(
            version = 1,
            codeId = newCodeId(),
            productId = productId,
            features = features,
            expiresAt = expiresAt,
            maxRedemptions = maxRedemptions
        )
        val payload = ActivationCodeCodec.signingPayload(claims)
        val signature = signer.sign(payload)
        require(signature.isNotEmpty()) { "activation code signer returned empty signature" }
        return ActivationCodeCodec.encode(
            ActivationCodeEnvelope(
                keyId = keyId,
                claims = claims,
                signature = signature
            )
        )
    }

    private fun newCodeId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)
        return "activation-v1:$token"
    }
}
