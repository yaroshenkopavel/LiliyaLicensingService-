package pro.liliya.licensing.gcpkms

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class GcpKmsLicenseEnvelopeSignerContractTest {
    @Test
    fun exact_binding_signs_with_fixed_production_profile() {
        val client = FakeClient()
        val signer = signer(client)
        val payload = "canonical-license".encodeToByteArray()

        val result = assertIs<SigningResult.Signed>(
            signer.sign(payload, SigningKeyReference("prod-key-v2"))
        )

        assertEquals(1L, result.envelope.schemaVersion.value)
        assertEquals("ECDSA-P256-SHA256", result.envelope.algorithm.value)
        assertEquals(SigningKeyReference("prod-key-v2"), result.envelope.keyReference)
        assertContentEquals(payload, result.envelope.copyCanonicalPayload())
        assertEquals(listOf(keyVersionV2), client.described)
        assertEquals(listOf(keyVersionV2), client.signed)
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(payload),
            client.lastDigest
        )
    }

    @Test
    fun unknown_logical_key_never_falls_back_to_configured_key() {
        val client = FakeClient()
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("unknown-key")
            )
        )

        assertEquals(SigningFailure.KEY_UNAVAILABLE, rejected.reason)
        assertEquals(emptyList(), client.described)
        assertEquals(emptyList(), client.signed)
    }

    @Test
    fun kms_algorithm_mismatch_fails_before_signing() {
        val client = FakeClient(
            algorithm = "RSA_SIGN_PSS_2048_SHA256"
        )
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("prod-key-v2")
            )
        )

        assertEquals(SigningFailure.SIGNING_REJECTED, rejected.reason)
        assertEquals(listOf(keyVersionV2), client.described)
        assertEquals(emptyList(), client.signed)
    }

    @Test
    fun unavailable_exact_version_fails_closed_without_old_key_retry() {
        val client = FakeClient(describeUnavailable = true)
        val signer = GcpKmsLicenseEnvelopeSigner(
            bindings = listOf(
                GcpKmsSigningKeyBinding(
                    SigningKeyReference("prod-key-v1"),
                    keyVersionV1
                ),
                GcpKmsSigningKeyBinding(
                    SigningKeyReference("prod-key-v2"),
                    keyVersionV2
                )
            ),
            client = client
        )

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("prod-key-v2")
            )
        )

        assertEquals(SigningFailure.KEY_UNAVAILABLE, rejected.reason)
        assertEquals(listOf(keyVersionV2), client.described)
        assertEquals(emptyList(), client.signed)
    }

    @Test
    fun rotated_logical_identity_selects_distinct_exact_kms_version() {
        val client = FakeClient()
        val signer = GcpKmsLicenseEnvelopeSigner(
            bindings = listOf(
                GcpKmsSigningKeyBinding(
                    SigningKeyReference("prod-key-v1"),
                    keyVersionV1
                ),
                GcpKmsSigningKeyBinding(
                    SigningKeyReference("prod-key-v2"),
                    keyVersionV2
                )
            ),
            client = client
        )

        assertIs<SigningResult.Signed>(
            signer.sign("one".encodeToByteArray(), SigningKeyReference("prod-key-v1"))
        )
        assertIs<SigningResult.Signed>(
            signer.sign("two".encodeToByteArray(), SigningKeyReference("prod-key-v2"))
        )

        assertEquals(listOf(keyVersionV1, keyVersionV2), client.signed)
    }

    private fun signer(client: FakeClient) =
        GcpKmsLicenseEnvelopeSigner(
            bindings = listOf(
                GcpKmsSigningKeyBinding(
                    SigningKeyReference("prod-key-v2"),
                    keyVersionV2
                )
            ),
            client = client
        )

    private class FakeClient(
        private val algorithm: String = GcpKmsProductionSigningProfile.kmsAlgorithm,
        private val describeUnavailable: Boolean = false
    ) : GcpKmsAsymmetricClient {
        val described = mutableListOf<String>()
        val signed = mutableListOf<String>()
        var lastDigest: ByteArray? = null

        override fun describe(cryptoKeyVersionName: String): GcpKmsDescribeResult {
            described += cryptoKeyVersionName
            return if (describeUnavailable) {
                GcpKmsDescribeResult.Unavailable
            } else {
                GcpKmsDescribeResult.Available(GcpKmsKeyDescription(algorithm))
            }
        }

        override fun signSha256Digest(
            cryptoKeyVersionName: String,
            digest: ByteArray
        ): GcpKmsSignResult {
            signed += cryptoKeyVersionName
            lastDigest = digest.copyOf()
            return GcpKmsSignResult.Signed("der-signature".encodeToByteArray())
        }
    }

    companion object {
        private const val keyVersionV1 =
            "projects/test/locations/europe-central2/keyRings/liliya/cryptoKeys/license/cryptoKeyVersions/1"
        private const val keyVersionV2 =
            "projects/test/locations/europe-central2/keyRings/liliya/cryptoKeys/license/cryptoKeyVersions/2"
    }
}
