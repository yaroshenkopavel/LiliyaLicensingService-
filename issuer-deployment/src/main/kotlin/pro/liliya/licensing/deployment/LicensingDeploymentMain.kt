package pro.liliya.licensing.deployment

import kotlin.system.exitProcess
import pro.liliya.licensing.observability.ConsoleLicensingOperationalEventSink

fun main() {
    val exitCode = LicensingDeploymentBootstrap(
        loader = LicensingDeploymentConfigLoader(
            SystemDeploymentEnvironmentSource()
        ),
        sink = ConsoleLicensingOperationalEventSink()
    ).run()

    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
