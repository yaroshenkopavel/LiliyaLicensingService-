package pro.liliya.licensing.openbao

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class OpenBaoTransitLicenseEnvelopeSignerContractTest {
    @Test
    fun exact_version_signs_with_frozen_client_algorithm_identity() {
        val client = FakeClient()
        val signer = signer(client)
        val payload = "canonical-license".encodeToByteArray()

        val signed = assertIs<SigningResult.Signed>(
            signer.sign(payload, SigningKeyReference("openbao-prod-v2"))
        )

        assertEquals(1L, signed.envelope.schemaVersion.value)
        assertEquals("ECDSA-P256-SHA256", signed.envelope.algorithm.value)
        assertEquals(SigningKeyReference("openbao-prod-v2"), signed.envelope.keyReference)
        assertContentEquals(payload, signed.envelope.copyCanonicalPayload())
        assertEquals(listOf(2), client.signedVersions)
    }

    @Test
    fun unknown_logical_key_never_falls_back() {
        val client = FakeClient()
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("unknown-key")
            )
        )

        assertEquals(SigningFailure.KEY_UNAVAILABLE, rejected.reason)
        assertEquals(emptyList(), client.signedVersions)
    }

    @Test
    fun unavailable_exact_version_fails_before_signing() {
        val client = FakeClient(availableVersions = setOf(1))
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("openbao-prod-v2")
            )
        )

        assertEquals(SigningFailure.KEY_UNAVAILABLE, rejected.reason)
        assertEquals(emptyList(), client.signedVersions)
    }

    @Test
    fun wrong_transit_key_type_fails_closed() {
        val client = FakeClient(type = "rsa-2048")
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("openbao-prod-v2")
            )
        )

        assertEquals(SigningFailure.SIGNING_REJECTED, rejected.reason)
        assertEquals(emptyList(), client.signedVersions)
    }

    @Test
    fun server_returning_different_signature_version_is_rejected() {
        val client = FakeClient(returnedVersion = 1)
        val signer = signer(client)

        val rejected = assertIs<SigningResult.Rejected>(
            signer.sign(
                "payload".encodeToByteArray(),
                SigningKeyReference("openbao-prod-v2")
            )
        )

        assertEquals(SigningFailure.SIGNING_REJECTED, rejected.reason)
        assertEquals(listOf(2), client.signedVersions)
    }

    private fun signer(client: FakeClient) =
        OpenBaoTransitLicenseEnvelopeSigner(
            bindings = listOf(
                OpenBaoTransitKeyBinding(
                    keyReference = SigningKeyReference("openbao-prod-v2"),
                    keyName = "license-signing",
                    keyVersion = 2
                )
            ),
            client = client
        )

    private class FakeClient(
        private val type: String = "ecdsa-p256",
        private val availableVersions: Set<Int> = setOf(1, 2),
        private val returnedVersion: Int = 2
    ) : OpenBaoTransitClient {
        val signedVersions = mutableListOf<Int>()

        override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
            OpenBaoTransitDescribeResult.Available(
                OpenBaoTransitKeyDescription(
                    type = type,
                    supportsSigning = true,
                    availableVersions = availableVersions
                )
            )

        override fun sign(
            keyName: String,
            keyVersion: Int,
            input: ByteArray
        ): OpenBaoTransitSignResult {
            signedVersions += keyVersion
            return OpenBaoTransitSignResult.Signed(
                OpenBaoTransitSignature(
                    version = returnedVersion,
                    bytes = "der-signature".encodeToByteArray()
                )
            )
        }

        override fun readPublicKeyPem(
            keyName: String,
            keyVersion: Int
        ): OpenBaoTransitPublicKeyResult =
            OpenBaoTransitPublicKeyResult.Unavailable
    }
}
