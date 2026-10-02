package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationDeploymentConfigLoaderContractTest {
    @Test
    fun completely_absent_activation_config_is_disabled() {
        val result = ActivationDeploymentConfigLoader(
            DeploymentEnvironmentSource { null }
        ).load()

        assertIs<ActivationDeploymentConfigLoadResult.Disabled>(result)
    }

    @Test
    fun partial_activation_config_fails_closed() {
        val values = mapOf(
            ActivationDeploymentConfigLoader.KEY_ID to "activation-key-v1"
        )
        val result = ActivationDeploymentConfigLoader(
            DeploymentEnvironmentSource(values::get)
        ).load()

        assertIs<ActivationDeploymentConfigLoadResult.Rejected>(result)
    }
    @Test
    fun complete_activation_config_loads_exact_key_and_policy() {
        val values = mapOf(
            ActivationDeploymentConfigLoader.KEY_ID to "activation-key-v1",
            ActivationDeploymentConfigLoader.KEY_NAME to "activation-signing",
            ActivationDeploymentConfigLoader.KEY_VERSION to "2",
            ActivationDeploymentConfigLoader.LIFETIME_SECONDS to "0",
            ActivationDeploymentConfigLoader.OFFLINE_SECONDS to "0"
        )

        val loaded = assertIs<ActivationDeploymentConfigLoadResult.Loaded>(
            ActivationDeploymentConfigLoader(
                DeploymentEnvironmentSource(values::get)
            ).load()
        )

        assertEquals("activation-key-v1", loaded.config.logicalKeyId)
        assertEquals("activation-signing", loaded.config.openBaoKeyName)
        assertEquals(2, loaded.config.openBaoKeyVersion)
        assertEquals(0L, loaded.config.entitlementLifetime?.seconds)
        assertEquals(0L, loaded.config.offlineLeaseDuration?.seconds)
    }
}
