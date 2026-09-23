package pro.liliya.licensing.activation

import java.time.Instant

data class ActivationGrant(
    val subject: String,
    val productId: String,
    val codeHash: ActivationCodeHash,
    val createdAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(subject.isNotBlank()) { "activation subject must not be blank" }
        require(productId.isNotBlank()) { "activation productId must not be blank" }
        require(expiresAt.isAfter(createdAt)) { "activation expiry must be after creation" }
    }

    override fun toString(): String =
        "ActivationGrant(subject=<redacted>,productId=$productId,codeHash=<redacted>," +
            "createdAt=$createdAt,expiresAt=$expiresAt)"
}

sealed interface ActivationProvisioningResult {
    data class Created(val code: ActivationCode) : ActivationProvisioningResult
    data object Failed : ActivationProvisioningResult
}

class ActivationPreparedGrant(
    val subject: String,
    val productId: String,
    internal val codeHash: ActivationCodeHash,
    internal val requestId: String,
    internal val installCredential: InstallCredentialBinding
) {
    override fun toString(): String =
        "ActivationPreparedGrant(subject=<redacted>,productId=$productId," +
            "codeHash=<redacted>,requestId=<redacted>,installCredential=<redacted>)"
}

sealed interface ActivationPreparationResult {
    data class Accepted(val grant: ActivationPreparedGrant) : ActivationPreparationResult
    data class Completed(val responseBody: ByteArray) : ActivationPreparationResult {
        override fun toString(): String = "Completed(responseBody=<redacted>)"
    }
    data object Invalid : ActivationPreparationResult
    data object Expired : ActivationPreparationResult
    data object AlreadyRedeemed : ActivationPreparationResult
    data object InProgress : ActivationPreparationResult
    data object Unavailable : ActivationPreparationResult
}

interface ActivationGrantStore {
    fun create(grant: ActivationGrant): Boolean

    fun prepare(
        codeHash: ActivationCodeHash,
        requestId: String,
        installCredential: InstallCredentialBinding,
        now: Instant
    ): ActivationPreparationResult

    fun complete(
        prepared: ActivationPreparedGrant,
        responseBody: ByteArray,
        now: Instant
    ): Boolean
}

/**
 * Owner-side activation-code creation.
 *
 * This creates only a one-time redemption credential. It does not create entitlement and it does
 * not sign or issue a license. The referenced entitlement must already exist in PostgreSQL.
 */
class ActivationProvisioningService(
    private val store: ActivationGrantStore,
    private val generator: ActivationCodeGenerator = SecureActivationCodeGenerator()
) {
    fun create(
        subject: String,
        productId: String,
        expiresAt: Instant,
        now: Instant = Instant.now()
    ): ActivationProvisioningResult {
        if (subject.isBlank() || productId.isBlank() || !expiresAt.isAfter(now)) {
            return ActivationProvisioningResult.Failed
        }

        val code = generator.generate()
        val stored = store.create(
            ActivationGrant(
                subject = subject,
                productId = productId,
                codeHash = ActivationCodeHasher.sha256(code),
                createdAt = now,
                expiresAt = expiresAt
            )
        )

        if (!stored) {
            code.close()
            return ActivationProvisioningResult.Failed
        }
        return ActivationProvisioningResult.Created(code)
    }
}

class ActivationRedemptionService(
    private val store: ActivationGrantStore
) {
    fun prepare(
        rawCode: String,
        requestId: String,
        installId: String,
        installSecret: String,
        now: Instant = Instant.now()
    ): ActivationPreparationResult {
        if (requestId.isBlank() || requestId.length > 128) {
            return ActivationPreparationResult.Invalid
        }

        val binding = try {
            InstallCredentialBinding(
                installId = installId,
                secretHash = InstallCredentialHasher.sha256(installSecret)
            )
        } catch (_: IllegalArgumentException) {
            return ActivationPreparationResult.Invalid
        }

        val code = ActivationCode.parse(rawCode)
            ?: return ActivationPreparationResult.Invalid

        return code.use {
            store.prepare(
                codeHash = ActivationCodeHasher.sha256(code),
                requestId = requestId,
                installCredential = binding,
                now = now
            )
        }
    }

    fun complete(
        prepared: ActivationPreparedGrant,
        responseBody: ByteArray,
        now: Instant = Instant.now()
    ): Boolean =
        responseBody.isNotEmpty() && store.complete(prepared, responseBody, now)
}
