package pro.liliya.licensing.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse

class LicenseHttpEndpointPrivacyContractTest {
    @Test
    fun request_and_response_rendering_never_expose_body_content() {
        val marker = "PRIVATE-HTTP-BODY-MARKER"
        val request = LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = LicenseHttpEndpoint.PATH,
            body = marker.encodeToByteArray()
        )
        val response = LicenseHttpResponse(
            status = 500,
            contentType = "text/plain",
            body = marker.encodeToByteArray()
        )

        assertFalse(marker in request.toString())
        assertFalse(marker in response.toString())
        assertTrue("body=<redacted>" in request.toString())
        assertTrue("body=<redacted>" in response.toString())
    }

    @Test
    fun malformed_request_with_private_marker_is_not_reflected_and_processor_is_not_called() {
        val marker = "PRIVATE-SUBJECT-MARKER"
        var calls = 0
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                calls++
                error("processor must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = LicenseHttpEndpoint.PATH,
                body = (
                    """{"wireVersion":1,"kind":"request","protocolVersion":1,"operation":"ISSUE","productId":"liliya-pro","subjectReference":"$marker""""
                ).encodeToByteArray()
            )
        )

        assertEquals(0, calls)
        assertEquals(400, response.status)
        assertFalse(marker in response.body.decodeToString())
        assertFalse(marker in response.toString())

        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        assertEquals(
            LicenseServiceFailure.INVALID_REQUEST,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun processor_exception_secret_text_is_not_reflected() {
        val marker = "PRIVATE-PROCESSOR-EXCEPTION-MARKER"
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                throw IllegalStateException(marker)
            }
        )

        val response = endpoint.handle(validRequest())

        assertEquals(500, response.status)
        assertTrue(response.body.isEmpty())
        assertFalse(marker in response.toString())
    }

    @Test
    fun typed_service_rejection_contains_only_failure_identity() {
        val marker = "PRIVATE-REJECTION-SUBJECT-MARKER"
        val endpoint = LicenseHttpEndpoint(
            LicensingIssuerProcessor {
                assertFalse(marker in it.toString())
                LicensingIssuerResult.Rejected(
                    LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE
                )
            }
        )

        val response = endpoint.handle(validRequest(subject = marker))

        assertEquals(403, response.status)
        assertFalse(marker in response.body.decodeToString())
        assertFalse(marker in response.toString())
    }

    private fun validRequest(
        subject: String = "private-subject"
    ): LicenseHttpRequest =
        LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = LicenseHttpEndpoint.PATH,
            body = (
                """{"wireVersion":1,"kind":"request","protocolVersion":1,"operation":"ISSUE","productId":"liliya-pro","subjectReference":"$subject","requestId":"private-request"}"""
            ).encodeToByteArray()
        )
}
