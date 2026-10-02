package pro.liliya.licensing.http

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pro.liliya.licensing.activation.ActivationCodeClaims
import pro.liliya.licensing.activation.ActivationCodeCodec
import pro.liliya.licensing.activation.ActivationCodeEnvelope
import pro.liliya.licensing.activation.ActivationCodePublicKeyResolver
import pro.liliya.licensing.activation.ActivationRedemptionRecord
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.activation.ActivationRedemptionStore
import pro.liliya.licensing.activation.ActivationRedemptionStoreResult
import pro.liliya.licensing.activation.ActivationSubjectGenerator
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

class ActivationRedemptionHttpEndpointContractTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val now = Instant.parse("2026-10-02T11:00:00Z")

    @Test
    fun fresh_install_needs_no_product_auth_and_receives_signed_license() {
        val store = FakeStore()
        var issuedSubject: String? = null
        val endpoint = endpoint(
            store = store,
            issuer = LicensingIssuerProcessor { request ->
                issuedSubject = request.subjectReference
                LicensingIssuerResult.Issued(
                    state = DecisionState(0, 0),
                    envelope = envelope()
                )
            }
        )

        val response = endpoint.handle(request(code(), "attempt-1"))

        assertEquals(200, response.status)
        assertTrue(response.body.toString(Charsets.UTF_8).contains("\"kind\":\"activated\""))
        assertTrue(response.body.toString(Charsets.UTF_8).contains("\"payloadBase64\""))
        assertEquals("liliya-subject-v1:AAAAAAAAAAAAAAAAAAAAAA", issuedSubject)
        assertEquals(1, store.createdCount)
    }

    @Test
    fun same_attempt_reissues_license_but_distinct_attempt_is_exhausted() {
        val store = FakeStore()
        var issuerCalls = 0
        val endpoint = endpoint(
            store = store,
            issuer = LicensingIssuerProcessor {
                issuerCalls += 1
                LicensingIssuerResult.Issued(
                    state = DecisionState(issuerCalls.toLong() - 1, 0),
                    envelope = envelope()
                )
            }
        )
        val activationCode = code()

        val first = endpoint.handle(request(activationCode, "attempt-1"))
        val replay = endpoint.handle(request(activationCode, "attempt-1"))
        val secondAttempt = endpoint.handle(request(activationCode, "attempt-2"))

        assertEquals(200, first.status)
        assertEquals(200, replay.status)
        assertEquals(409, secondAttempt.status)
        assertEquals(1, store.createdCount)
        assertEquals(2, issuerCalls)
    }

    private fun endpoint(
        store: FakeStore = FakeStore(),
        issuer: LicensingIssuerProcessor = LicensingIssuerProcessor {
            LicensingIssuerResult.Issued(
                state = DecisionState(0, 0),
                envelope = envelope()
            )
        }
    ) = ActivationRedemptionHttpEndpoint(
        service = ActivationRedemptionService(
            publicKeys = ActivationCodePublicKeyResolver { keyPair.public },
            store = store,
            subjectGenerator = ActivationSubjectGenerator {
                "liliya-subject-v1:AAAAAAAAAAAAAAAAAAAAAA"
            }
        ),
        issuer = issuer,
        clock = Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun request(
        code: String,
        attemptId: String
    ) = LicenseHttpRequest(
        method = LicenseHttpMethod.POST,
        path = ActivationRedemptionHttpEndpoint.PATH,
        body = """{"wireVersion":1,"activationCode":"$code","attemptId":"$attemptId"}"""
            .toByteArray(),
        authentication = null
    )

    private fun code(): String {
        val claims = ActivationCodeClaims(
            version = 1,
            codeId = "code-http-001",
            productId = "liliya-pro",
            features = setOf("core"),
            expiresAt = Instant.parse("2027-10-02T00:00:00Z"),
            maxRedemptions = 1
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(ActivationCodeCodec.signingPayload(claims))
            sign()
        }
        return ActivationCodeCodec.encode(
            ActivationCodeEnvelope("activation-key-v1", claims, signature)
        )
    }

    private fun envelope() = SignedLicenseEnvelope(
        schemaVersion = SigningEnvelopeSchemaVersion(1),
        algorithm = SigningAlgorithm("ECDSA_P256_SHA256"),
        keyReference = SigningKeyReference("liliya-prod-license-signing-v1"),
        canonicalPayload = "payload".encodeToByteArray(),
        signature = "signature".encodeToByteArray()
    )

    private class FakeStore : ActivationRedemptionStore {
        private var record: ActivationRedemptionRecord? = null
        var createdCount = 0

        override fun redeem(
            claims: ActivationCodeClaims,
            attemptId: String,
            proposedSubject: String,
            now: Instant
        ): ActivationRedemptionStoreResult {
            val existing = record
            if (existing != null) {
                return if (existing.attemptId == attemptId) {
                    ActivationRedemptionStoreResult.Replay(existing)
                } else {
                    ActivationRedemptionStoreResult.Exhausted
                }
            }
            val created = ActivationRedemptionRecord(
                codeId = claims.codeId,
                attemptId = attemptId,
                subject = proposedSubject,
                productId = claims.productId,
                features = claims.features,
                redeemedAt = now
            )
            record = created
            createdCount += 1
            return ActivationRedemptionStoreResult.Created(created)
        }
    }
}
