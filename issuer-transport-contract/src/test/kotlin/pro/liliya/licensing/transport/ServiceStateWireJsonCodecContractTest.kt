package pro.liliya.licensing.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.servicestate.ServiceStateProtocolVersion
import pro.liliya.licensing.servicestate.ServiceStateScope

class ServiceStateWireJsonCodecContractTest {
    @Test
    fun request_round_trip_is_separate_versioned_contract() {
        val request = ServiceStateWireRequest(
            wireVersion = ServiceStateWireJsonCodec.currentVersion,
            protocolVersion = ServiceStateProtocolVersion(1),
            scope = ServiceStateScope(
                productId = "liliya-pro",
                subject = "PRIVATE-SUBJECT"
            ),
            requestId = "PRIVATE-REQUEST"
        )

        val decoded = assertIs<
            ServiceStateWireDecodeResult.Decoded<ServiceStateWireRequest>
        >(
            ServiceStateWireJsonCodec.decodeRequest(
                ServiceStateWireJsonCodec.encodeRequest(request)
            )
        ).value

        assertEquals(1, decoded.wireVersion.value)
        assertEquals(1L, decoded.protocolVersion.value)
        assertEquals("liliya-pro", decoded.scope.productId)
        assertEquals("PRIVATE-SUBJECT", decoded.scope.subject)
    }

    @Test
    fun unsupported_wire_version_fails_closed() {
        val bytes =
            """{"wireVersion":2,"kind":"service-state-request","protocolVersion":1,"productId":"liliya-pro","subjectReference":"private","requestId":"r"}"""
                .encodeToByteArray()

        assertEquals(
            ServiceStateWireDecodeResult.ProtocolFailure,
            ServiceStateWireJsonCodec.decodeRequest(bytes)
        )
    }

    @Test
    fun malformed_service_state_response_fails_closed() {
        val missingProof =
            """{"wireVersion":1,"kind":"service-state","protocolVersion":1,"purpose":"SECURITY_STATE","profile":"ECDSA-P256-SHA256-SERVICE-STATE-V1","signingKeyId":"key","payloadBase64":"AQ=="}"""
                .encodeToByteArray()

        assertEquals(
            ServiceStateWireDecodeResult.ProtocolFailure,
            ServiceStateWireJsonCodec.decodeResponse(missingProof)
        )
    }

    @Test
    fun rendering_redacts_private_scope_and_request_identity() {
        val request = ServiceStateWireRequest(
            wireVersion = ServiceStateWireJsonCodec.currentVersion,
            protocolVersion = ServiceStateProtocolVersion(1),
            scope = ServiceStateScope(
                productId = "liliya-pro",
                subject = "PRIVATE-SUBJECT-DO-NOT-LOG"
            ),
            requestId = "PRIVATE-REQUEST-DO-NOT-LOG"
        )

        val rendered = request.toString()
        kotlin.test.assertFalse("PRIVATE-SUBJECT-DO-NOT-LOG" in rendered)
        kotlin.test.assertFalse("PRIVATE-REQUEST-DO-NOT-LOG" in rendered)
    }
}
