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

enum class RequestAuthenticationFailure {
    MISSING,
    INVALID,
    UNAVAILABLE
}

sealed interface RequestAuthenticationResult {
    data object Authenticated : RequestAuthenticationResult
    data class Rejected(val reason: RequestAuthenticationFailure) :
        RequestAuthenticationResult
}

/**
 * Production transport-request authentication seam.
 *
 * This boundary validates only transport-request credentials. It does not create entitlement,
 * License state, subject eligibility, enrollment authority, Capability Authority or Execution
 * permission.
 */
fun interface RequestAuthenticationPort {
    fun authenticate(
        credential: RequestAuthenticationCredential?
    ): RequestAuthenticationResult
}


data class RequestAuthenticationScope(
    val subject: String,
    val productId: String,
    val operation: String
) {
    init {
        require(subject.isNotBlank()) { "authentication subject must not be blank" }
        require(productId.isNotBlank()) { "authentication productId must not be blank" }
        require(operation.isNotBlank()) { "authentication operation must not be blank" }
    }

    override fun toString(): String =
        "RequestAuthenticationScope(subject=<redacted>,productId=$productId,operation=$operation)"
}

/**
 * Scope-aware transport authentication used by the production license endpoint.
 *
 * This allows an installation credential to be bound to the exact subject/product pair while
 * preserving the original unscoped authentication seam for internal/service-state traffic.
 */
fun interface ScopedRequestAuthenticationPort {
    fun authenticate(
        credential: RequestAuthenticationCredential?,
        scope: RequestAuthenticationScope
    ): RequestAuthenticationResult
}
