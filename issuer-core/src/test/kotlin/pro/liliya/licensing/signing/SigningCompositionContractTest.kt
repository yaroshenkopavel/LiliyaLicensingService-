package pro.liliya.licensing.signing

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.protocol.CanonicalLicenseEntitlement
import pro.liliya.licensing.protocol.LicenseServiceFailure

class SigningCompositionContractTest {
    @Test
    fun unavailable_exact_key_fails_closed_without_substitution() {
        val requested = mutableListOf<SigningKeyReference>()
        val signer = LicenseEnvelopeSigner { _, key ->
            requested += key
            SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)
        }

        val result = LicenseSigningComposition(signer).sign(entitlement("key-required"))

        val rejected = assertIs<LicenseSigningCompositionResult.Rejected>(result)
        assertEquals(LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE, rejected.reason)
        assertEquals(listOf(SigningKeyReference("key-required")), requested)
    }

    @Test
    fun signer_returning_different_key_identity_is_rejected() {
        val signer = LicenseEnvelopeSigner { payload, _ ->
            SigningResult.Signed(
                SignedLicenseEnvelope(
                    SigningKeyReference("different-key"),
                    payload,
                    byteArrayOf(1)
                )
            )
        }

        val rejected = assertIs<LicenseSigningCompositionResult.Rejected>(
            LicenseSigningComposition(signer).sign(entitlement("expected-key"))
        )
        assertEquals(LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE, rejected.reason)
    }

    @Test
    fun signer_returning_different_payload_is_rejected() {
        val signer = LicenseEnvelopeSigner { _, key ->
            SigningResult.Signed(
                SignedLicenseEnvelope(
                    key,
                    "different-payload".encodeToByteArray(),
                    byteArrayOf(1)
                )
            )
        }

        val rejected = assertIs<LicenseSigningCompositionResult.Rejected>(
            LicenseSigningComposition(signer).sign(entitlement("expected-key"))
        )
        assertEquals(LicenseServiceFailure.INTERNAL_FAILURE, rejected.reason)
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
