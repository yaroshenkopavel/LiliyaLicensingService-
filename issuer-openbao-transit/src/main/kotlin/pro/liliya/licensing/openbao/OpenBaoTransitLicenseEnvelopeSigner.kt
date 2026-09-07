package pro.liliya.licensing.openbao

import pro.liliya.licensing.signing.LicenseEnvelopeSigner
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

object OpenBaoTransitProductionSigningProfile {
    val schemaVersion = SigningEnvelopeSchemaVersion(1)
    val algorithm = SigningAlgorithm("ECDSA-P256-SHA256")
    const val transitKeyType = "ecdsa-p256"
}

data class OpenBaoTransitKeyBinding(
    val keyReference: SigningKeyReference,
    val keyName: String,
    val keyVersion: Int
) {
    init {
        require(keyName.isNotBlank()) { "OpenBao key name must not be blank" }
        require(keyVersion > 0) { "OpenBao key version must be positive" }
    }
}

/**
 * Exact-version OpenBao Transit signer.
 *
 * One reviewed logical key reference maps to one exact Transit key name + version.
 * The adapter never searches for another key/version and never falls back to latest.
 */
class OpenBaoTransitLicenseEnvelopeSigner(
    bindings: Collection<OpenBaoTransitKeyBinding>,
    private val client: OpenBaoTransitClient
) : LicenseEnvelopeSigner {
    private val bindings: Map<SigningKeyReference, OpenBaoTransitKeyBinding>

    init {
        require(bindings.isNotEmpty()) { "at least one OpenBao key binding is required" }
        val mapped = bindings.associateBy { it.keyReference }
        require(mapped.size == bindings.size) { "duplicate OpenBao signing key reference" }
        this.bindings = mapped
    }

    override fun sign(
        canonicalPayload: ByteArray,
        keyReference: SigningKeyReference
    ): SigningResult {
        val binding = bindings[keyReference]
            ?: return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)

        val description = when (val result = client.describeKey(binding.keyName)) {
            is OpenBaoTransitDescribeResult.Available -> result.description
            OpenBaoTransitDescribeResult.Unavailable ->
                return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
            OpenBaoTransitDescribeResult.Failed ->
                return SigningResult.Rejected(SigningFailure.INTERNAL_FAILURE)
        }

        if (
            description.type != OpenBaoTransitProductionSigningProfile.transitKeyType ||
            !description.supportsSigning
        ) {
            return SigningResult.Rejected(SigningFailure.SIGNING_REJECTED)
        }

        if (binding.keyVersion !in description.availableVersions) {
            return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
        }

        val signature = when (
            val result = client.sign(
                keyName = binding.keyName,
                keyVersion = binding.keyVersion,
                input = canonicalPayload
            )
        ) {
            is OpenBaoTransitSignResult.Signed -> result.signature
            OpenBaoTransitSignResult.Unavailable ->
                return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
            OpenBaoTransitSignResult.Rejected ->
                return SigningResult.Rejected(SigningFailure.SIGNING_REJECTED)
            OpenBaoTransitSignResult.Failed ->
                return SigningResult.Rejected(SigningFailure.INTERNAL_FAILURE)
        }

        if (signature.version != binding.keyVersion) {
            return SigningResult.Rejected(SigningFailure.SIGNING_REJECTED)
        }

        return SigningResult.Signed(
            SignedLicenseEnvelope(
                schemaVersion = OpenBaoTransitProductionSigningProfile.schemaVersion,
                algorithm = OpenBaoTransitProductionSigningProfile.algorithm,
                keyReference = keyReference,
                canonicalPayload = canonicalPayload,
                signature = signature.bytes
            )
        )
    }
}
