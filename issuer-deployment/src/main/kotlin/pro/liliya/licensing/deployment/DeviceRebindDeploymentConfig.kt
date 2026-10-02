package pro.liliya.licensing.deployment

data class DeviceRebindDeploymentConfig(
    val writerUsername: String,
    val writerCredential: DeploymentSecret
) : AutoCloseable {
    init {
        require(writerUsername.isNotBlank())
    }

    override fun close() {
        writerCredential.close()
    }

    override fun toString(): String =
        "DeviceRebindDeploymentConfig(writerUsername=<redacted>," +
            "writerCredential=<redacted>)"
}

sealed interface DeviceRebindDeploymentConfigLoadResult {
    data object Disabled : DeviceRebindDeploymentConfigLoadResult
    data class Loaded(val config: DeviceRebindDeploymentConfig) :
        DeviceRebindDeploymentConfigLoadResult
    data class Rejected(val reason: String) :
        DeviceRebindDeploymentConfigLoadResult
}

class DeviceRebindDeploymentConfigLoader(
    private val source: DeploymentEnvironmentSource
) {
    fun load(): DeviceRebindDeploymentConfigLoadResult {
        val username = source.read(WRITER_USERNAME)
        val credential = source.read(WRITER_CREDENTIAL)

        if (username.isNullOrBlank() && credential.isNullOrBlank()) {
            return DeviceRebindDeploymentConfigLoadResult.Disabled
        }
        if (username.isNullOrBlank() || credential.isNullOrBlank()) {
            return DeviceRebindDeploymentConfigLoadResult.Rejected(
                "partial device rebind configuration"
            )
        }

        return runCatching {
            DeviceRebindDeploymentConfig(
                writerUsername = username,
                writerCredential = DeploymentSecret.of(
                    credential.toCharArray()
                )
            )
        }.fold(
            onSuccess = DeviceRebindDeploymentConfigLoadResult::Loaded,
            onFailure = {
                DeviceRebindDeploymentConfigLoadResult.Rejected(
                    "invalid device rebind configuration"
                )
            }
        )
    }

    companion object {
        const val WRITER_USERNAME = "LILIYA_DEVICE_REBIND_POSTGRES_USERNAME"
        const val WRITER_CREDENTIAL = "LILIYA_DEVICE_REBIND_POSTGRES_CREDENTIAL"
    }
}
