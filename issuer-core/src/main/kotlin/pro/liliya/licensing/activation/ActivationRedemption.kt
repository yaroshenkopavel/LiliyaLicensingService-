package pro.liliya.licensing.activation

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class ActivationRedemptionRequest(
    val activationCode: String,
    val attemptId: String
) {
    init {
        require(activationCode.isNotBlank())
        require(attemptId.isNotBlank())
    }

    override fun toString(): String =
        "ActivationRedemptionRequest(activationCode=<redacted>,attemptId=<redacted>)"
}

sealed interface ActivationRedemptionResult {
    data class Activated(val subject: String, val claims: ActivationCodeClaims) :
        ActivationRedemptionResult
    data class IdempotentReplay(val subject: String, val claims: ActivationCodeClaims) :
        ActivationRedemptionResult
    data object InvalidCode : ActivationRedemptionResult
    data object ExpiredCode : ActivationRedemptionResult
    data object CodeExhausted : ActivationRedemptionResult
    data object StoreUnavailable : ActivationRedemptionResult
}
data class ActivationRedemptionRecord(
    val codeId: String,
    val attemptId: String,
    val subject: String,
    val productId: String,
    val features: Set<String>,
    val redeemedAt: Instant
)

sealed interface ActivationRedemptionStoreResult {
    data class Created(val record: ActivationRedemptionRecord) : ActivationRedemptionStoreResult
    data class Replay(val record: ActivationRedemptionRecord) : ActivationRedemptionStoreResult
    data object Exhausted : ActivationRedemptionStoreResult
    data object Failed : ActivationRedemptionStoreResult
}

fun interface ActivationRedemptionStore {
    fun redeem(
        claims: ActivationCodeClaims,
        attemptId: String,
        proposedSubject: String,
        now: Instant
    ): ActivationRedemptionStoreResult
}

fun interface ActivationSubjectGenerator {
    fun generate(): String
}
class SecureRandomActivationSubjectGenerator(
    private val random: SecureRandom = SecureRandom()
) : ActivationSubjectGenerator {
    override fun generate(): String {
        val bytes = ByteArray(SUBJECT_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)
        return "$SUBJECT_PREFIX$token"
    }

    private companion object {
        const val SUBJECT_BYTES = 16
        const val SUBJECT_PREFIX = "liliya-subject-v1:"
    }
}

class ActivationRedemptionService(
    private val publicKeys: ActivationCodePublicKeyResolver,
    private val store: ActivationRedemptionStore,
    private val subjectGenerator: ActivationSubjectGenerator
) {
    fun redeem(
        request: ActivationRedemptionRequest,
        now: Instant
    ): ActivationRedemptionResult {
        val verified = ActivationCodeVerifier.verify(
            encoded = request.activationCode,
            keys = publicKeys,
            now = now
        )
        val claims = when (verified) {
            is ActivationCodeVerificationResult.Verified -> verified.claims
            ActivationCodeVerificationResult.Expired -> return ActivationRedemptionResult.ExpiredCode
            ActivationCodeVerificationResult.InvalidSignature,
            ActivationCodeVerificationResult.UnknownKey,
            ActivationCodeVerificationResult.Malformed ->
                return ActivationRedemptionResult.InvalidCode
        }

        if (claims.maxRedemptions != 1) {
            return ActivationRedemptionResult.InvalidCode
        }

        val proposedSubject = subjectGenerator.generate()
        return when (
            val result = store.redeem(
                claims = claims,
                attemptId = request.attemptId,
                proposedSubject = proposedSubject,
                now = now
            )
        ) {
            is ActivationRedemptionStoreResult.Created ->
                ActivationRedemptionResult.Activated(
                    subject = result.record.subject,
                    claims = claims
                )
            is ActivationRedemptionStoreResult.Replay ->
                ActivationRedemptionResult.IdempotentReplay(
                    subject = result.record.subject,
                    claims = claims
                )
            ActivationRedemptionStoreResult.Exhausted ->
                ActivationRedemptionResult.CodeExhausted
            ActivationRedemptionStoreResult.Failed ->
                ActivationRedemptionResult.StoreUnavailable
        }
    }
}
