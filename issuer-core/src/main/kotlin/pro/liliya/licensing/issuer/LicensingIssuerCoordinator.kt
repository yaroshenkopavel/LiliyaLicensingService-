package pro.liliya.licensing.issuer

import pro.liliya.licensing.protocol.CanonicalEntitlementComposer
import pro.liliya.licensing.protocol.CanonicalEntitlementCompositionResult
import pro.liliya.licensing.protocol.EntitlementDecision
import pro.liliya.licensing.protocol.LicenseRequestValidationResult
import pro.liliya.licensing.protocol.LicenseRequestValidator
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.LicenseSigningCompositionResult

sealed interface LicensingIssuerResult {
    data class Issued(
        val state: DecisionState,
        val envelope: pro.liliya.licensing.signing.SignedLicenseEnvelope
    ) : LicensingIssuerResult

    data class Rejected(val reason: LicenseServiceFailure) : LicensingIssuerResult
}

class LicensingIssuerCoordinator(
    private val validator: LicenseRequestValidator,
    private val source: EntitlementSourcePort,
    private val transactions: DecisionTransactionPort,
    private val signing: LicenseSigningComposition
) {
    fun process(request: LicenseServiceRequest): LicensingIssuerResult {
        val validated = validator.validate(request)
        if (validated is LicenseRequestValidationResult.Rejected) {
            return LicensingIssuerResult.Rejected(validated.reason)
        }

        val sourceRecord = when (val resolved = source.resolve(request)) {
            is EntitlementSourceResult.Eligible -> resolved.record
            is EntitlementSourceResult.Ineligible -> return LicensingIssuerResult.Rejected(resolved.reason)
            is EntitlementSourceResult.Failed -> return LicensingIssuerResult.Rejected(resolved.reason)
        }

        val scope = DecisionScope(
            subject = sourceRecord.subject,
            productId = sourceRecord.productId
        )

        return when (
            val transaction = transactions.transact(scope) { current ->
                val currentReplay = current?.replaySequence ?: -1L
                val nextReplay = currentReplay + 1L
                if (nextReplay < 0L) return@transact null
                if (current != null && sourceRecord.revocationEpoch < current.revocationEpoch) {
                    return@transact null
                }

                val composed = CanonicalEntitlementComposer.compose(
                    EntitlementDecision(
                        licenseId = sourceRecord.licenseId,
                        subject = sourceRecord.subject,
                        productId = sourceRecord.productId,
                        features = sourceRecord.features,
                        version = sourceRecord.version,
                        signingKeyId = sourceRecord.signingKeyId,
                        issuedAt = sourceRecord.issuedAt,
                        notBefore = sourceRecord.notBefore,
                        expiresAt = sourceRecord.expiresAt,
                        offlineLeaseUntil = sourceRecord.offlineLeaseUntil,
                        revocationEpoch = sourceRecord.revocationEpoch,
                        replaySequence = nextReplay
                    )
                )
                val entitlement = when (composed) {
                    is CanonicalEntitlementCompositionResult.Composed -> composed.entitlement
                    is CanonicalEntitlementCompositionResult.Rejected -> return@transact null
                }
                val signed = when (val result = signing.sign(entitlement)) {
                    is LicenseSigningCompositionResult.Signed -> result.envelope
                    is LicenseSigningCompositionResult.Rejected -> return@transact null
                }

                DecisionCandidate(
                    nextState = DecisionState(
                        replaySequence = nextReplay,
                        revocationEpoch = sourceRecord.revocationEpoch
                    ),
                    envelope = signed
                )
            }
        ) {
            is DecisionTransactionResult.Committed ->
                LicensingIssuerResult.Issued(transaction.state, transaction.envelope)
            is DecisionTransactionResult.Rejected ->
                LicensingIssuerResult.Rejected(
                    when (transaction.reason) {
                        DecisionTransactionFailure.CONFLICT -> LicenseServiceFailure.REPLAY_CONFLICT
                        DecisionTransactionFailure.REJECTED -> LicenseServiceFailure.REFRESH_REJECTED
                        DecisionTransactionFailure.INTERNAL_FAILURE -> LicenseServiceFailure.INTERNAL_FAILURE
                    }
                )
        }
    }
}
