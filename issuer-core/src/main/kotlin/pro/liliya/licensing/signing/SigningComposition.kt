package pro.liliya.licensing.signing

import pro.liliya.licensing.protocol.CanonicalEntitlementCodec
import pro.liliya.licensing.protocol.CanonicalLicenseEntitlement
import pro.liliya.licensing.protocol.LicenseServiceFailure

sealed interface LicenseSigningCompositionResult {
    data class Signed(val envelope: SignedLicenseEnvelope) : LicenseSigningCompositionResult
    data class Rejected(val reason: LicenseServiceFailure) : LicenseSigningCompositionResult
}

class LicenseSigningComposition(
    private val signer: LicenseEnvelopeSigner
) {
    fun sign(entitlement: CanonicalLicenseEntitlement): LicenseSigningCompositionResult {
        val payload = CanonicalEntitlementCodec.encode(entitlement)
        val exactKey = SigningKeyReference(entitlement.signingKeyId)

        return when (val result = signer.sign(payload, exactKey)) {
            is SigningResult.Signed -> {
                if (result.envelope.keyReference != exactKey) {
                    LicenseSigningCompositionResult.Rejected(
                        LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE
                    )
                } else if (!result.envelope.copyCanonicalPayload().contentEquals(payload)) {
                    LicenseSigningCompositionResult.Rejected(
                        LicenseServiceFailure.INTERNAL_FAILURE
                    )
                } else {
                    LicenseSigningCompositionResult.Signed(result.envelope)
                }
            }
            is SigningResult.Rejected -> {
                LicenseSigningCompositionResult.Rejected(
                    when (result.reason) {
                        SigningFailure.KEY_UNAVAILABLE,
                        SigningFailure.KEY_RETIRED -> LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE
                        SigningFailure.SIGNING_REJECTED,
                        SigningFailure.INTERNAL_FAILURE -> LicenseServiceFailure.INTERNAL_FAILURE
                    }
                )
            }
        }
    }
}
