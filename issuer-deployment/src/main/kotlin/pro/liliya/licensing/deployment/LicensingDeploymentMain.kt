package pro.liliya.licensing.deployment

import kotlin.system.exitProcess

fun main() {
    when (
        val result = LicensingDeploymentConfigLoader(
            SystemDeploymentEnvironmentSource()
        ).load()
    ) {
        is DeploymentConfigLoadResult.Loaded -> {
            result.config.use { config ->
                println(
                    "LICENSING_DEPLOYMENT_BOOTSTRAP_READY={" +
                        config.structuralSummary() +
                        ",serverStarted=false}"
                )
            }
        }

        is DeploymentConfigLoadResult.Rejected -> {
            System.err.println(
                "LICENSING_DEPLOYMENT_BOOTSTRAP_REJECTED={" +
                    "key=" + result.failure.key +
                    ",reason=" + result.failure.reason +
                    ",value=<redacted>}"
            )
            exitProcess(2)
        }
    }
}
