package pro.liliya.licensing.activation

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ActivationRedemptionContractTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun first_redemption_creates_subject_and_same_attempt_replays() {
        val store = FakeStore()
        val service = service(store)
        val request = ActivationRedemptionRequest(signedCode(), "attempt-1")
        val now = Instant.parse("2026-10-02T10:00:00Z")

        val first = assertIs<ActivationRedemptionResult.Activated>(
            service.redeem(request, now)
        )
        val replay = assertIs<ActivationRedemptionResult.IdempotentReplay>(
            service.redeem(request, now.plusSeconds(1))
        )

        assertEquals(first.subject, replay.subject)
        assertEquals(1, store.createdCount)
    }
    @Test
    fun reused_code_with_different_attempt_is_exhausted() {
        val store = FakeStore()
        val service = service(store)
        val code = signedCode()
        val now = Instant.parse("2026-10-02T10:00:00Z")

        val first = assertIs<ActivationRedemptionResult.Activated>(
            service.redeem(ActivationRedemptionRequest(code, "attempt-1"), now)
        )
        assertIs<ActivationRedemptionResult.CodeExhausted>(
            service.redeem(
                ActivationRedemptionRequest(code, "attempt-2"),
                now.plusSeconds(1)
            )
        )

        assertNotEquals("", first.subject)
        assertEquals(1, store.createdCount)
    }

    @Test
    fun invalid_code_never_reaches_store() {
        val store = FakeStore()
        val result = service(store).redeem(
            ActivationRedemptionRequest("invalid", "attempt-1"),
            Instant.parse("2026-10-02T10:00:00Z")
        )

        assertIs<ActivationRedemptionResult.InvalidCode>(result)
        assertEquals(0, store.calls)
    }
    private fun service(store: FakeStore) = ActivationRedemptionService(
        publicKeys = ActivationCodePublicKeyResolver { keyPair.public },
        store = store,
        subjectGenerator = ActivationSubjectGenerator {
            "liliya-subject-v1:AAAAAAAAAAAAAAAAAAAAAA"
        }
    )

    private fun signedCode(): String {
        val claims = ActivationCodeClaims(
            version = 1,
            codeId = "activation-code-001",
            productId = "liliya-pro",
            features = setOf("core"),
            expiresAt = Instant.parse("2027-10-02T00:00:00Z"),
            maxRedemptions = 1
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(ActivationCodeCodec.signingPayload(claims))
            sign()
        }
        return ActivationCodeCodec.encode(
            ActivationCodeEnvelope("activation-key-v1", claims, signature)
        )
    }

    private class FakeStore : ActivationRedemptionStore {
        var calls = 0
        var createdCount = 0
        private var record: ActivationRedemptionRecord? = null
        override fun redeem(
            claims: ActivationCodeClaims,
            attemptId: String,
            proposedSubject: String,
            now: Instant
        ): ActivationRedemptionStoreResult {
            calls += 1
            val existing = record
            if (existing != null) {
                return if (existing.attemptId == attemptId) {
                    ActivationRedemptionStoreResult.Replay(existing)
                } else {
                    ActivationRedemptionStoreResult.Exhausted
                }
            }
            val created = ActivationRedemptionRecord(
                codeId = claims.codeId,
                attemptId = attemptId,
                subject = proposedSubject,
                productId = claims.productId,
                features = claims.features,
                redeemedAt = now
            )
            record = created
            createdCount += 1
            return ActivationRedemptionStoreResult.Created(created)
        }
    }
}
