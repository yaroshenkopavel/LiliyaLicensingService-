package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceRebindDeploymentConfigLoaderContractTest {
    @Test
    fun absent_rebind_config_is_disabled() {
        assertIs<DeviceRebindDeploymentConfigLoadResult.Disabled>(
            DeviceRebindDeploymentConfigLoader(
                DeploymentEnvironmentSource { null }
            ).load()
        )
    }

    @Test
    fun partial_rebind_config_fails_closed() {
        val values = mapOf(
            DeviceRebindDeploymentConfigLoader.WRITER_USERNAME to
                "liliya_device_rebind_writer"
        )

        assertIs<DeviceRebindDeploymentConfigLoadResult.Rejected>(
            DeviceRebindDeploymentConfigLoader(
                DeploymentEnvironmentSource(values::get)
            ).load()
        )
    }

    @Test
    fun complete_rebind_config_loads_exact_writer() {
        val values = mapOf(
            DeviceRebindDeploymentConfigLoader.WRITER_USERNAME to
                "liliya_device_rebind_writer",
            DeviceRebindDeploymentConfigLoader.WRITER_CREDENTIAL to
                "test-only-rebind-writer-credential"
        )

        val loaded = assertIs<DeviceRebindDeploymentConfigLoadResult.Loaded>(
            DeviceRebindDeploymentConfigLoader(
                DeploymentEnvironmentSource(values::get)
            ).load()
        )

        assertEquals(
            "liliya_device_rebind_writer",
            loaded.config.writerUsername
        )
        loaded.config.close()
    }
}
