package pro.liliya.licensing.deployment

import pro.liliya.licensing.observability.ConsoleLicensingOperationalEventSink
import pro.liliya.licensing.observability.LicensingOperationalComponent
import pro.liliya.licensing.observability.LicensingOperationalDetailCode
import pro.liliya.licensing.observability.LicensingOperationalEnvironment
import pro.liliya.licensing.observability.LicensingOperationalEvent
import pro.liliya.licensing.observability.LicensingOperationalEventCode
import pro.liliya.licensing.observability.LicensingOperationalEventSink
import pro.liliya.licensing.observability.LicensingOperationalReasonCode

class LicensingDeploymentBootstrap(
    private val loader: LicensingDeploymentConfigLoader,
    private val sink: LicensingOperationalEventSink
) {
    fun run(): Int =
        when (val result = loader.load()) {
            is DeploymentConfigLoadResult.Loaded -> {
                result.config.use { config ->
                    sink.publish(
                        LicensingOperationalEvent(
                            environment = config.environment.toOperationalEnvironment(),
                            component = LicensingOperationalComponent.DEPLOYMENT,
                            code = LicensingOperationalEventCode.BOOTSTRAP_READY
                        )
                    )
                }
                0
            }

            is DeploymentConfigLoadResult.Rejected -> {
                sink.publish(
                    LicensingOperationalEvent(
                        environment = LicensingOperationalEnvironment.UNKNOWN,
                        component = LicensingOperationalComponent.DEPLOYMENT,
                        code = LicensingOperationalEventCode.BOOTSTRAP_REJECTED,
                        reason = result.failure.reason.toOperationalReason(),
                        detail = result.failure.key.toOperationalDetail()
                    )
                )
                2
            }
        }

    override fun toString(): String =
        "LicensingDeploymentBootstrap(loader=<redacted>,sink=<redacted>)"
}

private fun LicensingDeploymentEnvironment.toOperationalEnvironment():
    LicensingOperationalEnvironment =
    when (this) {
        LicensingDeploymentEnvironment.DEVELOPMENT ->
            LicensingOperationalEnvironment.DEVELOPMENT
        LicensingDeploymentEnvironment.STAGING ->
            LicensingOperationalEnvironment.STAGING
        LicensingDeploymentEnvironment.PRODUCTION ->
            LicensingOperationalEnvironment.PRODUCTION
    }

private fun DeploymentConfigFailureReason.toOperationalReason():
    LicensingOperationalReasonCode =
    when (this) {
        DeploymentConfigFailureReason.MISSING_REQUIRED_VALUE ->
            LicensingOperationalReasonCode.MISSING_REQUIRED_CONFIGURATION
        DeploymentConfigFailureReason.INVALID_VALUE ->
            LicensingOperationalReasonCode.INVALID_CONFIGURATION
    }

private fun DeploymentConfigKey.toOperationalDetail(): LicensingOperationalDetailCode =
    when (this) {
        DeploymentConfigKey.ENVIRONMENT -> LicensingOperationalDetailCode.ENVIRONMENT
        DeploymentConfigKey.LISTENER_HOST -> LicensingOperationalDetailCode.LISTENER_HOST
        DeploymentConfigKey.LISTENER_PORT -> LicensingOperationalDetailCode.LISTENER_PORT
        DeploymentConfigKey.POSTGRES_JDBC_URL -> LicensingOperationalDetailCode.POSTGRES_JDBC_URL
        DeploymentConfigKey.POSTGRES_USERNAME -> LicensingOperationalDetailCode.POSTGRES_USERNAME
        DeploymentConfigKey.POSTGRES_PASSWORD -> LicensingOperationalDetailCode.POSTGRES_PASSWORD
        DeploymentConfigKey.OPENBAO_ADDRESS -> LicensingOperationalDetailCode.OPENBAO_ADDRESS
        DeploymentConfigKey.OPENBAO_KEY_REFERENCE -> LicensingOperationalDetailCode.OPENBAO_KEY_REFERENCE
        DeploymentConfigKey.OPENBAO_TOKEN -> LicensingOperationalDetailCode.OPENBAO_TOKEN
        DeploymentConfigKey.REQUEST_AUTH_SECRET -> LicensingOperationalDetailCode.REQUEST_AUTH_SECRET
    }
