package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicensingDeploymentConfigContractTest {
    @Test
    fun production_config_loads_only_from_explicit_external_source() {
        val result = loader(validValues()).load()
        val loaded = assertIs<DeploymentConfigLoadResult.Loaded>(result)

        loaded.config.use { config ->
            assertEquals(LicensingDeploymentEnvironment.PRODUCTION, config.environment)
            assertEquals("0.0.0.0", config.listenerHost)
            assertEquals(8443, config.listenerPort)
            assertTrue(config.toString().contains("postgresPassword=<redacted>"))
            assertTrue(config.toString().contains("openBaoToken=<redacted>"))
            assertTrue(config.toString().contains("requestAuthenticationSecret=<redacted>"))
        }
    }

    @Test
    fun missing_secret_rejects_without_rendering_secret_value() {
        val values = validValues().toMutableMap()
        values.remove(DeploymentConfigKey.OPENBAO_TOKEN.environmentName)

        val result = loader(values).load()
        val rejected = assertIs<DeploymentConfigLoadResult.Rejected>(result)

        assertEquals(DeploymentConfigKey.OPENBAO_TOKEN, rejected.failure.key)
        assertEquals(
            DeploymentConfigFailureReason.MISSING_REQUIRED_VALUE,
            rejected.failure.reason
        )
        assertTrue(rejected.failure.toString().contains("value=<redacted>"))
    }

    @Test
    fun production_openbao_plain_http_is_rejected() {
        val values = validValues().toMutableMap()
        values[DeploymentConfigKey.OPENBAO_ADDRESS.environmentName] =
            "http://openbao.internal:8200"

        assertIs<DeploymentConfigLoadResult.Rejected>(loader(values).load())
    }

    @Test
    fun deployment_secret_copies_and_erases_consumer_copy() {
        val source = "private-secret".toCharArray()
        val secret = DeploymentSecret.of(source)
        source.fill('X')

        var observed = ""
        secret.useChars { chars ->
            observed = chars.concatToString()
            chars.fill('Y')
        }

        assertEquals("private-secret", observed)
        assertFalse(secret.toString().contains("private-secret"))
        secret.close()
    }

    @Test
    fun structural_summary_contains_no_connection_or_credential_material() {
        val config = assertIs<DeploymentConfigLoadResult.Loaded>(
            loader(validValues()).load()
        ).config

        config.use {
            val summary = it.structuralSummary()
            assertFalse(summary.contains("jdbc:postgresql"))
            assertFalse(summary.contains("db-password"))
            assertFalse(summary.contains("openbao-token"))
            assertFalse(summary.contains("request-auth-secret"))
            assertTrue(summary.contains("serverStarted").not())
        }
    }

    private fun loader(values: Map<String, String>) =
        LicensingDeploymentConfigLoader(
            DeploymentEnvironmentSource { name -> values[name] }
        )

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
