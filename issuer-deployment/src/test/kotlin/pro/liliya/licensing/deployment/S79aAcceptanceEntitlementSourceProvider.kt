package pro.liliya.licensing.deployment

import java.time.Instant
import pro.liliya.licensing.issuer.EntitlementSourcePort
import pro.liliya.licensing.issuer.EntitlementSourceRecord
import pro.liliya.licensing.issuer.EntitlementSourceResult
import pro.liliya.licensing.protocol.LicenseServiceFailure

/**
 * Test-runtime-only provider used to prove ServiceLoader wiring of the real production entrypoint.
 */
class S79aAcceptanceEntitlementSourceProvider :
    DeploymentEntitlementSourceProvider {
    override fun create(): EntitlementSourcePort =
        EntitlementSourcePort { request ->
            if (request.productId != "liliya-pro") {
                EntitlementSourceResult.Ineligible(
                    LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE
                )
            } else {
                val issuedAt = Instant.parse("2026-09-07T00:00:00Z")
                EntitlementSourceResult.Eligible(
                    EntitlementSourceRecord(
                        licenseId = "s7-9a-acceptance-license",
                        subject = request.subjectReference,
                        productId = request.productId,
                        features = setOf("model.local"),
                        version = 1,
                        signingKeyId = "s7-9a-logical-key",
                        issuedAt = issuedAt,
                        notBefore = issuedAt,
                        expiresAt = Instant.parse("2030-09-07T00:00:00Z"),
                        offlineLeaseUntil = Instant.parse("2027-09-07T00:00:00Z"),
                        revocationEpoch = 1
                    )
                )
            }
        }
}
