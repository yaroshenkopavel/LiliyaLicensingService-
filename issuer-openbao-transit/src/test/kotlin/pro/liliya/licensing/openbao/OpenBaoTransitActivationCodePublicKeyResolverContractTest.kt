package pro.liliya.licensing.openbao

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenBaoTransitActivationCodePublicKeyResolverContractTest {
    @Test
    fun resolves_only_exact_logical_activation_key_id() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val pem = buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                .encodeToString(keyPair.public.encoded))
            append("-----END PUBLIC KEY-----")
        }
        val resolver = OpenBaoTransitActivationCodePublicKeyResolver.create(
            client = FakeClient(pem),
            keyId = "activation-key-v1",
            keyName = "activation-signing",
            keyVersion = 2
        )

        assertNotNull(resolver.resolve("activation-key-v1"))
        assertNull(resolver.resolve("other-key"))
    }
    private class FakeClient(
        private val pem: String
    ) : OpenBaoTransitClient {
        override fun describeKey(keyName: String): OpenBaoTransitDescribeResult =
            OpenBaoTransitDescribeResult.Available(
                OpenBaoTransitKeyDescription(
                    type = "ecdsa-p256",
                    supportsSigning = true,
                    availableVersions = setOf(2)
                )
            )

        override fun sign(
            keyName: String,
            keyVersion: Int,
            input: ByteArray
        ): OpenBaoTransitSignResult =
            OpenBaoTransitSignResult.Failed

        override fun readPublicKeyPem(
            keyName: String,
            keyVersion: Int
        ): OpenBaoTransitPublicKeyResult =
            OpenBaoTransitPublicKeyResult.Available(pem)
    }
}
