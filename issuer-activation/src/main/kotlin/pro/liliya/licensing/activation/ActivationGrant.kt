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
    data class Created(
        val code: ActivationCode
    ) : ActivationProvisioningResult

    data object Failed : ActivationProvisioningResult
}

sealed interface ActivationRedemptionResult {
    data class Accepted(
        val subject: String,
        val productId: String
    ) : ActivationRedemptionResult

    data object Invalid : ActivationRedemptionResult
    data object Expired : ActivationRedemptionResult
    data object AlreadyRedeemed : ActivationRedemptionResult
    data object Unavailable : ActivationRedemptionResult
}

interface ActivationGrantStore {
    fun create(grant: ActivationGrant): Boolean

    fun redeem(
        codeHash: ActivationCodeHash,
        now: Instant
    ): ActivationRedemptionResult
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
        if (
            subject.isBlank() ||
            productId.isBlank() ||
            !expiresAt.isAfter(now)
        ) {
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
    fun redeem(
        rawCode: String,
        now: Instant = Instant.now()
    ): ActivationRedemptionResult {
        val code = ActivationCode.parse(rawCode)
            ?: return ActivationRedemptionResult.Invalid

        return code.use {
            store.redeem(
                codeHash = ActivationCodeHasher.sha256(code),
                now = now
            )
        }
    }
}
