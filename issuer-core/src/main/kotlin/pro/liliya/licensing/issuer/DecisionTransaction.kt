package pro.liliya.licensing.issuer

import pro.liliya.licensing.signing.SignedLicenseEnvelope

data class DecisionScope(val subject: String, val productId: String) {
    override fun toString(): String =
        "DecisionScope(subject=<redacted>,productId=" + productId + ")"
}

data class DecisionState(
    val replaySequence: Long,
    val revocationEpoch: Long
)

data class DecisionCandidate(
    val nextState: DecisionState,
    val envelope: SignedLicenseEnvelope
)

sealed interface DecisionTransactionResult {
    data class Committed(
        val state: DecisionState,
        val envelope: SignedLicenseEnvelope
    ) : DecisionTransactionResult

    data class Rejected(val reason: DecisionTransactionFailure) : DecisionTransactionResult
}

enum class DecisionTransactionFailure {
    CONFLICT,
    REJECTED,
    INTERNAL_FAILURE
}

fun interface DecisionTransactionPort {
    fun transact(
        scope: DecisionScope,
        block: (DecisionState?) -> DecisionCandidate?
    ): DecisionTransactionResult
}
