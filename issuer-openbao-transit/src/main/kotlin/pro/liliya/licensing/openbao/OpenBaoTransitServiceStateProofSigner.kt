package pro.liliya.licensing.openbao

import pro.liliya.licensing.servicestate.ServiceStateAuthenticationProof
import pro.liliya.licensing.servicestate.ServiceStateProofSigner
import pro.liliya.licensing.servicestate.ServiceStateSigningResult

/**
 * Exact-version OpenBao Transit signer for the service-state authentication transcript.
 *
 * This adapter signs bytes only. It does not read or own replay/revocation state and it does not
 * create entitlement, Authority or Execution semantics.
 */
class OpenBaoTransitServiceStateProofSigner(
    private val client: OpenBaoTransitClient,
    private val keyName: String,
    private val keyVersion: Int
) : ServiceStateProofSigner {
    init {
        require(keyName.isNotBlank()) { "service-state OpenBao key name must not be blank" }
        require(keyVersion > 0) { "service-state OpenBao key version must be positive" }
    }

    override fun sign(transcript: ByteArray): ServiceStateSigningResult {
        if (transcript.isEmpty()) {
            return ServiceStateSigningResult.Rejected
        }

        val description = when (val result = client.describeKey(keyName)) {
            is OpenBaoTransitDescribeResult.Available -> result.description
            OpenBaoTransitDescribeResult.Unavailable ->
                return ServiceStateSigningResult.KeyUnavailable
            OpenBaoTransitDescribeResult.Failed ->
                return ServiceStateSigningResult.Failed
        }

        if (
            description.type != OpenBaoTransitProductionSigningProfile.transitKeyType ||
            !description.supportsSigning
        ) {
            return ServiceStateSigningResult.Rejected
        }
        if (keyVersion !in description.availableVersions) {
            return ServiceStateSigningResult.KeyUnavailable
        }

        return when (
            val result = client.sign(
                keyName = keyName,
                keyVersion = keyVersion,
                input = transcript
            )
        ) {
            is OpenBaoTransitSignResult.Signed -> {
                if (result.signature.version != keyVersion) {
                    ServiceStateSigningResult.Rejected
                } else {
                    ServiceStateSigningResult.Signed(
                        ServiceStateAuthenticationProof.of(result.signature.bytes)
                    )
                }
            }
            OpenBaoTransitSignResult.Unavailable -> ServiceStateSigningResult.KeyUnavailable
            OpenBaoTransitSignResult.Rejected -> ServiceStateSigningResult.Rejected
            OpenBaoTransitSignResult.Failed -> ServiceStateSigningResult.Failed
        }
    }

    override fun toString(): String =
        "OpenBaoTransitServiceStateProofSigner(client=<redacted>,keyName=<redacted>," +
            "keyVersion=" + keyVersion + ")"
}
