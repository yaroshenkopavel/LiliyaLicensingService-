package pro.liliya.licensing.deployment

enum class LicensingDeploymentEnvironment {
    DEVELOPMENT,
    STAGING,
    PRODUCTION
}

class DeploymentSecret private constructor(
    value: CharArray
) : AutoCloseable {
    private val chars = value.copyOf()
    private var closed = false

    init {
        require(chars.isNotEmpty()) { "secret must not be empty" }
    }

    fun useChars(block: (CharArray) -> Unit) {
        check(!closed) { "secret is closed" }
        val copy = chars.copyOf()
        try {
            block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    override fun close() {
        if (!closed) {
            chars.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "DeploymentSecret(<redacted>)"

    companion object {
        fun of(value: CharArray): DeploymentSecret = DeploymentSecret(value)
    }
}

data class LicensingDeploymentConfig(
    val environment: LicensingDeploymentEnvironment,
    val listenerHost: String,
    val listenerPort: Int,
    val postgresJdbcUrl: String,
    val openBaoAddress: String,
    val openBaoKeyReference: String,
    val postgresUsername: String,
    val postgresPassword: DeploymentSecret,
    val openBaoToken: DeploymentSecret,
    val requestAuthenticationSecret: DeploymentSecret
) : AutoCloseable {
    init {
        require(listenerHost.isNotBlank()) { "listener host must not be blank" }
        require(listenerPort in 1..65535) { "listener port must be valid" }
        require(postgresJdbcUrl.startsWith("jdbc:postgresql://")) {
            "PostgreSQL JDBC URL must use jdbc:postgresql"
        }
        require(openBaoAddress.startsWith("https://") || environment == LicensingDeploymentEnvironment.DEVELOPMENT) {
            "OpenBao address must use HTTPS outside development"
        }
        require(openBaoKeyReference.isNotBlank()) { "OpenBao key reference must not be blank" }
        require(postgresUsername.isNotBlank()) { "PostgreSQL username must not be blank" }
    }

    override fun close() {
        postgresPassword.close()
        openBaoToken.close()
        requestAuthenticationSecret.close()
    }

    override fun toString(): String =
        "LicensingDeploymentConfig(environment=" + environment +
            ",listenerHost=" + listenerHost +
            ",listenerPort=" + listenerPort +
            ",postgresJdbcUrl=<redacted>,openBaoAddress=<redacted>," +
            "openBaoKeyReference=" + openBaoKeyReference +
            ",postgresUsername=<redacted>,postgresPassword=<redacted>," +
            "openBaoToken=<redacted>,requestAuthenticationSecret=<redacted>)"

    fun structuralSummary(): String =
        "environment=" + environment +
            ",listener=" + listenerHost + ":" + listenerPort +
            ",postgresConfigured=true,openBaoConfigured=true,requestAuthConfigured=true"
}

enum class DeploymentConfigKey(val environmentName: String, val secret: Boolean) {
    ENVIRONMENT("LILIYA_ENVIRONMENT", false),
    LISTENER_HOST("LILIYA_LISTENER_HOST", false),
    LISTENER_PORT("LILIYA_LISTENER_PORT", false),
    POSTGRES_JDBC_URL("LILIYA_POSTGRES_JDBC_URL", false),
    POSTGRES_USERNAME("LILIYA_POSTGRES_USERNAME", false),
    POSTGRES_PASSWORD("LILIYA_POSTGRES_PASSWORD", true),
    OPENBAO_ADDRESS("LILIYA_OPENBAO_ADDRESS", false),
    OPENBAO_KEY_REFERENCE("LILIYA_OPENBAO_KEY_REFERENCE", false),
    OPENBAO_TOKEN("LILIYA_OPENBAO_TOKEN", true),
    REQUEST_AUTH_SECRET("LILIYA_REQUEST_AUTH_SECRET", true)
}

fun interface DeploymentEnvironmentSource {
    fun read(name: String): String?
}

class SystemDeploymentEnvironmentSource : DeploymentEnvironmentSource {
    override fun read(name: String): String? = System.getenv(name)
}

enum class DeploymentConfigFailureReason {
    MISSING_REQUIRED_VALUE,
    INVALID_VALUE
}

data class DeploymentConfigFailure(
    val key: DeploymentConfigKey,
    val reason: DeploymentConfigFailureReason
) {
    override fun toString(): String =
        "DeploymentConfigFailure(key=" + key +
            ",reason=" + reason + ",value=<redacted>)"
}

sealed interface DeploymentConfigLoadResult {
    data class Loaded(val config: LicensingDeploymentConfig) : DeploymentConfigLoadResult
    data class Rejected(val failure: DeploymentConfigFailure) : DeploymentConfigLoadResult
}

class LicensingDeploymentConfigLoader(
    private val source: DeploymentEnvironmentSource
) {
    fun load(): DeploymentConfigLoadResult {
        val values = LinkedHashMap<DeploymentConfigKey, String>()

        for (key in DeploymentConfigKey.entries) {
            val value = source.read(key.environmentName)
            if (value.isNullOrBlank()) {
                return DeploymentConfigLoadResult.Rejected(
                    DeploymentConfigFailure(
                        key = key,
                        reason = DeploymentConfigFailureReason.MISSING_REQUIRED_VALUE
                    )
                )
            }
            values[key] = value
        }

        val environment = when (values.getValue(DeploymentConfigKey.ENVIRONMENT).uppercase()) {
            "DEVELOPMENT" -> LicensingDeploymentEnvironment.DEVELOPMENT
            "STAGING" -> LicensingDeploymentEnvironment.STAGING
            "PRODUCTION" -> LicensingDeploymentEnvironment.PRODUCTION
            else -> return invalid(DeploymentConfigKey.ENVIRONMENT)
        }

        val port = values.getValue(DeploymentConfigKey.LISTENER_PORT).toIntOrNull()
            ?: return invalid(DeploymentConfigKey.LISTENER_PORT)
        if (port !in 1..65535) return invalid(DeploymentConfigKey.LISTENER_PORT)

        return try {
            DeploymentConfigLoadResult.Loaded(
                LicensingDeploymentConfig(
                    environment = environment,
                    listenerHost = values.getValue(DeploymentConfigKey.LISTENER_HOST),
                    listenerPort = port,
                    postgresJdbcUrl = values.getValue(DeploymentConfigKey.POSTGRES_JDBC_URL),
                    postgresUsername = values.getValue(DeploymentConfigKey.POSTGRES_USERNAME),
                    postgresPassword = secret(values.getValue(DeploymentConfigKey.POSTGRES_PASSWORD)),
                    openBaoAddress = values.getValue(DeploymentConfigKey.OPENBAO_ADDRESS),
                    openBaoKeyReference = values.getValue(DeploymentConfigKey.OPENBAO_KEY_REFERENCE),
                    openBaoToken = secret(values.getValue(DeploymentConfigKey.OPENBAO_TOKEN)),
                    requestAuthenticationSecret = secret(values.getValue(DeploymentConfigKey.REQUEST_AUTH_SECRET))
                )
            )
        } catch (_: IllegalArgumentException) {
            DeploymentConfigLoadResult.Rejected(
                DeploymentConfigFailure(
                    key = DeploymentConfigKey.ENVIRONMENT,
                    reason = DeploymentConfigFailureReason.INVALID_VALUE
                )
            )
        }
    }

    private fun invalid(key: DeploymentConfigKey): DeploymentConfigLoadResult.Rejected =
        DeploymentConfigLoadResult.Rejected(
            DeploymentConfigFailure(key, DeploymentConfigFailureReason.INVALID_VALUE)
        )

    private fun secret(value: String): DeploymentSecret =
        DeploymentSecret.of(value.toCharArray())
}
