package pro.liliya.licensing.activation

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivationCodeContractTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun signed_single_redemption_code_verifies() {
        val claims = claims()
        val code = signedCode(claims)

        val verified = assertIs<ActivationCodeVerificationResult.Verified>(
            ActivationCodeVerifier.verify(
                encoded = code,
                keys = ActivationCodePublicKeyResolver { keyPair.public },
                now = Instant.parse("2026-10-02T09:00:00Z")
            )
        )

        assertEquals(claims, verified.claims)
        assertEquals(1, verified.claims.maxRedemptions)
    }
    @Test
    fun tampered_payload_is_rejected() {
        val code = signedCode(claims())
        val parsed = assertIs<ActivationCodeParseResult.Parsed>(ActivationCodeCodec.parse(code))
        val tamperedClaims = parsed.envelope.claims.copy(productId = "liliya-enterprise")
        val tampered = ActivationCodeCodec.encode(
            parsed.envelope.copy(claims = tamperedClaims)
        )

        assertIs<ActivationCodeVerificationResult.InvalidSignature>(
            ActivationCodeVerifier.verify(
                tampered,
                ActivationCodePublicKeyResolver { keyPair.public },
                Instant.parse("2026-10-02T09:00:00Z")
            )
        )
    }

    @Test
    fun expired_code_is_rejected_after_signature_verification() {
        val code = signedCode(claims(expiresAt = Instant.parse("2026-10-01T00:00:00Z")))

        assertIs<ActivationCodeVerificationResult.Expired>(
            ActivationCodeVerifier.verify(
                code,
                ActivationCodePublicKeyResolver { keyPair.public },
                Instant.parse("2026-10-02T09:00:00Z")
            )
        )
    }
    @Test
    fun canonical_payload_is_feature_order_independent() {
        val a = claims(features = linkedSetOf("core", "offline"))
        val b = claims(features = linkedSetOf("offline", "core"))

        assertContentEquals(
            ActivationCodeCodec.signingPayload(a),
            ActivationCodeCodec.signingPayload(b)
        )
    }

    @Test
    fun unknown_key_and_malformed_code_fail_closed() {
        val code = signedCode(claims())

        assertIs<ActivationCodeVerificationResult.UnknownKey>(
            ActivationCodeVerifier.verify(
                code,
                ActivationCodePublicKeyResolver { null },
                Instant.parse("2026-10-02T09:00:00Z")
            )
        )
        assertIs<ActivationCodeVerificationResult.Malformed>(
            ActivationCodeVerifier.verify(
                "not-an-activation-code",
                ActivationCodePublicKeyResolver { keyPair.public },
                Instant.parse("2026-10-02T09:00:00Z")
            )
        )
    }
    @Test
    fun generator_creates_verifiable_owner_code_without_exposing_private_key() {
        val generator = ActivationCodeGenerator(
            keyId = "activation-key-v1",
            signer = ActivationCodeSigner { payload ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(payload)
                    sign()
                }
            }
        )

        val code = generator.generate(
            productId = "liliya-pro",
            features = setOf("core"),
            expiresAt = Instant.parse("2027-10-02T00:00:00Z")
        )

        val verified = assertIs<ActivationCodeVerificationResult.Verified>(
            ActivationCodeVerifier.verify(
                code,
                ActivationCodePublicKeyResolver { keyPair.public },
                Instant.parse("2026-10-02T09:00:00Z")
            )
        )
        assertEquals("liliya-pro", verified.claims.productId)
        assertEquals(1, verified.claims.maxRedemptions)
    }

    private fun signedCode(claims: ActivationCodeClaims): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(ActivationCodeCodec.signingPayload(claims))
            sign()
        }
        return ActivationCodeCodec.encode(
            ActivationCodeEnvelope(
                keyId = "activation-key-v1",
                claims = claims,
                signature = signature
            )
        )
    }

    private fun claims(
        features: Set<String> = linkedSetOf("core", "offline"),
        expiresAt: Instant? = Instant.parse("2027-10-02T00:00:00Z")
    ) = ActivationCodeClaims(
        version = 1,
        codeId = "code-001",
        productId = "liliya-pro",
        features = features,
        expiresAt = expiresAt,
        maxRedemptions = 1
    )
}
