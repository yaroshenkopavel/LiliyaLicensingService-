package pro.liliya.licensing.http

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireRequest
import pro.liliya.licensing.transport.LicenseWireResponse

class LicenseHttpEndpointContractTest {
    @Test
    fun valid_request_invokes_processor_exactly_once_and_returns_exact_signed_envelope() {
        var calls = 0
        var seen: LicenseServiceRequest? = null
        val envelope = envelope()
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor { request ->
                calls++
                seen = request
                LicensingIssuerResult.Issued(
                    state = DecisionState(7, 3),
                    envelope = envelope
                )
            }
        )
        val serviceRequest = request()

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = LicenseHttpEndpoint.PATH,
                body = LicenseWireJsonCodec.encodeRequest(
                    LicenseWireRequest.ServiceRequest(
                        LicenseWireJsonCodec.currentVersion,
                        serviceRequest
                    )
                )
            )
        )

        assertEquals(1, calls)
        assertEquals(serviceRequest, seen)
        assertEquals(200, response.status)
        assertEquals(LicenseHttpEndpoint.JSON, response.contentType)

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        val success = assertIs<LicenseWireResponse.SignedSuccess>(decoded)

        assertEquals(envelope.schemaVersion, success.envelope.schemaVersion)
        assertEquals(envelope.algorithm, success.envelope.algorithm)
        assertEquals(envelope.keyReference, success.envelope.keyReference)
        assertContentEquals(
            envelope.copyCanonicalPayload(),
            success.envelope.copyCanonicalPayload()
        )
        assertContentEquals(
            envelope.copySignature(),
            success.envelope.copySignature()
        )
    }

    @Test
    fun malformed_wire_request_invokes_processor_zero_times_and_returns_typed_invalid_request() {
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                error("must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = LicenseHttpEndpoint.PATH,
                body = "{not-json".encodeToByteArray()
            )
        )

        assertEquals(0, calls)
        assertEquals(400, response.status)

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        assertEquals(
            LicenseServiceFailure.INVALID_REQUEST,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun coordinator_rejection_maps_to_typed_http_service_response() {
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                LicensingIssuerResult.Rejected(
                    LicenseServiceFailure.REPLAY_CONFLICT
                )
            }
        )

        val response = endpoint.handle(validHttpRequest())

        assertEquals(1, calls)
        assertEquals(409, response.status)

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        assertEquals(
            LicenseServiceFailure.REPLAY_CONFLICT,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun signer_or_source_unavailable_maps_to_503_without_transport_retry() {
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                LicensingIssuerResult.Rejected(
                    LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE
                )
            }
        )

        val response = endpoint.handle(validHttpRequest())

        assertEquals(1, calls)
        assertEquals(503, response.status)
        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        assertEquals(
            LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun unexpected_processor_exception_does_not_leak_exception_message() {
        val secretMarker = "PRIVATE-SUBJECT-OR-SECRET-MARKER"
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                throw IllegalStateException(secretMarker)
            }
        )

        val response = endpoint.handle(validHttpRequest())

        assertEquals(500, response.status)
        assertTrue(response.body.isEmpty())
        assertTrue(secretMarker !in response.body.decodeToString())
    }

    @Test
    fun wrong_method_invokes_processor_zero_times() {
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                error("must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.GET,
                path = LicenseHttpEndpoint.PATH,
                body = byteArrayOf()
            )
        )

        assertEquals(405, response.status)
        assertEquals(0, calls)
        assertTrue(response.body.isEmpty())
    }

    @Test
    fun wrong_route_invokes_processor_zero_times() {
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                error("must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = "/not-license",
                body = byteArrayOf()
            )
        )

        assertEquals(404, response.status)
        assertEquals(0, calls)
        assertTrue(response.body.isEmpty())
    }

    private fun validHttpRequest(): LicenseHttpRequest =
        LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = LicenseHttpEndpoint.PATH,
            body = LicenseWireJsonCodec.encodeRequest(
                LicenseWireRequest.ServiceRequest(
                    LicenseWireJsonCodec.currentVersion,
                    request()
                )
            )
        )

    private fun request() =
        LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = "liliya-pro",
            subjectReference = "subject-private",
            requestId = "attempt-001"
        )

    private fun envelope() =
        SignedLicenseEnvelope(
            schemaVersion = SigningEnvelopeSchemaVersion(1),
            algorithm = SigningAlgorithm("ECDSA-P256-SHA256"),
            keyReference = SigningKeyReference("openbao-prod-v2"),
            canonicalPayload = byteArrayOf(1, 2, 3, 4),
            signature = byteArrayOf(9, 8, 7)
        )
}
