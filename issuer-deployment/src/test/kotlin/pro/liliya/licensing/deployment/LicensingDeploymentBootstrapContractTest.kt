package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.licensing.observability.LicensingOperationalEvent
import pro.liliya.licensing.observability.LicensingOperationalEventCode
import pro.liliya.licensing.observability.LicensingOperationalEventSink
import pro.liliya.licensing.observability.LicensingOperationalReasonCode

class LicensingDeploymentBootstrapContractTest {
    @Test
    fun valid_production_config_emits_only_structural_ready_event() {
        val events = mutableListOf<LicensingOperationalEvent>()
        val bootstrap = LicensingDeploymentBootstrap(
            loader = LicensingDeploymentConfigLoader(
                DeploymentEnvironmentSource { name -> validValues()[name] }
            ),
            sink = LicensingOperationalEventSink(events::add)
        )

        assertEquals(0, bootstrap.run())
        assertEquals(1, events.size)
        assertEquals(LicensingOperationalEventCode.BOOTSTRAP_READY, events.single().code)

        val rendered = events.single().structuralLine()
        assertTrue(rendered.contains("PRODUCTION"))
        assertFalse(rendered.contains("jdbc:postgresql"))
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("token"))
        assertFalse(rendered.contains("runtime-role"))
    }

    @Test
    fun missing_secret_emits_typed_rejection_without_value_or_exception_text() {
        val values = validValues().toMutableMap()
        values.remove("LILIYA_OPENBAO_TOKEN")
        val events = mutableListOf<LicensingOperationalEvent>()

        val bootstrap = LicensingDeploymentBootstrap(
            loader = LicensingDeploymentConfigLoader(
                DeploymentEnvironmentSource { name -> values[name] }
            ),
            sink = LicensingOperationalEventSink(events::add)
        )

        assertEquals(2, bootstrap.run())
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(LicensingOperationalEventCode.BOOTSTRAP_REJECTED, event.code)
        assertEquals(
            LicensingOperationalReasonCode.MISSING_REQUIRED_CONFIGURATION,
            event.reason
        )

        val rendered = event.structuralLine()
        assertTrue(rendered.contains("OPENBAO_TOKEN"))
        assertFalse(rendered.contains("openbao-token-private"))
        assertFalse(rendered.contains("Exception"))
        assertFalse(rendered.contains("missing required"))
    }

    @Test
    fun bootstrap_rendering_redacts_collaborators() {
        val bootstrap = LicensingDeploymentBootstrap(
            loader = LicensingDeploymentConfigLoader(
                DeploymentEnvironmentSource { null }
            ),
            sink = LicensingOperationalEventSink { }
        )

        val rendered = bootstrap.toString()
        assertTrue(rendered.contains("loader=<redacted>"))
        assertTrue(rendered.contains("sink=<redacted>"))
    }

    private fun validValues(): Map<String, String> =
        mapOf(
            "LILIYA_ENVIRONMENT" to "PRODUCTION",
            "LILIYA_LISTENER_HOST" to "0.0.0.0",
            "LILIYA_LISTENER_PORT" to "8443",
            "LILIYA_POSTGRES_JDBC_URL" to "jdbc:postgresql://db.internal:5432/licensing",
            "LILIYA_POSTGRES_USERNAME" to "runtime-role",
            "LILIYA_POSTGRES_PASSWORD" to "db-password-private",
            "LILIYA_OPENBAO_ADDRESS" to "https://openbao.internal:8200",
            "LILIYA_OPENBAO_KEY_REFERENCE" to "licensing-prod",
            "LILIYA_OPENBAO_TOKEN" to "openbao-token-private",
            "LILIYA_REQUEST_AUTH_SECRET" to "request-auth-secret-private"
        )
}
