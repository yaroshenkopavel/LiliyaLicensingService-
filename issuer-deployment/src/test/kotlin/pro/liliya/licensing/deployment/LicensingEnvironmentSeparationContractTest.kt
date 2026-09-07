package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicensingEnvironmentSeparationContractTest {
    @Test
    fun distinct_development_staging_production_identities_are_accepted() {
        assertEquals(
            EnvironmentSeparationResult.Accepted,
            LicensingEnvironmentSeparationValidator.validate(distinctProfiles())
        )
    }

    @Test
    fun production_openbao_key_reuse_is_rejected() {
        val profiles = distinctProfiles().toMutableList()
        profiles[2] = profiles[2].copy(
            openBaoKeyIdentity = profiles[0].openBaoKeyIdentity
        )

        val result = LicensingEnvironmentSeparationValidator.validate(profiles)

        assertIs<EnvironmentSeparationResult.Rejected>(result)
        assertEquals(EnvironmentSeparationFailure.OPENBAO_KEY_REUSED, result.reason)
    }

    @Test
    fun production_database_identity_reuse_is_rejected() {
        val profiles = distinctProfiles().toMutableList()
        profiles[2] = profiles[2].copy(
            postgresIdentity = profiles[1].postgresIdentity
        )

        val result = LicensingEnvironmentSeparationValidator.validate(profiles)

        assertIs<EnvironmentSeparationResult.Rejected>(result)
        assertEquals(EnvironmentSeparationFailure.POSTGRES_IDENTITY_REUSED, result.reason)
    }

    @Test
    fun production_request_auth_identity_reuse_is_rejected() {
        val profiles = distinctProfiles().toMutableList()
        profiles[2] = profiles[2].copy(
            requestAuthenticationIdentity = profiles[0].requestAuthenticationIdentity
        )

        val result = LicensingEnvironmentSeparationValidator.validate(profiles)

        assertIs<EnvironmentSeparationResult.Rejected>(result)
        assertEquals(
            EnvironmentSeparationFailure.REQUEST_AUTHENTICATION_IDENTITY_REUSED,
            result.reason
        )
    }

    @Test
    fun production_tls_identity_reuse_is_rejected() {
        val profiles = distinctProfiles().toMutableList()
        profiles[2] = profiles[2].copy(
            tlsIdentity = profiles[0].tlsIdentity
        )

        val result = LicensingEnvironmentSeparationValidator.validate(profiles)

        assertIs<EnvironmentSeparationResult.Rejected>(result)
        assertEquals(EnvironmentSeparationFailure.TLS_IDENTITY_REUSED, result.reason)
    }

    @Test
    fun profile_rendering_never_exposes_identity_values() {
        val profile = distinctProfiles().last()
        val rendered = profile.toString()

        assertTrue(rendered.contains("environment=PRODUCTION"))
        assertTrue(rendered.contains("postgresIdentity=<redacted>"))
        assertFalse(rendered.contains(profile.postgresIdentity))
        assertFalse(rendered.contains(profile.openBaoKeyIdentity))
        assertFalse(rendered.contains(profile.requestAuthenticationIdentity))
        assertFalse(rendered.contains(profile.tlsIdentity))
    }

    private fun distinctProfiles(): List<LicensingEnvironmentIdentityProfile> =
        listOf(
            LicensingEnvironmentIdentityProfile(
                environment = LicensingDeploymentEnvironment.DEVELOPMENT,
                postgresIdentity = "dev-db-runtime",
                openBaoKeyIdentity = "dev-license-key",
                requestAuthenticationIdentity = "dev-request-auth",
                tlsIdentity = "dev-tls"
            ),
            LicensingEnvironmentIdentityProfile(
                environment = LicensingDeploymentEnvironment.STAGING,
                postgresIdentity = "staging-db-runtime",
                openBaoKeyIdentity = "staging-license-key",
                requestAuthenticationIdentity = "staging-request-auth",
                tlsIdentity = "staging-tls"
            ),
            LicensingEnvironmentIdentityProfile(
                environment = LicensingDeploymentEnvironment.PRODUCTION,
                postgresIdentity = "prod-db-runtime",
                openBaoKeyIdentity = "prod-license-key",
                requestAuthenticationIdentity = "prod-request-auth",
                tlsIdentity = "prod-tls"
            )
        )
}
