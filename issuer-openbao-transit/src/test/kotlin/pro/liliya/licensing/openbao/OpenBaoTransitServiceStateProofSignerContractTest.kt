package pro.liliya.licensing.openbao

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.servicestate.ServiceStateSigningResult

class OpenBaoTransitServiceStateProofSignerContractTest {
    @Test
    fun signs_exact_transcript_with_exact_configured_key_version() {
        val calls = mutableListOf<Triple<String, Int, ByteArray>>()
        val client = object : OpenBaoTransitClient {
            override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
                OpenBaoTransitDescribeResult.Available(
                    OpenBaoTransitKeyDescription(
                        type = "ecdsa-p256",
                        supportsSigning = true,
                        availableVersions = setOf(1, 2)
                    )
                )

            override fun sign(
                keyName: String,
                keyVersion: Int,
                input: ByteArray
            ): OpenBaoTransitSignResult {
                calls += Triple(keyName, keyVersion, input.copyOf())
                return OpenBaoTransitSignResult.Signed(
                    OpenBaoTransitSignature(
                        version = keyVersion,
                        bytes = byteArrayOf(7, 8, 9)
                    )
                )
            }

            override fun readPublicKeyPem(
                keyName: String,
                keyVersion: Int
            ): OpenBaoTransitPublicKeyResult = OpenBaoTransitPublicKeyResult.Unavailable
        }

        val signer = OpenBaoTransitServiceStateProofSigner(
            client = client,
            keyName = "service-state-key",
            keyVersion = 2
        )
        val transcript = byteArrayOf(1, 2, 3, 4)

        val result = assertIs<ServiceStateSigningResult.Signed>(
            signer.sign(transcript)
        )

        assertEquals(1, calls.size)
        assertEquals("service-state-key", calls.single().first)
        assertEquals(2, calls.single().second)
        assertContentEquals(transcript, calls.single().third)
        assertContentEquals(byteArrayOf(7, 8, 9), result.proof.copyBytes())
    }

    @Test
    fun missing_exact_version_fails_closed_without_sign_call() {
        var signCalls = 0
        val client = object : OpenBaoTransitClient {
            override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
                OpenBaoTransitDescribeResult.Available(
                    OpenBaoTransitKeyDescription(
                        type = "ecdsa-p256",
                        supportsSigning = true,
                        availableVersions = setOf(1)
                    )
                )

            override fun sign(
                keyName: String,
                keyVersion: Int,
                input: ByteArray
            ): OpenBaoTransitSignResult {
                signCalls += 1
                return OpenBaoTransitSignResult.Failed
            }

            override fun readPublicKeyPem(
                keyName: String,
                keyVersion: Int
            ): OpenBaoTransitPublicKeyResult = OpenBaoTransitPublicKeyResult.Unavailable
        }

        val result = OpenBaoTransitServiceStateProofSigner(
            client = client,
            keyName = "service-state-key",
            keyVersion = 2
        ).sign(byteArrayOf(1))

        assertEquals(ServiceStateSigningResult.KeyUnavailable, result)
        assertEquals(0, signCalls)
    }

    @Test
    fun returned_wrong_version_is_rejected() {
        val client = object : OpenBaoTransitClient {
            override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
                OpenBaoTransitDescribeResult.Available(
                    OpenBaoTransitKeyDescription(
                        type = "ecdsa-p256",
                        supportsSigning = true,
                        availableVersions = setOf(1, 2)
                    )
                )

            override fun sign(
                keyName: String,
                keyVersion: Int,
                input: ByteArray
            ): OpenBaoTransitSignResult =
                OpenBaoTransitSignResult.Signed(
                    OpenBaoTransitSignature(
                        version = 1,
                        bytes = byteArrayOf(5)
                    )
                )

            override fun readPublicKeyPem(
                keyName: String,
                keyVersion: Int
            ): OpenBaoTransitPublicKeyResult = OpenBaoTransitPublicKeyResult.Unavailable
        }

        val result = OpenBaoTransitServiceStateProofSigner(
            client = client,
            keyName = "service-state-key",
            keyVersion = 2
        ).sign(byteArrayOf(1))

        assertEquals(ServiceStateSigningResult.Rejected, result)
    }
}
