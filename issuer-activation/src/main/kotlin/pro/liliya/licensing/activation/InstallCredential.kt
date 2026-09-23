package pro.liliya.licensing.activation

import java.security.MessageDigest
import java.time.Instant

class InstallCredentialHash private constructor(
    value: ByteArray
) {
    private val bytes = value.copyOf()

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is InstallCredentialHash && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "InstallCredentialHash(<redacted>)"

    companion object {
        fun of(value: ByteArray): InstallCredentialHash {
            require(value.size == 32) { "install credential hash must be SHA-256" }
            return InstallCredentialHash(value)
        }
    }
}

data class InstallCredentialBinding(
    val installId: String,
    val secretHash: InstallCredentialHash
) {
    init {
        require(installId.length in 8..128) { "installId length out of range" }
        require(installId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "installId contains unsupported characters"
        }
    }

    override fun toString(): String =
        "InstallCredentialBinding(installId=<redacted>,secretHash=<redacted>)"
}

object InstallCredentialHasher {
    fun sha256(secret: String): InstallCredentialHash {
        require(secret.length in 32..256) { "install secret length out of range" }
        val bytes = secret.encodeToByteArray()
        return try {
            InstallCredentialHash.of(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun sha256(secret: ByteArray): InstallCredentialHash {
        require(secret.size in 32..256) { "install secret length out of range" }
        val copy = secret.copyOf()
        return try {
            InstallCredentialHash.of(
                MessageDigest.getInstance("SHA-256").digest(copy)
            )
        } finally {
            copy.fill(0)
        }
    }
}

enum class InstallCredentialVerificationResult {
    VALID,
    INVALID,
    UNAVAILABLE
}

interface InstallCredentialVerifier {
    fun verify(
        subject: String,
        productId: String,
        secret: ByteArray,
        now: Instant = Instant.now()
    ): InstallCredentialVerificationResult
}
