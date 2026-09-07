package pro.liliya.licensing.testkit

import java.util.concurrent.ConcurrentHashMap
import pro.liliya.licensing.issuer.DecisionCandidate
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.DecisionTransactionFailure
import pro.liliya.licensing.issuer.DecisionTransactionPort
import pro.liliya.licensing.issuer.DecisionTransactionResult

class InMemoryDecisionTransactionPort : DecisionTransactionPort {
    private val locks = ConcurrentHashMap<DecisionScope, Any>()
    private val states = ConcurrentHashMap<DecisionScope, DecisionState>()

    override fun transact(
        scope: DecisionScope,
        block: (DecisionState?) -> DecisionCandidate?
    ): DecisionTransactionResult {
        val lock = locks.computeIfAbsent(scope) { Any() }
        return synchronized(lock) {
            val candidate = block(states[scope])
                ?: return@synchronized DecisionTransactionResult.Rejected(
                    DecisionTransactionFailure.REJECTED
                )
            states[scope] = candidate.nextState
            DecisionTransactionResult.Committed(candidate.nextState, candidate.envelope)
        }
    }

    fun inspect(scope: DecisionScope): DecisionState? = states[scope]
}
