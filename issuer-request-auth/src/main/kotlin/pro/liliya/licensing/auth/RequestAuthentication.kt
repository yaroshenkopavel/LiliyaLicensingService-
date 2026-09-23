package pro.liliya.licensing.auth

class RequestAuthenticationCredential private constructor(
    value: ByteArray
) {
    private val bytes = value.copyOf()

    init {
        require(bytes.isNotEmpty()) { "authentication credential must not be empty" }
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String =
        "RequestAuthenticationCredential(bytes=<redacted>)"

    companion object {
        fun of(value: ByteArray): RequestAuthenticationCredential =
            RequestAuthenticationCredential(value)
    }
}

data class RequestAuthenticationScope(
    val subject: String,
    val productId: String
) {
    init {
        require(subject.isNotBlank()) { "authentication subject must not be blank" }
        require(productId.isNotBlank()) { "authentication product id must not be blank" }
    }

    override fun toString(): String =
        "RequestAuthenticationScope(subject=<redacted>,productId=$productId)"
}

enum class RequestAuthenticationFailure {
    MISSING,
    INVALID,
    UNAVAILABLE
}

sealed interface RequestAuthenticationResult {
    data object Authenticated : RequestAuthenticationResult

    data class AuthenticatedScoped(
        val scope: RequestAuthenticationScope
    ) : RequestAuthenticationResult

    data class Rejected(val reason: RequestAuthenticationFailure) :
        RequestAuthenticationResult
}

/**
 * Production transport-request authentication seam.
 *
 * Unscoped authentication is retained for the existing server-owned bootstrap credential.
 * Per-installation credentials use AuthenticatedScoped and must be matched by the HTTP boundary
 * against the exact subject/product in the decoded request before issuer processing.
 *
 * Authentication does not create entitlement, License state, Capability Authority or Execution
 * permission.
 */
fun interface RequestAuthenticationPort {
    fun authenticate(
        credential: RequestAuthenticationCredential?
    ): RequestAuthenticationResult
}
