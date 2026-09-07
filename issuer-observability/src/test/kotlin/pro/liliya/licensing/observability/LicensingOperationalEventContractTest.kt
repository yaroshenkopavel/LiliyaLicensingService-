package pro.liliya.licensing.observability

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicensingOperationalEventContractTest {
    @Test
    fun event_rendering_contains_only_fixed_structural_fields() {
        val event = LicensingOperationalEvent(
            environment = LicensingOperationalEnvironment.PRODUCTION,
            component = LicensingOperationalComponent.DEPLOYMENT,
            code = LicensingOperationalEventCode.BOOTSTRAP_REJECTED,
            reason = LicensingOperationalReasonCode.INVALID_CONFIGURATION
        )

        val rendered = event.structuralLine()

        assertTrue(rendered.contains("\"environment\":\"PRODUCTION\""))
        assertTrue(rendered.contains("\"component\":\"DEPLOYMENT\""))
        assertTrue(rendered.contains("\"code\":\"BOOTSTRAP_REJECTED\""))
        assertTrue(rendered.contains("\"reason\":\"INVALID_CONFIGURATION\""))
        assertFalse(rendered.contains("subject"))
        assertFalse(rendered.contains("requestId"))
        assertFalse(rendered.contains("signature"))
        assertFalse(rendered.contains("token"))
        assertFalse(rendered.contains("exception"))
        assertFalse(rendered.contains("path"))
        assertFalse(rendered.contains("body"))
    }

    @Test
    fun console_sink_emits_only_structural_event() {
        val buffer = ByteArrayOutputStream()
        val sink = ConsoleLicensingOperationalEventSink(PrintStream(buffer))

        sink.publish(
            LicensingOperationalEvent(
                environment = LicensingOperationalEnvironment.STAGING,
                component = LicensingOperationalComponent.RUNTIME,
                code = LicensingOperationalEventCode.RUNTIME_READY
            )
        )

        val rendered = buffer.toString(Charsets.UTF_8)
        assertTrue(rendered.contains("RUNTIME_READY"))
        assertTrue(rendered.contains("STAGING"))
        assertFalse(rendered.contains("PRIVATE"))
        assertFalse(sink.toString().contains(buffer.toString(Charsets.UTF_8)))
    }
}
