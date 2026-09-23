package pro.liliya.licensing.activation

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

data class ActivationGrant(
    val subject: String,
    val productId: String
) {
    init {
        require(subject.isNotBlank()) { "activation subject must not be blank" }
        require(productId.isNotBlank()) { "activation product id must not be blank" }
    }

    override fun toString(): String =
        "ActivationGrant(subject=<redacted>,productId=$productId)"
}

class ActivationClientCredential private constructor(
    value: ByteArray
) : AutoCloseable {
    private val bytes = value.copyOf()
    private var closed = false

    init {
        require(bytes.isNotEmpty()) { "activation client credential must not be empty" }
    }

    fun copyBytes(): ByteArray {
        check(!closed) { "activation client credential is closed" }
        return bytes.copyOf()
    }

    override fun close() {
        if (!closed) {
            bytes.fill(0)
            closed = true
        }
    }

    override fun toString(): String = "ActivationClientCredential(<redacted>)"

    companion object {
        fun of(value: ByteArray): ActivationClientCredential =
            ActivationClientCredential(value)
    }
}

data class ActivationCredentialIdentity(
    val subject: String,
    val productId: String
) {
    init {
        require(subject.isNotBlank()) { "credential subject must not be blank" }
        require(productId.isNotBlank()) { "credential product id must not be blank" }
    }

    override fun toString(): String =
        "ActivationCredentialIdentity(subject=<redacted>,productId=$productId)"
}

sealed interface ActivationStoreConsumeResult {
    data class Consumed(val grant: ActivationGrant) : ActivationStoreConsumeResult
    data object Rejected : ActivationStoreConsumeResult
    data object Failed : ActivationStoreConsumeResult
}

sealed interface ActivationCredentialLookupResult {
    data class Active(val identity: ActivationCredentialIdentity) :
        ActivationCredentialLookupResult

    data object Rejected : ActivationCredentialLookupResult
    data object Failed : ActivationCredentialLookupResult
}

interface ActivationStorePort {
    fun consume(
        activationCodeDigest: ByteArray,
        clientCredentialDigest: ByteArray,
        now: Instant
    ): ActivationStoreConsumeResult

    fun lookupCredential(
        clientCredentialDigest: ByteArray
    ): ActivationCredentialLookupResult

    fun revokeCredential(
        clientCredentialDigest: ByteArray,
        now: Instant
    ): Boolean
}

sealed interface ActivationResult {
    data class Activated(
        val grant: ActivationGrant,
        val credential: ActivationClientCredential
    ) : ActivationResult

    data object Rejected : ActivationResult
    data object Unavailable : ActivationResult
}

internal object ActivationDigest {
    fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

fun interface ActivationRandomBytes {
    fun next(size: Int): ByteArray
}

class ActivationService(
    private val store: ActivationStorePort,
    private val clock: Clock = Clock.systemUTC(),
    private val randomBytes: ActivationRandomBytes = secureRandomBytes()
) {
    fun activate(code: ByteArray): ActivationResult {
        if (code.isEmpty() || code.size > MAX_CODE_BYTES) {
            return ActivationResult.Rejected
        }

        val codeDigest = ActivationDigest.sha256(code)
        val credentialBytes = encodeUrlSafe(randomBytes.next(CREDENTIAL_RANDOM_BYTES))
        val credentialDigest = ActivationDigest.sha256(credentialBytes)

        return try {
            when (
                val consumed = store.consume(
                    activationCodeDigest = codeDigest,
                    clientCredentialDigest = credentialDigest,
                    now = clock.instant()
                )
            ) {
                is ActivationStoreConsumeResult.Consumed ->
                    ActivationResult.Activated(
                        grant = consumed.grant,
                        credential = ActivationClientCredential.of(credentialBytes)
                    )

                ActivationStoreConsumeResult.Rejected ->
                    ActivationResult.Rejected

                ActivationStoreConsumeResult.Failed ->
                    ActivationResult.Unavailable
            }
        } finally {
            codeDigest.fill(0)
            credentialDigest.fill(0)
            credentialBytes.fill(0)
        }
    }

    companion object {
        const val MAX_CODE_BYTES = 128
        private const val CREDENTIAL_RANDOM_BYTES = 32

        fun generateActivationCode(
            randomBytes: ActivationRandomBytes = secureRandomBytes()
        ): ByteArray =
            encodeUrlSafe(randomBytes.next(ACTIVATION_CODE_RANDOM_BYTES))

        private const val ACTIVATION_CODE_RANDOM_BYTES = 24

        private fun secureRandomBytes(): ActivationRandomBytes {
            val random = SecureRandom()
            return ActivationRandomBytes { size ->
                ByteArray(size).also(random::nextBytes)
            }
        }

        private fun encodeUrlSafe(value: ByteArray): ByteArray =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encode(value)
    }
}
