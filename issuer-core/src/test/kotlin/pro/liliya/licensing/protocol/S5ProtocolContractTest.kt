package pro.liliya.licensing.protocol

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class S5ProtocolContractTest {
    @Test
    fun supported_issue_request_is_accepted() {
        val request = LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = "liliya-pro",
            subjectReference = "subject-001"
        )

        assertIs<LicenseRequestValidationResult.Accepted>(
            LicenseRequestValidator(LicenseProtocolVersion(1)).validate(request)
        )
    }

    @Test
    fun unsupported_protocol_is_rejected_explicitly() {
        val request = LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(2),
            operation = LicenseOperation.REFRESH,
            productId = "liliya-pro",
            subjectReference = "subject-001"
        )

        val rejected = assertIs<LicenseRequestValidationResult.Rejected>(
            LicenseRequestValidator(LicenseProtocolVersion(1)).validate(request)
        )
        assertEquals(LicenseServiceFailure.UNSUPPORTED_PROTOCOL, rejected.reason)
    }

    @Test
    fun raw_malformed_request_is_rejected_as_typed_invalid_request() {
        val rejected = assertIs<LicenseServiceRequestCreationResult.Rejected>(
            LicenseServiceRequestFactory.create(
                protocolVersion = 1,
                operation = "ISSUE",
                productId = "liliya-pro",
                subjectReference = "   "
            )
        )

        assertEquals(LicenseServiceFailure.INVALID_REQUEST, rejected.reason)
    }

    @Test
    fun unknown_raw_operation_is_rejected_as_typed_invalid_request() {
        val rejected = assertIs<LicenseServiceRequestCreationResult.Rejected>(
            LicenseServiceRequestFactory.create(
                protocolVersion = 1,
                operation = "MUTATE_OLD_LICENSE",
                productId = "liliya-pro",
                subjectReference = "subject-001"
            )
        )

        assertEquals(LicenseServiceFailure.INVALID_REQUEST, rejected.reason)
    }

    @Test
    fun raw_refresh_request_is_created_without_old_envelope_field() {
        val created = assertIs<LicenseServiceRequestCreationResult.Created>(
            LicenseServiceRequestFactory.create(
                protocolVersion = 1,
                operation = "REFRESH",
                productId = "liliya-pro",
                subjectReference = "subject-001",
                requestId = "refresh-attempt-001"
            )
        )

        assertEquals(LicenseOperation.REFRESH, created.request.operation)
        assertEquals("refresh-attempt-001", created.request.requestId)
    }

    @Test
    fun canonical_composition_rejects_structurally_invalid_entitlement() {
        val result = CanonicalEntitlementComposer.compose(
            decision(features = emptySet())
        )
        val rejected = assertIs<CanonicalEntitlementCompositionResult.Rejected>(result)
        assertEquals(LicenseServiceFailure.INVALID_REQUEST, rejected.reason)
    }

    @Test
    fun canonical_encoding_is_feature_order_independent() {
        val a = entitlement(setOf("offline", "core"))
        val b = entitlement(setOf("core", "offline"))

        assertContentEquals(
            CanonicalEntitlementCodec.encode(a),
            CanonicalEntitlementCodec.encode(b)
        )
    }

    @Test
    fun canonical_encoding_matches_frozen_liliy_core_fixed_vector() {
        val encoded = CanonicalEntitlementCodec.encode(entitlement(setOf("core", "offline")))

        assertEquals(
            "4c494331000000076c69632d3030310000000b7375626a6563742d303031" +
                "0000000a6c696c6979612d70726f0000000200000004636f726500000007" +
                "6f66666c696e6500000000000000010000000a746573742d6b65792d3100" +
                "0000006a9e6f0000000000000000006a9e6f000000000001000000006ac5" +
                "fc000000000001000000006aa7a980000000000000000000000003010000" +
                "000000000009",
            encoded.toHex()
        )
    }

    private fun entitlement(features: Set<String>) = CanonicalLicenseEntitlement(
        id = "lic-001",
        subject = "subject-001",
        productId = "liliya-pro",
        features = features,
        version = 1,
        signingKeyId = "test-key-1",
        issuedAt = Instant.parse("2026-09-07T08:00:00Z"),
        notBefore = Instant.parse("2026-09-07T08:00:00Z"),
        expiresAt = Instant.parse("2026-10-07T08:00:00Z"),
        offlineLeaseUntil = Instant.parse("2026-09-14T08:00:00Z"),
        revocationEpoch = 3,
        replaySequence = 9
    )

    private fun decision(features: Set<String>) = EntitlementDecision(
        licenseId = "lic-001",
        subject = "subject-001",
        productId = "liliya-pro",
        features = features,
        version = 1,
        signingKeyId = "test-key-1",
        issuedAt = Instant.parse("2026-09-07T08:00:00Z"),
        notBefore = Instant.parse("2026-09-07T08:00:00Z"),
        expiresAt = Instant.parse("2026-10-07T08:00:00Z"),
        offlineLeaseUntil = Instant.parse("2026-09-14T08:00:00Z"),
        revocationEpoch = 3,
        replaySequence = 9
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
