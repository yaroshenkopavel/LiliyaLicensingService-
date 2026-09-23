package pro.liliya.licensing.activation

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * One-time first-run activation secret.
 *
 * The printable value is intentionally never exposed by toString().
 * Callers that need to show the code to the owner must do so explicitly through useText().
 */
class ActivationCode private constructor(
    private val chars: CharArray
) : AutoCloseable {
    private var closed = false

    fun <T> useText(block: (String) -> T): T {
        check(!closed) { "activation code is closed" }
        return block(String(chars))
    }

    override fun close() {
        if (!closed) {
            chars.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "ActivationCode(<redacted>)"

    companion object {
        internal fun fromCanonical(value: String): ActivationCode =
            ActivationCode(value.toCharArray())

        fun parse(value: String): ActivationCode? {
            val compact = value
                .trim()
                .uppercase(Locale.ROOT)
                .filterNot { it == '-' || it.isWhitespace() }

            if (!compact.startsWith(PREFIX)) return null
            val payload = compact.removePrefix(PREFIX)
            if (payload.length != HEX_CHAR_COUNT) return null
            if (payload.any { it !in '0'..'9' && it !in 'A'..'F' }) return null

            return ActivationCode(format(payload).toCharArray())
        }

        private fun format(payload: String): String =
            PREFIX + "-" + payload.chunked(GROUP_SIZE).joinToString("-")

        private const val PREFIX = "LIL"
        private const val HEX_CHAR_COUNT = 32
        private const val GROUP_SIZE = 4
    }
}

class ActivationCodeHash private constructor(
    value: ByteArray
) {
    private val bytes = value.copyOf()

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ActivationCodeHash && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ActivationCodeHash(<redacted>)"

    companion object {
        fun of(value: ByteArray): ActivationCodeHash {
            require(value.size == SHA256_BYTES) { "activation code hash must be SHA-256" }
            return ActivationCodeHash(value)
        }

        private const val SHA256_BYTES = 32
    }
}

fun interface ActivationCodeGenerator {
    fun generate(): ActivationCode
}

class SecureActivationCodeGenerator(
    private val random: SecureRandom = SecureRandom()
) : ActivationCodeGenerator {
    override fun generate(): ActivationCode {
        val entropy = ByteArray(ENTROPY_BYTES)
        random.nextBytes(entropy)
        return try {
            val payload = buildString(ENTROPY_BYTES * 2) {
                entropy.forEach { byte ->
                    append(HEX[(byte.toInt() ushr 4) and 0x0f])
                    append(HEX[byte.toInt() and 0x0f])
                }
            }
            ActivationCode.parse("LIL-$payload")
                ?: error("generated activation code was not canonical")
        } finally {
            entropy.fill(0)
        }
    }

    private companion object {
        const val ENTROPY_BYTES = 16
        const val HEX = "0123456789ABCDEF"
    }
}

object ActivationCodeHasher {
    fun sha256(code: ActivationCode): ActivationCodeHash =
        code.useText { text ->
            val bytes = text.encodeToByteArray()
            try {
                ActivationCodeHash.of(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                )
            } finally {
                bytes.fill(0)
            }
        }
}
