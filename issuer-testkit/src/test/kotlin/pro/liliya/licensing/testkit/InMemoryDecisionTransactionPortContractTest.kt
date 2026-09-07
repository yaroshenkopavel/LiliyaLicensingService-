package pro.liliya.licensing.testkit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pro.liliya.licensing.issuer.DecisionCandidate
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.DecisionTransactionResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningKeyReference

class InMemoryDecisionTransactionPortContractTest {
    @Test
    fun concurrent_transactions_for_same_scope_serialize_monotonic_state() {
        val port = InMemoryDecisionTransactionPort()
        val scope = DecisionScope("subject", "product")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(
            mutableListOf<DecisionTransactionResult>()
        )

        repeat(2) {
            Thread {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                results += port.transact(scope) { current ->
                    val next = (current?.replaySequence ?: -1L) + 1L
                    DecisionCandidate(
                        DecisionState(next, 0),
                        envelope(next)
                    )
                }
                done.countDown()
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(2, results.count { it is DecisionTransactionResult.Committed })
        assertEquals(1L, port.inspect(scope)?.replaySequence)
    }

    private fun envelope(sequence: Long) = SignedLicenseEnvelope(
        SigningKeyReference("test-key"),
        byteArrayOf(sequence.toByte()),
        byteArrayOf(1)
    )
}
