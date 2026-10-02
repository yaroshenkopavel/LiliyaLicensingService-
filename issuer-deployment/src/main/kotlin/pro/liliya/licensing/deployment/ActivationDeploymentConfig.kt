package pro.liliya.licensing.deployment

import java.time.Duration

data class ActivationDeploymentConfig(
    val logicalKeyId: String,
    val openBaoKeyName: String,
    val openBaoKeyVersion: Int,
    val entitlementLifetime: Duration?,
    val offlineLeaseDuration: Duration?
) {
    init {
        require(logicalKeyId.isNotBlank())
        require(openBaoKeyName.isNotBlank())
        require(openBaoKeyVersion > 0)
        require(entitlementLifetime == null || !entitlementLifetime.isNegative)
        require(offlineLeaseDuration == null || !offlineLeaseDuration.isNegative)
    }

    override fun toString(): String =
        "ActivationDeploymentConfig(logicalKeyId=" + logicalKeyId +
            ",openBaoKeyName=<redacted>,openBaoKeyVersion=" + openBaoKeyVersion +
            ",entitlementLifetime=" + entitlementLifetime +
            ",offlineLeaseDuration=" + offlineLeaseDuration + ")"
}

sealed interface ActivationDeploymentConfigLoadResult {
    data object Disabled : ActivationDeploymentConfigLoadResult
    data class Loaded(val config: ActivationDeploymentConfig) :
        ActivationDeploymentConfigLoadResult
    data class Rejected(val reason: String) : ActivationDeploymentConfigLoadResult
}
class ActivationDeploymentConfigLoader(
    private val source: DeploymentEnvironmentSource
) {
    fun load(): ActivationDeploymentConfigLoadResult {
        val raw = NAMES.associateWith(source::read)
        if (raw.values.all { it.isNullOrBlank() }) {
            return ActivationDeploymentConfigLoadResult.Disabled
        }
        if (raw.values.any { it.isNullOrBlank() }) {
            return ActivationDeploymentConfigLoadResult.Rejected(
                "partial activation configuration"
            )
        }

        val version = raw.getValue(KEY_VERSION)!!.toIntOrNull()
            ?: return rejected()
        if (version <= 0) return rejected()

        val lifetime = parseDuration(raw.getValue(LIFETIME_SECONDS)!!)
            ?: return rejected()
        val offline = parseDuration(raw.getValue(OFFLINE_SECONDS)!!)
            ?: return rejected()

        return runCatching {
            ActivationDeploymentConfig(
                logicalKeyId = raw.getValue(KEY_ID)!!,
                openBaoKeyName = raw.getValue(KEY_NAME)!!,
                openBaoKeyVersion = version,
                entitlementLifetime = lifetime,
                offlineLeaseDuration = offline
            )
        }.fold(
            onSuccess = ActivationDeploymentConfigLoadResult::Loaded,
            onFailure = { rejected() }
        )
    }
    private fun parseDuration(raw: String): Duration? {
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds < 0) return null
        return if (seconds == 0L) Duration.ZERO else Duration.ofSeconds(seconds)
    }

    private fun rejected() =
        ActivationDeploymentConfigLoadResult.Rejected(
            "invalid activation configuration"
        )

    companion object {
        const val KEY_ID = "LILIYA_ACTIVATION_KEY_ID"
        const val KEY_NAME = "LILIYA_ACTIVATION_OPENBAO_KEY_NAME"
        const val KEY_VERSION = "LILIYA_ACTIVATION_OPENBAO_KEY_VERSION"
        const val LIFETIME_SECONDS = "LILIYA_ACTIVATION_ENTITLEMENT_LIFETIME_SECONDS"
        const val OFFLINE_SECONDS = "LILIYA_ACTIVATION_OFFLINE_LEASE_SECONDS"
        val NAMES = listOf(
            KEY_ID,
            KEY_NAME,
            KEY_VERSION,
            LIFETIME_SECONDS,
            OFFLINE_SECONDS
        )
    }
}
