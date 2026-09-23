package pro.liliya.licensing.activation

import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.auth.RequestAuthenticationScope

/**
 * Authenticates a per-installation bearer credential by digest lookup.
 *
 * The raw bearer value is never persisted. A successful lookup returns a scoped identity so the
 * HTTP endpoint can reject attempts to use one installation credential for another subject or
 * product.
 */
class ActivationRequestAuthentication(
    private val store: ActivationStorePort
) : RequestAuthenticationPort {
    override fun authenticate(
        credential: RequestAuthenticationCredential?
    ): RequestAuthenticationResult {
        if (credential == null) {
            return RequestAuthenticationResult.Rejected(
                RequestAuthenticationFailure.MISSING
            )
        }

        val bytes = try {
            credential.copyBytes()
        } catch (_: Exception) {
            return RequestAuthenticationResult.Rejected(
                RequestAuthenticationFailure.INVALID
            )
        }

        val digest = try {
            ActivationDigest.sha256(bytes)
        } finally {
            bytes.fill(0)
        }

        return try {
            when (val result = store.lookupCredential(digest)) {
                is ActivationCredentialLookupResult.Active ->
                    RequestAuthenticationResult.AuthenticatedScoped(
                        RequestAuthenticationScope(
                            subject = result.identity.subject,
                            productId = result.identity.productId
                        )
                    )

                ActivationCredentialLookupResult.Rejected ->
                    RequestAuthenticationResult.Rejected(
                        RequestAuthenticationFailure.INVALID
                    )

                ActivationCredentialLookupResult.Failed ->
                    RequestAuthenticationResult.Rejected(
                        RequestAuthenticationFailure.UNAVAILABLE
                    )
            }
        } finally {
            digest.fill(0)
        }
    }

    override fun toString(): String =
        "ActivationRequestAuthentication(store=<redacted>)"
}
