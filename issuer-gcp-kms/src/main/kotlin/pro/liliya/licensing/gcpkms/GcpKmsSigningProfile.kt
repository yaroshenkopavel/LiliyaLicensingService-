package pro.liliya.licensing.gcpkms

import java.security.MessageDigest
import pro.liliya.licensing.signing.LicenseEnvelopeSigner
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

object GcpKmsProductionSigningProfile {
    val schemaVersion = SigningEnvelopeSchemaVersion(1)
    val algorithm = SigningAlgorithm("ECDSA-P256-SHA256")
    const val kmsAlgorithm = "EC_SIGN_P256_SHA256"
}

data class GcpKmsSigningKeyBinding(
    val keyReference: SigningKeyReference,
    val cryptoKeyVersionName: String
) {
    init {
        require(cryptoKeyVersionName.isNotBlank()) {
            "Cloud KMS crypto key version name must not be blank"
        }
    }
}

data class GcpKmsKeyDescription(
    val algorithm: String
)

sealed interface GcpKmsDescribeResult {
    data class Available(val description: GcpKmsKeyDescription) : GcpKmsDescribeResult
    data object Unavailable : GcpKmsDescribeResult
    data object Failed : GcpKmsDescribeResult
}

sealed interface GcpKmsSignResult {
    data class Signed(val signature: ByteArray) : GcpKmsSignResult {
        init { require(signature.isNotEmpty()) { "KMS signature must not be empty" } }
    }
    data object Unavailable : GcpKmsSignResult
    data object Failed : GcpKmsSignResult
}

interface GcpKmsAsymmetricClient {
    fun describe(cryptoKeyVersionName: String): GcpKmsDescribeResult

    fun signSha256Digest(
        cryptoKeyVersionName: String,
        digest: ByteArray
    ): GcpKmsSignResult
}

/**
 * Production Cloud KMS signing adapter.
 *
 * The configured map contains exact logical key ID -> exact KMS key-version ownership.
 * There is no key search, alias fallback, automatic downgrade or old-key retry.
 */
class GcpKmsLicenseEnvelopeSigner(
    bindings: Collection<GcpKmsSigningKeyBinding>,
    private val client: GcpKmsAsymmetricClient
) : LicenseEnvelopeSigner {
    private val bindings: Map<SigningKeyReference, GcpKmsSigningKeyBinding>

    init {
        require(bindings.isNotEmpty()) { "at least one KMS signing binding is required" }
        val byKey = bindings.associateBy { it.keyReference }
        require(byKey.size == bindings.size) { "duplicate KMS signing key reference" }
        this.bindings = byKey
    }

    override fun sign(
        canonicalPayload: ByteArray,
        keyReference: SigningKeyReference
    ): SigningResult {
        val binding = bindings[keyReference]
            ?: return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)

        val description = when (val result = client.describe(binding.cryptoKeyVersionName)) {
            is GcpKmsDescribeResult.Available -> result.description
            GcpKmsDescribeResult.Unavailable ->
                return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
            GcpKmsDescribeResult.Failed ->
                return SigningResult.Rejected(SigningFailure.INTERNAL_FAILURE)
        }
        if (description.algorithm != GcpKmsProductionSigningProfile.kmsAlgorithm) {
            return SigningResult.Rejected(SigningFailure.SIGNING_REJECTED)
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload)
        val signature = when (
            val result = client.signSha256Digest(binding.cryptoKeyVersionName, digest)
        ) {
            is GcpKmsSignResult.Signed -> result.signature
            GcpKmsSignResult.Unavailable ->
                return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
            GcpKmsSignResult.Failed ->
                return SigningResult.Rejected(SigningFailure.INTERNAL_FAILURE)
        }

        return SigningResult.Signed(
            SignedLicenseEnvelope(
                schemaVersion = GcpKmsProductionSigningProfile.schemaVersion,
                algorithm = GcpKmsProductionSigningProfile.algorithm,
                keyReference = keyReference,
                canonicalPayload = canonicalPayload,
                signature = signature
            )
        )
    }
}
