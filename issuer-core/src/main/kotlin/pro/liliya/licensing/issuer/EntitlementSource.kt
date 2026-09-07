package pro.liliya.licensing.issuer

import java.time.Instant
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest

data class EntitlementSourceRecord(
    val licenseId: String,
    val subject: String,
    val productId: String,
    val features: Set<String>,
    val version: Long,
    val signingKeyId: String,
    val issuedAt: Instant,
    val notBefore: Instant,
    val expiresAt: Instant?,
    val offlineLeaseUntil: Instant?,
    val revocationEpoch: Long
) {
    override fun toString(): String =
        "EntitlementSourceRecord(licenseId=" + licenseId +
            ",subject=<redacted>,productId=" + productId +
            ",featuresCount=" + features.size +
            ",version=" + version +
            ",signingKeyId=" + signingKeyId +
            ",issuedAt=" + issuedAt +
            ",notBefore=" + notBefore +
            ",expiresAt=" + expiresAt +
            ",offlineLeaseUntil=" + offlineLeaseUntil +
            ",revocationEpoch=" + revocationEpoch + ")"
}

sealed interface EntitlementSourceResult {
    data class Eligible(val record: EntitlementSourceRecord) : EntitlementSourceResult
    data class Ineligible(val reason: LicenseServiceFailure) : EntitlementSourceResult
    data class Failed(val reason: LicenseServiceFailure) : EntitlementSourceResult
}

fun interface EntitlementSourcePort {
    fun resolve(request: LicenseServiceRequest): EntitlementSourceResult
}
