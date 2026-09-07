package pro.liliya.licensing.testkit

import java.security.MessageDigest

/**
 * TEST-ONLY deterministic envelope fixture helper.
 *
 * This is not a production signer and deliberately owns no private key. S5.2 introduces the real
 * signing port and explicit key-reference semantics.
 */
data class TestSignedEnvelope(
    val keyId: String,
    val canonicalPayload: ByteArray,
    val testSignature: ByteArray
)

object TestSigningAdapter {
    fun sign(keyId: String, canonicalPayload: ByteArray): TestSignedEnvelope {
        require(keyId.startsWith("test-")) { "test key id required" }
        val signature = MessageDigest.getInstance("SHA-256").digest(
            keyId.encodeToByteArray() + canonicalPayload
        )
        return TestSignedEnvelope(
            keyId = keyId,
            canonicalPayload = canonicalPayload.copyOf(),
            testSignature = signature
        )
    }
}
