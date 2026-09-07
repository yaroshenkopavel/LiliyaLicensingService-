package pro.liliya.licensing.privacy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.EntitlementSourceRecord
import pro.liliya.licensing.protocol.CanonicalLicenseEntitlement
import pro.liliya.licensing.protocol.EntitlementDecision
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseRequestValidationResult
import pro.liliya.licensing.protocol.LicenseServiceRequest

class FailurePrivacyRenderingContractTest {
    private val privateSubject = "PRIVATE-SUBJECT-DO-NOT-LOG"
    private val privateRequestId = "PRIVATE-REQUEST-ID-DO-NOT-LOG"
    private val privateEnrollment = "PRIVATE-ENROLLMENT-DO-NOT-LOG"

    @Test
    fun request_and_wrapped_validation_rendering_redact_private_identifiers() {
        val request = LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = "liliya-pro",
            subjectReference = privateSubject,
            requestId = privateRequestId,
            enrollmentReference = privateEnrollment
        )

        assertRedacted(request.toString())
        assertRedacted(LicenseRequestValidationResult.Accepted(request).toString())
    }

    @Test
    fun canonical_and_source_records_redact_private_subject() {
        val entitlement = CanonicalLicenseEntitlement(
            id = "lic-privacy",
            subject = privateSubject,
            productId = "liliya-pro",
            features = setOf("core", "offline"),
            version = 1,
            signingKeyId = "prod-key-v2",
            issuedAt = Instant.parse("2026-09-07T09:00:00Z"),
            notBefore = Instant.parse("2026-09-07T09:00:00Z"),
            expiresAt = Instant.parse("2026-10-07T09:00:00Z"),
            offlineLeaseUntil = Instant.parse("2026-09-14T09:00:00Z"),
            revocationEpoch = 3,
            replaySequence = 7
        )
        val source = EntitlementSourceRecord(
            licenseId = entitlement.id,
            subject = privateSubject,
            productId = entitlement.productId,
            features = entitlement.features,
            version = entitlement.version,
            signingKeyId = entitlement.signingKeyId,
            issuedAt = entitlement.issuedAt,
            notBefore = entitlement.notBefore,
            expiresAt = entitlement.expiresAt,
            offlineLeaseUntil = entitlement.offlineLeaseUntil,
            revocationEpoch = entitlement.revocationEpoch
        )

        assertRedacted(entitlement.toString())
        assertRedacted(source.toString())
    }

    @Test
    fun entitlement_decision_and_transaction_scope_redact_private_subject() {
        val decision = EntitlementDecision(
            licenseId = "lic-privacy",
            subject = privateSubject,
            productId = "liliya-pro",
            features = setOf("core"),
            version = 1,
            signingKeyId = "prod-key-v2",
            issuedAt = Instant.parse("2026-09-07T09:00:00Z"),
            notBefore = Instant.parse("2026-09-07T09:00:00Z"),
            expiresAt = null,
            offlineLeaseUntil = null,
            revocationEpoch = 0,
            replaySequence = 0
        )
        val scope = DecisionScope(privateSubject, "liliya-pro")

        assertRedacted(decision.toString())
        assertRedacted(scope.toString())
    }

    private fun assertRedacted(rendered: String) {
        assertFalse(privateSubject in rendered)
        assertFalse(privateRequestId in rendered)
        assertFalse(privateEnrollment in rendered)
        assertTrue("<redacted>" in rendered)
    }
}
