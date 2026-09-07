package pro.liliya.licensing.openbao

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.licensing.protocol.CanonicalEntitlementCodec
import pro.liliya.licensing.protocol.CanonicalLicenseEntitlement
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class OpenBaoTransitLiveAcceptanceTest {
    @Test
    fun live_openbao_transit_signs_real_entitlement_and_emits_cross_repo_evidence() {
        val address = System.getenv("LIVE_OPENBAO_ADDR")
        val token = System.getenv("LIVE_OPENBAO_TOKEN")
        val keyName = System.getenv("LIVE_OPENBAO_KEY")
        val keyVersion = System.getenv("LIVE_OPENBAO_KEY_VERSION")?.toIntOrNull()

        assumeTrue(
            !address.isNullOrBlank() &&
                !token.isNullOrBlank() &&
                !keyName.isNullOrBlank() &&
                keyVersion != null,
            "LIVE_OPENBAO_* environment is not configured"
        )

        val client = OpenBaoTransitHttpClient(
            endpoint = OpenBaoTransitEndpoint(address!!),
            tokenProvider = { token!! }
        )
        val logicalKey = SigningKeyReference("live-openbao-v" + keyVersion)
        val signer = OpenBaoTransitLicenseEnvelopeSigner(
            bindings = listOf(
                OpenBaoTransitKeyBinding(logicalKey, keyName!!, keyVersion!!)
            ),
            client = client
        )

        val now = Instant.now()
        val entitlement = CanonicalLicenseEntitlement(
            id = "live-openbao-license-001",
            subject = "live-openbao-subject",
            productId = "liliya-pro",
            features = setOf("model.local"),
            version = 1,
            signingKeyId = logicalKey.value,
            issuedAt = now.minusSeconds(30),
            notBefore = now.minusSeconds(10),
            expiresAt = now.plusSeconds(3600),
            offlineLeaseUntil = now.plusSeconds(1800),
            revocationEpoch = 7,
            replaySequence = 11
        )
        val payload = CanonicalEntitlementCodec.encode(entitlement)

        val signed = assertIs<SigningResult.Signed>(
            signer.sign(payload, logicalKey)
        )
        assertEquals(1L, signed.envelope.schemaVersion.value)
        assertEquals("ECDSA-P256-SHA256", signed.envelope.algorithm.value)

        val publicKeyPem = assertIs<OpenBaoTransitPublicKeyResult.Available>(
            client.readPublicKeyPem(keyName, keyVersion)
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
                    keyVersion + 999_999
                )
            ),
            client = client
        )
        val missingResult = assertIs<SigningResult.Rejected>(
            missing.sign(payload, logicalKey)
        )
        assertEquals(SigningFailure.KEY_UNAVAILABLE, missingResult.reason)

        writeEvidenceIfRequested(
            signed = signed,
            publicKeyDer = publicKey.encoded
        )

        println(
            "LICENSING_S5_6B_OPENBAO_EVIDENCE=" +
                "{\"externalTransitSignature\":true," +
                "\"exactKeyVersion\":true," +
                "\"publicKeyVerification\":true," +
                "\"tamperRejected\":true," +
                "\"missingExactVersionRejected\":true," +
                "\"noFallback\":true," +
                "\"privateKeyNotReturnedBySigningApi\":true}"
        )
    }

    private fun writeEvidenceIfRequested(
        signed: SigningResult.Signed,
        publicKeyDer: ByteArray
    ) {
        val path = System.getenv("LIVE_OPENBAO_EVIDENCE_PATH")
            ?.takeIf { it.isNotBlank() }
            ?: return

        val target = Path.of(path)
        target.parent?.let(Files::createDirectories)

        val properties = Properties().apply {
            setProperty("schemaVersion", signed.envelope.schemaVersion.value.toString())
            setProperty("algorithm", signed.envelope.algorithm.value)
            setProperty("keyReference", signed.envelope.keyReference.value)
            setProperty(
                "payloadBase64",
                Base64.getEncoder().encodeToString(signed.envelope.copyCanonicalPayload())
            )
            setProperty(
                "signatureBase64",
                Base64.getEncoder().encodeToString(signed.envelope.copySignature())
            )
            setProperty(
                "publicKeyDerBase64",
                Base64.getEncoder().encodeToString(publicKeyDer)
            )
        }

        Files.newOutputStream(target).use { output ->
            properties.store(output, "Liliya Licensing S5 OpenBao Transit live evidence")
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
