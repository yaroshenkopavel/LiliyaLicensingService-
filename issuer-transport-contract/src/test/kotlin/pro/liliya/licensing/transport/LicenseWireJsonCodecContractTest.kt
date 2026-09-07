package pro.liliya.licensing.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

class LicenseWireJsonCodecContractTest {
    @Test
    fun issue_request_round_trip_preserves_structural_fields() {
        val request = LicenseWireRequest.ServiceRequest(
            LicenseWireJsonCodec.currentVersion,
            LicenseServiceRequest(
                protocolVersion = LicenseProtocolVersion(1),
                operation = LicenseOperation.ISSUE,
                productId = "liliya-pro",
                subjectReference = "subject-private",
                requestId = "attempt-001"
            )
        )

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireRequest.ServiceRequest>>(
            LicenseWireJsonCodec.decodeRequest(
                LicenseWireJsonCodec.encodeRequest(request)
            )
        ).value

        assertEquals(request.request, decoded.request)
    }

    @Test
    fun refresh_request_round_trip_preserves_operation() {
        val request = LicenseWireRequest.ServiceRequest(
            LicenseWireJsonCodec.currentVersion,
            LicenseServiceRequest(
                protocolVersion = LicenseProtocolVersion(1),
                operation = LicenseOperation.REFRESH,
                productId = "liliya-pro",
                subjectReference = "subject-private",
                enrollmentReference = "enrollment-opaque"
            )
        )

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireRequest.ServiceRequest>>(
            LicenseWireJsonCodec.decodeRequest(
                LicenseWireJsonCodec.encodeRequest(request)
            )
        ).value

        assertEquals(LicenseOperation.REFRESH, decoded.request.operation)
        assertEquals("enrollment-opaque", decoded.request.enrollmentReference)
    }

    @Test
    fun signed_response_round_trip_preserves_exact_payload_and_signature_bytes() {
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1)
        val signature = byteArrayOf(9, 8, 7, 6, -2)
        val response = LicenseWireResponse.SignedSuccess(
            LicenseWireJsonCodec.currentVersion,
            SignedLicenseEnvelope(
                schemaVersion = SigningEnvelopeSchemaVersion(1),
                algorithm = SigningAlgorithm("ECDSA-P256-SHA256"),
                keyReference = SigningKeyReference("prod-v2"),
                canonicalPayload = payload,
                signature = signature
            )
        )

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(
                LicenseWireJsonCodec.encodeResponse(response)
            )
        ).value
        val success = assertIs<LicenseWireResponse.SignedSuccess>(decoded)

        assertEquals(1L, success.envelope.schemaVersion.value)
        assertEquals("ECDSA-P256-SHA256", success.envelope.algorithm.value)
        assertEquals("prod-v2", success.envelope.keyReference.value)
        assertContentEquals(payload, success.envelope.copyCanonicalPayload())
        assertContentEquals(signature, success.envelope.copySignature())
    }

    @Test
    fun typed_service_rejection_round_trip_is_exact() {
        val response = LicenseWireResponse.ServiceRejected(
            LicenseWireJsonCodec.currentVersion,
            LicenseServiceFailure.REFRESH_REJECTED
        )

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(
                LicenseWireJsonCodec.encodeResponse(response)
            )
        ).value

        assertEquals(
            LicenseServiceFailure.REFRESH_REJECTED,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun unsupported_wire_version_fails_as_protocol_failure() {
        val result = LicenseWireJsonCodec.decodeRequest(
            """{"wireVersion":2,"kind":"request","protocolVersion":1,"operation":"ISSUE","productId":"liliya-pro","subjectReference":"x"}"""
                .encodeToByteArray()
        )

        assertEquals(
            LicenseTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseWireDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun malformed_signed_success_fails_as_protocol_failure() {
        val result = LicenseWireJsonCodec.decodeResponse(
            """{"wireVersion":1,"kind":"success","schemaVersion":1,"algorithm":"ECDSA-P256-SHA256","keyReference":"prod-v2","payloadBase64":"***","signatureBase64":"AQI="}"""
                .encodeToByteArray()
        )

        assertEquals(
            LicenseTransportFailure.PROTOCOL_FAILURE,
            assertIs<LicenseWireDecodeResult.Rejected>(result).reason
        )
    }
}
