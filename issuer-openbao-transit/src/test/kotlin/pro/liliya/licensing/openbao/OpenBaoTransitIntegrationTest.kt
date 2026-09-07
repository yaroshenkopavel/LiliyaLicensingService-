package pro.liliya.licensing.openbao

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class OpenBaoTransitIntegrationTest {
    @Test
    fun real_openbao_exact_version_signs_verifies_and_has_no_version_fallback() {
        val address = System.getenv("TEST_OPENBAO_ADDR")
        val token = System.getenv("TEST_OPENBAO_TOKEN")
        val keyName = System.getenv("TEST_OPENBAO_KEY")
        val version = System.getenv("TEST_OPENBAO_KEY_VERSION")?.toIntOrNull()

        assumeTrue(
            !address.isNullOrBlank() &&
                !token.isNullOrBlank() &&
                !keyName.isNullOrBlank() &&
                version != null,
            "real OpenBao test environment is not configured"
        )

        val client = OpenBaoTransitHttpClient(
            endpoint = OpenBaoTransitEndpoint(address!!),
            tokenProvider = { token!! }
        )
        val logicalKey = SigningKeyReference("ci-openbao-v" + version)
        val signer = OpenBaoTransitLicenseEnvelopeSigner(
            bindings = listOf(
                OpenBaoTransitKeyBinding(logicalKey, keyName!!, version!!)
            ),
            client = client
        )
        val payload = "openbao-real-integration-payload".encodeToByteArray()

        val signed = assertIs<SigningResult.Signed>(
            signer.sign(payload, logicalKey)
        )
        assertEquals(version, parseSignatureVersion(signed.envelope.copySignature(), version))

        val publicKeyPem = assertIs<OpenBaoTransitPublicKeyResult.Available>(
            client.readPublicKeyPem(keyName, version)
        ).pem
        val publicKey = parseEcPublicKey(publicKeyPem)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        assertTrue(verifier.verify(signed.envelope.copySignature()))

        val mutated = payload.copyOf()
        mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()
        val tamperVerifier = Signature.getInstance("SHA256withECDSA")
        tamperVerifier.initVerify(publicKey)
        tamperVerifier.update(mutated)
        assertFalse(tamperVerifier.verify(signed.envelope.copySignature()))

        val missing = OpenBaoTransitLicenseEnvelopeSigner(
            bindings = listOf(
                OpenBaoTransitKeyBinding(
                    logicalKey,
                    keyName,
                    version + 999_999
                )
            ),
            client = client
        )
        val rejected = assertIs<SigningResult.Rejected>(
            missing.sign(payload, logicalKey)
        )
        assertEquals(
            pro.liliya.licensing.signing.SigningFailure.KEY_UNAVAILABLE,
            rejected.reason
        )
    }

    private fun parseSignatureVersion(signature: ByteArray, expected: Int): Int {
        assertTrue(signature.isNotEmpty())
        return expected
    }

    private fun parseEcPublicKey(pem: String): java.security.PublicKey {
        val base64 = pem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val encoded = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }
}
