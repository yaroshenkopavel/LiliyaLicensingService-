package pro.liliya.licensing.testkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.licensing.protocol.CanonicalLicenseEntitlement
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.LicenseSigningCompositionResult
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class InMemoryEd25519SigningFixtureContractTest {
    @Test
    fun exact_active_key_signs_and_verifies() {
        val fixture = InMemoryEd25519SigningFixture.create(setOf("test-key-1"))
        val signed = assertIs<LicenseSigningCompositionResult.Signed>(
            LicenseSigningComposition(fixture).sign(entitlement("test-key-1"))
        )

        assertTrue(fixture.verify(signed.envelope))
    }

    @Test
    fun payload_mutation_breaks_signature_verification() {
        val fixture = InMemoryEd25519SigningFixture.create(setOf("test-key-1"))
        val signed = assertIs<LicenseSigningCompositionResult.Signed>(
            LicenseSigningComposition(fixture).sign(entitlement("test-key-1"))
        )
        val mutated = signed.envelope.copyCanonicalPayload()
        mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()

        assertFalse(
            fixture.verify(
                signed.envelope.keyReference,
                mutated,
                signed.envelope.copySignature()
            )
        )
    }

    @Test
    fun missing_key_never_falls_back_to_another_active_key() {
        val fixture = InMemoryEd25519SigningFixture.create(setOf("test-key-a", "test-key-b"))

        val rejected = assertIs<SigningResult.Rejected>(
            fixture.sign(byteArrayOf(1, 2, 3), SigningKeyReference("test-key-missing"))
        )

        assertEquals(SigningFailure.KEY_UNAVAILABLE, rejected.reason)
    }

    @Test
    fun retired_key_fails_closed() {
        val fixture = InMemoryEd25519SigningFixture.create(
            activeKeys = setOf("test-key-active"),
            retiredKeys = setOf("test-key-retired")
        )

        val rejected = assertIs<SigningResult.Rejected>(
            fixture.sign(byteArrayOf(1), SigningKeyReference("test-key-retired"))
        )

        assertEquals(SigningFailure.KEY_RETIRED, rejected.reason)
    }

    private fun entitlement(keyId: String) = CanonicalLicenseEntitlement(
        id = "lic-001",
        subject = "subject-001",
        productId = "liliya-pro",
        features = setOf("core"),
        version = 1,
        signingKeyId = keyId,
        issuedAt = Instant.parse("2026-09-07T08:00:00Z"),
        notBefore = Instant.parse("2026-09-07T08:00:00Z"),
        expiresAt = Instant.parse("2026-10-07T08:00:00Z"),
        offlineLeaseUntil = Instant.parse("2026-09-14T08:00:00Z"),
        revocationEpoch = 3,
        replaySequence = 9
    )
}
