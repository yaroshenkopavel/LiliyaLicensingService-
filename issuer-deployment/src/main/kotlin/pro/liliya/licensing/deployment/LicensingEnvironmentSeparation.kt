package pro.liliya.licensing.deployment

data class LicensingEnvironmentIdentityProfile(
    val environment: LicensingDeploymentEnvironment,
    val postgresIdentity: String,
    val openBaoKeyIdentity: String,
    val requestAuthenticationIdentity: String,
    val tlsIdentity: String
) {
    init {
        require(postgresIdentity.isNotBlank()) { "PostgreSQL identity must not be blank" }
        require(openBaoKeyIdentity.isNotBlank()) { "OpenBao key identity must not be blank" }
        require(requestAuthenticationIdentity.isNotBlank()) {
            "request-authentication identity must not be blank"
        }
        require(tlsIdentity.isNotBlank()) { "TLS identity must not be blank" }
    }

    override fun toString(): String =
        "LicensingEnvironmentIdentityProfile(environment=" + environment +
            ",postgresIdentity=<redacted>,openBaoKeyIdentity=<redacted>," +
            "requestAuthenticationIdentity=<redacted>,tlsIdentity=<redacted>)"
}

enum class EnvironmentSeparationFailure {
    MISSING_ENVIRONMENT,
    DUPLICATE_ENVIRONMENT,
    POSTGRES_IDENTITY_REUSED,
    OPENBAO_KEY_REUSED,
    REQUEST_AUTHENTICATION_IDENTITY_REUSED,
    TLS_IDENTITY_REUSED
}

sealed interface EnvironmentSeparationResult {
    data object Accepted : EnvironmentSeparationResult
    data class Rejected(val reason: EnvironmentSeparationFailure) : EnvironmentSeparationResult
}

object LicensingEnvironmentSeparationValidator {
    fun validate(
        profiles: List<LicensingEnvironmentIdentityProfile>
    ): EnvironmentSeparationResult {
        val environments = profiles.map { it.environment }

        if (environments.toSet().size != environments.size) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.DUPLICATE_ENVIRONMENT
            )
        }

        if (!environments.toSet().containsAll(LicensingDeploymentEnvironment.entries)) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.MISSING_ENVIRONMENT
            )
        }

        if (hasReuse(profiles.map { it.postgresIdentity })) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.POSTGRES_IDENTITY_REUSED
            )
        }

        if (hasReuse(profiles.map { it.openBaoKeyIdentity })) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.OPENBAO_KEY_REUSED
            )
        }

        if (hasReuse(profiles.map { it.requestAuthenticationIdentity })) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.REQUEST_AUTHENTICATION_IDENTITY_REUSED
            )
        }

        if (hasReuse(profiles.map { it.tlsIdentity })) {
            return EnvironmentSeparationResult.Rejected(
                EnvironmentSeparationFailure.TLS_IDENTITY_REUSED
            )
        }

        return EnvironmentSeparationResult.Accepted
    }

    private fun hasReuse(values: List<String>): Boolean =
        values.toSet().size != values.size
}
