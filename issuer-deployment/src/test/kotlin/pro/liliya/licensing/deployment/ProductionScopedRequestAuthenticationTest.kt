package pro.liliya.licensing.deployment

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import pro.liliya.licensing.activation.InstallCredentialVerificationResult
import pro.liliya.licensing.activation.InstallCredentialVerifier
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.auth.RequestAuthenticationScope

class ProductionScopedRequestAuthenticationTest {
    @Test
    fun global_secret_remains_valid_for_internal_compatibility() {
        val global = SharedSecretRequestAuthentication("global-secret".encodeToByteArray())
        val auth = ProductionScopedRequestAuthentication(
            global = global,
            installs = verifier { _, _, _ -> InstallCredentialVerificationResult.INVALID }
        )

        val result = auth.authenticate(
            RequestAuthenticationCredential.of("global-secret".encodeToByteArray()),
            RequestAuthenticationScope("subject-a", "liliya-pro")
        )

        assertEquals(RequestAuthenticationResult.Authenticated, result)
        global.close()
    }

    @Test
    fun install_secret_is_bound_to_exact_subject_and_product() {
        val secret = "0123456789abcdef0123456789abcdef".encodeToByteArray()
        val global = SharedSecretRequestAuthentication("global-secret".encodeToByteArray())
        val auth = ProductionScopedRequestAuthentication(
            global = global,
            installs = verifier { subject, productId, candidate ->
                if (
                    subject == "subject-a" &&
                    productId == "liliya-pro" &&
                    candidate.contentEquals(secret)
                ) {
                    InstallCredentialVerificationResult.VALID
                } else {
                    InstallCredentialVerificationResult.INVALID
                }
            }
        )

        assertEquals(
            RequestAuthenticationResult.Authenticated,
            auth.authenticate(
                RequestAuthenticationCredential.of(secret),
                RequestAuthenticationScope("subject-a", "liliya-pro")
            )
        )

        val wrongScope = auth.authenticate(
            RequestAuthenticationCredential.of(secret),
            RequestAuthenticationScope("subject-b", "liliya-pro")
        )
        assertEquals(
            RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.INVALID),
            wrongScope
        )
        global.close()
        secret.fill(0)
    }

    @Test
    fun install_store_unavailable_fails_closed() {
        val global = SharedSecretRequestAuthentication("global-secret".encodeToByteArray())
        val auth = ProductionScopedRequestAuthentication(
            global = global,
            installs = verifier { _, _, _ -> InstallCredentialVerificationResult.UNAVAILABLE }
        )

        val result = auth.authenticate(
            RequestAuthenticationCredential.of(
                "0123456789abcdef0123456789abcdef".encodeToByteArray()
            ),
            RequestAuthenticationScope("subject-a", "liliya-pro")
        )

        assertEquals(
            RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.UNAVAILABLE),
            result
        )
        global.close()
    }

    private fun verifier(
        block: (String, String, ByteArray) -> InstallCredentialVerificationResult
    ): InstallCredentialVerifier =
        object : InstallCredentialVerifier {
            override fun verify(
                subject: String,
                productId: String,
                secret: ByteArray,
                now: Instant
            ): InstallCredentialVerificationResult =
                block(subject, productId, secret)
        }
}
