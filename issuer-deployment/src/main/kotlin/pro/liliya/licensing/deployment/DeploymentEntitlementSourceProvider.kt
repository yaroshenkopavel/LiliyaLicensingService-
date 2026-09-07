package pro.liliya.licensing.deployment

import java.util.ServiceLoader
import pro.liliya.licensing.issuer.EntitlementSourcePort

/**
 * External production entitlement-source seam.
 *
 * Implementations own business eligibility lookup only. They do not own signing,
 * replay/revocation persistence, LicensePolicy, Capability Authority or Execution Authority.
 */
interface DeploymentEntitlementSourceProvider {
    fun create(): EntitlementSourcePort
}

enum class DeploymentEntitlementSourceProviderFailure {
    MISSING,
    MULTIPLE,
    CREATION_FAILED
}

sealed interface DeploymentEntitlementSourceProviderResult {
    data class Loaded(val source: EntitlementSourcePort) :
        DeploymentEntitlementSourceProviderResult

    data class Rejected(val reason: DeploymentEntitlementSourceProviderFailure) :
        DeploymentEntitlementSourceProviderResult
}

fun interface DeploymentEntitlementSourceProviderLoader {
    fun load(): DeploymentEntitlementSourceProviderResult
}

class ServiceLoaderDeploymentEntitlementSourceProviderLoader(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader
            ?: ServiceLoaderDeploymentEntitlementSourceProviderLoader::class.java.classLoader
) : DeploymentEntitlementSourceProviderLoader {
    override fun load(): DeploymentEntitlementSourceProviderResult =
        try {
            val providers = ServiceLoader.load(
                DeploymentEntitlementSourceProvider::class.java,
                classLoader
            ).iterator().asSequence().toList()

            when {
                providers.isEmpty() ->
                    DeploymentEntitlementSourceProviderResult.Rejected(
                        DeploymentEntitlementSourceProviderFailure.MISSING
                    )

                providers.size != 1 ->
                    DeploymentEntitlementSourceProviderResult.Rejected(
                        DeploymentEntitlementSourceProviderFailure.MULTIPLE
                    )

                else -> {
                    val source = runCatching { providers.single().create() }
                        .getOrNull()
                        ?: return DeploymentEntitlementSourceProviderResult.Rejected(
                            DeploymentEntitlementSourceProviderFailure.CREATION_FAILED
                        )
                    DeploymentEntitlementSourceProviderResult.Loaded(source)
                }
            }
        } catch (_: Throwable) {
            DeploymentEntitlementSourceProviderResult.Rejected(
                DeploymentEntitlementSourceProviderFailure.CREATION_FAILED
            )
        }

    override fun toString(): String =
        "ServiceLoaderDeploymentEntitlementSourceProviderLoader(classLoader=<redacted>)"
}
