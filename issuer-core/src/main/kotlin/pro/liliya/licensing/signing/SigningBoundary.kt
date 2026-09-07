package pro.liliya.licensing.signing

@JvmInline
value class SigningKeyReference(val value: String) {
    init { require(value.isNotBlank()) { "signing key reference must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class SigningEnvelopeSchemaVersion(val value: Long) {
    init { require(value > 0L) { "signing envelope schema version must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class SigningAlgorithm(val value: String) {
    init { require(value.isNotBlank()) { "signing algorithm must not be blank" } }
    override fun toString(): String = value
}

enum class SigningFailure {
    KEY_UNAVAILABLE,
    KEY_RETIRED,
    SIGNING_REJECTED,
    INTERNAL_FAILURE
}

class SignedLicenseEnvelope(
    val schemaVersion: SigningEnvelopeSchemaVersion,
    val algorithm: SigningAlgorithm,
    val keyReference: SigningKeyReference,
    canonicalPayload: ByteArray,
    signature: ByteArray
) {
    private val payloadBytes = canonicalPayload.copyOf()
    private val signatureBytes = signature.copyOf()

    init {
        require(payloadBytes.isNotEmpty()) { "canonical payload must not be empty" }
        require(signatureBytes.isNotEmpty()) { "signature must not be empty" }
    }

    fun copyCanonicalPayload(): ByteArray = payloadBytes.copyOf()

    fun copySignature(): ByteArray = signatureBytes.copyOf()

    override fun toString(): String =
        "SignedLicenseEnvelope(schemaVersion=" + schemaVersion +
            ",algorithm=" + algorithm +
            ",keyReference=" + keyReference +
            ",payloadBytes=" + payloadBytes.size +
            ",signatureBytes=" + signatureBytes.size + ")"
}

sealed interface SigningResult {
    data class Signed(val envelope: SignedLicenseEnvelope) : SigningResult
    data class Rejected(val reason: SigningFailure) : SigningResult
}

fun interface LicenseEnvelopeSigner {
    fun sign(
        canonicalPayload: ByteArray,
        keyReference: SigningKeyReference
    ): SigningResult
}
