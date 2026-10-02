package pro.liliya.licensing.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceRebindWireJsonCodecContractTest {
    @Test
    fun valid_request_decodes_exact_binding_shape() {
        val decoded = assertIs<DeviceRebindWireDecodeResult.Decoded>(
            DeviceRebindWireJsonCodec.decodeRequest(
                """
                {
                  "wireVersion":1,
                  "rebindCode":"LDR1.example",
                  "attemptId":"attempt-1",
                  "installationId":"installation-B",
                  "deviceKeyFingerprint":"sha256:device-B"
                }
                """.trimIndent().toByteArray()
            )
        )

        assertEquals("LDR1.example", decoded.value.rebindCode)
        assertEquals("attempt-1", decoded.value.attemptId)
        assertEquals("installation-B", decoded.value.installationId)
        assertEquals("sha256:device-B", decoded.value.deviceKeyFingerprint)
    }

    @Test
    fun missing_binding_fails_closed() {
        assertIs<DeviceRebindWireDecodeResult.Rejected>(
            DeviceRebindWireJsonCodec.decodeRequest(
                """{"wireVersion":1,"rebindCode":"LDR1.example","attemptId":"a"}"""
                    .toByteArray()
            )
        )
    }
}
