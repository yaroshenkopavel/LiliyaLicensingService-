package pro.liliya.licensing.gcpkms

import com.google.cloud.kms.v1.GetPublicKeyRequest
import com.google.cloud.kms.v1.KeyManagementServiceClient
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
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class GcpKmsLiveAcceptanceTest {
    @Test
    fun live_exact_kms_version_signs_verifies_and_fails_closed() {
        val keyVersion = System.getenv("LIVE_GCP_KMS_KEY_VERSION")
        assumeTrue(!keyVersion.isNullOrBlank(), "LIVE_GCP_KMS_KEY_VERSION is not configured")

        KeyManagementServiceClient.create().use { kms ->
            val client = GoogleCloudKmsClientAdapter(kms)
            val logicalKey = SigningKeyReference("live-gcp-kms-v1")
            val signer = GcpKmsLicenseEnvelopeSigner(
                bindings = listOf(
                    GcpKmsSigningKeyBinding(logicalKey, keyVersion!!)
                ),
                client = client
            )
            val payload = "liliya-live-kms-canonical-payload-v1".encodeToByteArray()

            val signed = assertIs<SigningResult.Signed>(
                signer.sign(payload, logicalKey)
            )
            assertEquals(1L, signed.envelope.schemaVersion.value)
            assertEquals("ECDSA-P256-SHA256", signed.envelope.algorithm.value)
            assertEquals(logicalKey, signed.envelope.keyReference)

            val publicKey = kms.getPublicKey(
                GetPublicKeyRequest.newBuilder()
                    .setName(keyVersion)
                    .build()
            )
            assertEquals(
                GcpKmsProductionSigningProfile.kmsAlgorithm,
                publicKey.algorithm.name
            )

            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(parseEcPublicKey(publicKey.pem))
            verifier.update(payload)
            assertTrue(verifier.verify(signed.envelope.copySignature()))

            val mutated = payload.copyOf()
            mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()
            val tamperVerifier = Signature.getInstance("SHA256withECDSA")
            tamperVerifier.initVerify(parseEcPublicKey(publicKey.pem))
            tamperVerifier.update(mutated)
            assertFalse(tamperVerifier.verify(signed.envelope.copySignature()))

            val missingVersion = keyVersion.replace(
                Regex("/cryptoKeyVersions/[^/]+$"),
                "/cryptoKeyVersions/999999999"
            )
            assumeTrue(missingVersion != keyVersion, "unexpected KMS version resource format")

            val unavailableSigner = GcpKmsLicenseEnvelopeSigner(
                bindings = listOf(
                    GcpKmsSigningKeyBinding(logicalKey, missingVersion)
                ),
                client = client
            )
            val unavailable = assertIs<SigningResult.Rejected>(
                unavailableSigner.sign(payload, logicalKey)
            )
            assertEquals(SigningFailure.KEY_UNAVAILABLE, unavailable.reason)

            println(
                "LICENSING_S5_6_KMS_EVIDENCE=" +
                    "{\"algorithm\":true," +
                    "\"realKmsSignature\":true," +
                    "\"publicKeyVerification\":true," +
                    "\"tamperRejected\":true," +
                    "\"missingExactKeyRejected\":true," +
                    "\"noFallback\":true}"
            )
        }
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
