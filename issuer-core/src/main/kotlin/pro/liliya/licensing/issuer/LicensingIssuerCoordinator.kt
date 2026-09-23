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

        // Activation supplies a grant-bound internal ID. Other issuer requests keep their
        // existing semantics until their own full-request idempotency contract is defined.
        val receiptPort = if (
            request.operation == pro.liliya.licensing.protocol.LicenseOperation.ISSUE &&
            request.requestId?.startsWith("activation:") == true
        ) transactions as? IdempotentDecisionTransactionPort else null
        val receiptId = request.requestId
        if (receiptId != null && receiptPort != null) {
            when (val receipt = receiptPort.lookup(receiptId)) {
                is IssueReceiptLookup.Found -> {
                    if (receipt.requestScope != DecisionScope(request.subjectReference, request.productId)) {
                        return LicensingIssuerResult.Rejected(LicenseServiceFailure.IDEMPOTENCY_CONFLICT)
                    }
                    return LicensingIssuerResult.Issued(receipt.state, receipt.envelope)
                }
                IssueReceiptLookup.Unavailable ->
                    return LicensingIssuerResult.Rejected(LicenseServiceFailure.INTERNAL_FAILURE)
                IssueReceiptLookup.Missing -> Unit
            }
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

        val decision: (DecisionState?) -> DecisionCandidate? = decision@{ current ->
                val currentReplay = current?.replaySequence ?: -1L
                val nextReplay = currentReplay + 1L
                if (nextReplay < 0L) return@decision null
                if (current != null && sourceRecord.revocationEpoch < current.revocationEpoch) {
                    return@decision null
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
                    is CanonicalEntitlementCompositionResult.Rejected -> return@decision null
                }
                val signed = when (val result = signing.sign(entitlement)) {
                    is LicenseSigningCompositionResult.Signed -> result.envelope
                    is LicenseSigningCompositionResult.Rejected -> return@decision null
                }

                DecisionCandidate(
                    nextState = DecisionState(
                        replaySequence = nextReplay,
                        revocationEpoch = sourceRecord.revocationEpoch
                    ),
                    envelope = signed
                )
        }
        val transaction = if (receiptId != null && receiptPort != null) {
            receiptPort.transactOnce(scope, DecisionScope(request.subjectReference, request.productId), receiptId, decision)
        } else {
            transactions.transact(scope, decision)
        }
        return when (transaction) {
            is DecisionTransactionResult.Committed ->
                LicensingIssuerResult.Issued(transaction.state, transaction.envelope)
            is DecisionTransactionResult.Rejected ->
                LicensingIssuerResult.Rejected(
                    when (transaction.reason) {
                        DecisionTransactionFailure.CONFLICT -> LicenseServiceFailure.REPLAY_CONFLICT
                        DecisionTransactionFailure.IDEMPOTENCY_CONFLICT -> LicenseServiceFailure.IDEMPOTENCY_CONFLICT
                        DecisionTransactionFailure.REJECTED -> LicenseServiceFailure.REFRESH_REJECTED
                        DecisionTransactionFailure.INTERNAL_FAILURE -> LicenseServiceFailure.INTERNAL_FAILURE
                    }
                )
        }
    }
}
