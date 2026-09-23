package pro.liliya.licensing.activation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationServiceContractTest {
    private val now = Instant.parse("2026-09-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun successful_activation_returns_credential_once_and_never_exposes_it_in_toString() {
        var seenCredentialDigest: ByteArray? = null
        val store = object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ): ActivationStoreConsumeResult {
                assertEquals(this@ActivationServiceContractTest.now, now)
                seenCredentialDigest = clientCredentialDigest.copyOf()
                return ActivationStoreConsumeResult.Consumed(
                    ActivationGrant("subject-1", "liliya-pro")
                )
            }

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Rejected

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }

        val service = ActivationService(
            store = store,
            clock = clock,
            randomBytes = ActivationRandomBytes { size ->
                ByteArray(size) { index -> (index + 1).toByte() }
            }
        )

        val result = assertIs<ActivationResult.Activated>(
            service.activate("one-time-code".encodeToByteArray())
        )

        val credential = result.credential.copyBytes()
        try {
            assertContentEquals(
                ActivationDigest.sha256(credential),
                seenCredentialDigest
            )
            assertEquals(
                "ActivationClientCredential(<redacted>)",
                result.credential.toString()
            )
            assertEquals(
                "ActivationGrant(subject=<redacted>,productId=liliya-pro)",
                result.grant.toString()
            )
        } finally {
            credential.fill(0)
            result.credential.close()
        }
    }

    @Test
    fun rejected_code_does_not_return_generated_credential() {
        val store = object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = ActivationStoreConsumeResult.Rejected

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Rejected

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }

        val service = ActivationService(
            store = store,
            clock = clock,
            randomBytes = ActivationRandomBytes { size -> ByteArray(size) { 7 } }
        )

        assertIs<ActivationResult.Rejected>(
            service.activate("wrong-code".encodeToByteArray())
        )
    }

    @Test
    fun blank_code_fails_closed_before_store() {
        var calls = 0
        val store = object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ): ActivationStoreConsumeResult {
                calls += 1
                return ActivationStoreConsumeResult.Failed
            }

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Rejected

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }

        val service = ActivationService(store, clock)

        assertIs<ActivationResult.Rejected>(service.activate(byteArrayOf()))
        assertEquals(0, calls)
    }
}
