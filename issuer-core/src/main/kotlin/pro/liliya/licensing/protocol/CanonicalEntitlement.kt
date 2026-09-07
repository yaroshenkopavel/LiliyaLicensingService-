package pro.liliya.licensing.protocol

import java.time.Instant

data class CanonicalLicenseEntitlement(
    val id: String,
    val subject: String,
    val productId: String,
    val features: Set<String>,
    val version: Long,
    val signingKeyId: String,
    val issuedAt: Instant,
    val notBefore: Instant,
    val expiresAt: Instant?,
    val offlineLeaseUntil: Instant?,
    val revocationEpoch: Long,
    val replaySequence: Long?
) {
    init {
        require(id.isNotBlank()) { "license id must not be blank" }
        require(subject.isNotBlank()) { "license subject must not be blank" }
        require(productId.isNotBlank()) { "license product id must not be blank" }
        require(features.isNotEmpty()) { "license features must not be empty" }
        require(features.none { it.isBlank() }) { "license feature must not be blank" }
        require(version > 0L) { "license version must be positive" }
        require(signingKeyId.isNotBlank()) { "license key id must not be blank" }
        require(revocationEpoch >= 0L) { "license revocation epoch must not be negative" }
        require(replaySequence == null || replaySequence >= 0L) {
            "license replay sequence must not be negative"
        }
        require(expiresAt == null || expiresAt.isAfter(notBefore)) {
            "license expiry must be after not-before"
        }
        require(offlineLeaseUntil == null || !offlineLeaseUntil.isBefore(notBefore)) {
            "license offline lease must not end before not-before"
        }
        require(expiresAt == null || offlineLeaseUntil == null || !offlineLeaseUntil.isAfter(expiresAt)) {
            "license offline lease must not exceed license expiry"
        }
    }
}

sealed interface CanonicalEntitlementCompositionResult {
    data class Composed(val entitlement: CanonicalLicenseEntitlement) :
        CanonicalEntitlementCompositionResult
    data class Rejected(val reason: LicenseServiceFailure) :
        CanonicalEntitlementCompositionResult
}

object CanonicalEntitlementComposer {
    fun compose(decision: EntitlementDecision): CanonicalEntitlementCompositionResult =
        try {
            CanonicalEntitlementCompositionResult.Composed(
                CanonicalLicenseEntitlement(
                    id = decision.licenseId,
                    subject = decision.subject,
                    productId = decision.productId,
                    features = decision.features.toSet(),
                    version = decision.version,
                    signingKeyId = decision.signingKeyId,
                    issuedAt = decision.issuedAt,
                    notBefore = decision.notBefore,
                    expiresAt = decision.expiresAt,
                    offlineLeaseUntil = decision.offlineLeaseUntil,
                    revocationEpoch = decision.revocationEpoch,
                    replaySequence = decision.replaySequence
                )
            )
        } catch (_: IllegalArgumentException) {
            CanonicalEntitlementCompositionResult.Rejected(LicenseServiceFailure.INVALID_REQUEST)
        }
}
