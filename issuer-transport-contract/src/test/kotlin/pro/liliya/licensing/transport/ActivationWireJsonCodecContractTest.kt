package pro.liliya.licensing.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationWireJsonCodecContractTest {
    @Test
    fun valid_request_round_trips_shape() {
        val decoded = assertIs<ActivationWireDecodeResult.Decoded>(
            ActivationWireJsonCodec.decodeRequest(
                """{"wireVersion":1,"activationCode":"LAC1.test","attemptId":"a-1"}"""
                    .toByteArray()
            )
        )
        assertEquals("LAC1.test", decoded.value.activationCode)
        assertEquals("a-1", decoded.value.attemptId)
    }

    @Test
    fun malformed_request_fails_closed() {
        assertIs<ActivationWireDecodeResult.Rejected>(
            ActivationWireJsonCodec.decodeRequest(
                """{"wireVersion":1,"activationCode":"","attemptId":"a-1"}"""
                    .toByteArray()
            )
        )
    }
}
