package pro.liliya.licensing.openbao

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class OpenBaoTransitActivationCodeSignerContractTest {
    @Test
    fun signs_exact_payload_with_exact_activation_key_version() {
        val client = FakeClient()
        val signer = OpenBaoTransitActivationCodeSigner(
            client = client,
            keyName = "activation-signing",
            keyVersion = 3
        )
        val payload = byteArrayOf(1, 2, 3, 4)

        val signature = signer.sign(payload)

        assertContentEquals(byteArrayOf(9, 8, 7), signature)
        assertContentEquals(payload, client.lastInput)
    }

    @Test
    fun unexpected_signature_version_fails_closed() {
        val client = FakeClient(signatureVersion = 4)
        val signer = OpenBaoTransitActivationCodeSigner(
            client = client,
            keyName = "activation-signing",
            keyVersion = 3
        )

        assertFailsWith<IllegalStateException> {
            signer.sign(byteArrayOf(1))
        }
    }
    private class FakeClient(
        private val signatureVersion: Int = 3
    ) : OpenBaoTransitClient {
        var lastInput: ByteArray? = null

        override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
            OpenBaoTransitDescribeResult.Available(
                OpenBaoTransitKeyDescription(
                    type = "ecdsa-p256",
                    supportsSigning = true,
                    availableVersions = setOf(3)
                )
            )

        override fun sign(
            keyName: String,
            keyVersion: Int,
            input: ByteArray
        ): OpenBaoTransitSignResult {
            lastInput = input.copyOf()
            return OpenBaoTransitSignResult.Signed(
                OpenBaoTransitSignature(
                    version = signatureVersion,
                    bytes = byteArrayOf(9, 8, 7)
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
