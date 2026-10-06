package pro.liliya.licensing.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationWireJsonCodecContractTest {
    @Test
    fun valid_request_round_trips_shape() {
        val decoded = assertIs<ActivationWireDecodeResult.Decoded>(
            ActivationWireJsonCodec.decodeRequest(
                """
                {
                  "wireVersion":1,
                  "activationCode":"LAC1.test",
                  "attemptId":"a-1",
                  "installationId":"installation-A",
                  "deviceKeyFingerprint":"sha256:device-A"
                }
                """.trimIndent().toByteArray()
            )
        )
        assertEquals("LAC1.test", decoded.value.activationCode)
        assertEquals("a-1", decoded.value.attemptId)
        assertEquals("installation-A", decoded.value.installationId)
        assertEquals("sha256:device-A", decoded.value.deviceKeyFingerprint)
    }

    @Test
    fun missing_device_binding_fails_closed() {
        assertIs<ActivationWireDecodeResult.Rejected>(
            ActivationWireJsonCodec.decodeRequest(
                """{"wireVersion":1,"activationCode":"LAC1.test","attemptId":"a-1"}"""
                    .toByteArray()
            )
        )
    }
}
