package pro.liliya.licensing.deployment

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import pro.liliya.licensing.observability.ConsoleLicensingOperationalEventSink
import pro.liliya.licensing.observability.LicensingOperationalComponent
import pro.liliya.licensing.observability.LicensingOperationalDetailCode
import pro.liliya.licensing.observability.LicensingOperationalEnvironment
import pro.liliya.licensing.observability.LicensingOperationalEvent
import pro.liliya.licensing.observability.LicensingOperationalEventCode
import pro.liliya.licensing.observability.LicensingOperationalReasonCode
import pro.liliya.licensing.runtime.LicensingRuntimeFailure
import pro.liliya.licensing.runtime.LicensingRuntimeStartResult

fun main() {
    val source = SystemDeploymentEnvironmentSource()
    val sink = ConsoleLicensingOperationalEventSink()

    val config = when (
        val result = LicensingDeploymentConfigLoader(source).load()
    ) {
        is DeploymentConfigLoadResult.Loaded -> result.config
        is DeploymentConfigLoadResult.Rejected -> {
            sink.publish(
                LicensingOperationalEvent(
                    environment = LicensingOperationalEnvironment.UNKNOWN,
                    component = LicensingOperationalComponent.DEPLOYMENT,
                    code = LicensingOperationalEventCode.BOOTSTRAP_REJECTED,
                    reason = when (result.failure.reason) {
                        DeploymentConfigFailureReason.MISSING_REQUIRED_VALUE ->
                            LicensingOperationalReasonCode.MISSING_REQUIRED_CONFIGURATION
                        DeploymentConfigFailureReason.INVALID_VALUE ->
                            LicensingOperationalReasonCode.INVALID_CONFIGURATION
                    },
                    detail = result.failure.key.toOperationalDetail()
                )
            )
            exitProcess(2)
        }
    }

    val material = when (
        val result = ProductionRuntimeMaterialLoader(source).load()
    ) {
        is ProductionRuntimeMaterialLoadResult.Loaded -> result.material
        is ProductionRuntimeMaterialLoadResult.Rejected -> {
            sink.publish(
                LicensingOperationalEvent(
                    environment = config.environment.toOperationalEnvironment(),
                    component = LicensingOperationalComponent.DEPLOYMENT,
                    code = LicensingOperationalEventCode.BOOTSTRAP_REJECTED,
                    reason = when (result.failure.reason) {
                        ProductionRuntimeMaterialFailureReason.MISSING_REQUIRED_VALUE ->
                            LicensingOperationalReasonCode.MISSING_REQUIRED_CONFIGURATION
                        ProductionRuntimeMaterialFailureReason.INVALID_VALUE ->
                            LicensingOperationalReasonCode.INVALID_CONFIGURATION
                    },
                    detail = result.failure.key.toOperationalDetail()
                )
            )
            config.close()
            exitProcess(2)
        }
    }

    val entitlementSource = when (
        val result = ServiceLoaderDeploymentEntitlementSourceProviderLoader().load()
    ) {
        is DeploymentEntitlementSourceProviderResult.Loaded -> result.source
        is DeploymentEntitlementSourceProviderResult.Rejected -> {
            sink.publish(
                LicensingOperationalEvent(
                    environment = config.environment.toOperationalEnvironment(),
                    component = LicensingOperationalComponent.ENTITLEMENT_SOURCE,
                    code = LicensingOperationalEventCode.DEPENDENCY_UNAVAILABLE,
                    reason = LicensingOperationalReasonCode.ENTITLEMENT_SOURCE_UNAVAILABLE
                )
            )
            material.close()
            config.close()
            exitProcess(2)
        }
    }

    val service = try {
        LicensingProductionService.create(
            deploymentConfig = config,
            runtimeMaterial = material,
            entitlementSource = entitlementSource
        )
    } catch (_: Exception) {
        sink.publish(
            LicensingOperationalEvent(
                environment = config.environment.toOperationalEnvironment(),
                component = LicensingOperationalComponent.DEPLOYMENT,
                code = LicensingOperationalEventCode.BOOTSTRAP_REJECTED,
                reason = LicensingOperationalReasonCode.INTERNAL_FAILURE
            )
        )
        material.close()
        config.close()
        exitProcess(2)
    }

    val environment = config.environment.toOperationalEnvironment()
    sink.publish(
        LicensingOperationalEvent(
            environment = environment,
            component = LicensingOperationalComponent.RUNTIME,
            code = LicensingOperationalEventCode.RUNTIME_STARTING
        )
    )

    when (val started = service.start()) {
        LicensingRuntimeStartResult.Ready -> {
            sink.publish(
                LicensingOperationalEvent(
                    environment = environment,
                    component = LicensingOperationalComponent.RUNTIME,
                    code = LicensingOperationalEventCode.RUNTIME_READY
                )
            )
        }

        is LicensingRuntimeStartResult.Failed -> {
            sink.publish(
                LicensingOperationalEvent(
                    environment = environment,
                    component = LicensingOperationalComponent.RUNTIME,
                    code = LicensingOperationalEventCode.RUNTIME_NOT_READY,
                    reason = started.reason.toOperationalReason()
                )
            )
            service.close()
            exitProcess(3)
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            sink.publish(
                LicensingOperationalEvent(
                    environment = environment,
                    component = LicensingOperationalComponent.RUNTIME,
                    code = LicensingOperationalEventCode.RUNTIME_STOPPING
                )
            )
            service.close()
            sink.publish(
                LicensingOperationalEvent(
                    environment = environment,
                    component = LicensingOperationalComponent.RUNTIME,
                    code = LicensingOperationalEventCode.RUNTIME_STOPPED
                )
            )
        }
    )

    try {
        CountDownLatch(1).await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        service.close()
    }
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
        DeploymentConfigKey.REQUEST_AUTH_IDENTITY_REFERENCE ->
            LicensingOperationalDetailCode.REQUEST_AUTH_IDENTITY_REFERENCE
        DeploymentConfigKey.REQUEST_AUTH_SECRET ->
            LicensingOperationalDetailCode.REQUEST_AUTH_SECRET
        DeploymentConfigKey.TLS_IDENTITY_REFERENCE ->
            LicensingOperationalDetailCode.TLS_IDENTITY_REFERENCE
    }

private fun ProductionRuntimeMaterialKey.toOperationalDetail():
    LicensingOperationalDetailCode =
    when (this) {
        ProductionRuntimeMaterialKey.TLS_KEYSTORE_PATH ->
            LicensingOperationalDetailCode.TLS_KEYSTORE_PATH
        ProductionRuntimeMaterialKey.TLS_KEYSTORE_PASSWORD ->
            LicensingOperationalDetailCode.TLS_KEYSTORE_PASSWORD
        ProductionRuntimeMaterialKey.OPENBAO_KEY_NAME ->
            LicensingOperationalDetailCode.OPENBAO_KEY_NAME
        ProductionRuntimeMaterialKey.OPENBAO_KEY_VERSION ->
            LicensingOperationalDetailCode.OPENBAO_KEY_VERSION
        ProductionRuntimeMaterialKey.SERVICE_STATE_OPENBAO_KEY_REFERENCE ->
            LicensingOperationalDetailCode.SERVICE_STATE_OPENBAO_KEY_REFERENCE
        ProductionRuntimeMaterialKey.SERVICE_STATE_OPENBAO_KEY_NAME ->
            LicensingOperationalDetailCode.SERVICE_STATE_OPENBAO_KEY_NAME
        ProductionRuntimeMaterialKey.SERVICE_STATE_OPENBAO_KEY_VERSION ->
            LicensingOperationalDetailCode.SERVICE_STATE_OPENBAO_KEY_VERSION
    }

private fun LicensingRuntimeFailure.toOperationalReason():
    LicensingOperationalReasonCode =
    when (this) {
        LicensingRuntimeFailure.POSTGRESQL_UNAVAILABLE ->
            LicensingOperationalReasonCode.POSTGRESQL_UNAVAILABLE
        LicensingRuntimeFailure.OPENBAO_UNAVAILABLE ->
            LicensingOperationalReasonCode.OPENBAO_UNAVAILABLE
        LicensingRuntimeFailure.REQUEST_AUTHENTICATION_UNAVAILABLE ->
            LicensingOperationalReasonCode.REQUEST_AUTHENTICATION_UNAVAILABLE
        LicensingRuntimeFailure.LISTENER_UNAVAILABLE ->
            LicensingOperationalReasonCode.LISTENER_UNAVAILABLE
        LicensingRuntimeFailure.INVALID_STATE,
        LicensingRuntimeFailure.SHUTDOWN_FAILURE ->
            LicensingOperationalReasonCode.INTERNAL_FAILURE
    }
