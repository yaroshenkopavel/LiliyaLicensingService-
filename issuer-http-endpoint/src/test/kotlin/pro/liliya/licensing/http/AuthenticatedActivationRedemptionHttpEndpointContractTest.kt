package pro.liliya.licensing.http

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import pro.liliya.licensing.activation.ActivationCodeClaims
import pro.liliya.licensing.activation.ActivationCodeCodec
import pro.liliya.licensing.activation.ActivationCodeEnvelope
import pro.liliya.licensing.activation.ActivationCodePublicKeyResolver
import pro.liliya.licensing.activation.ActivationRedemptionRecord
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.activation.ActivationRedemptionStore
import pro.liliya.licensing.activation.ActivationRedemptionStoreResult
import pro.liliya.licensing.activation.ActivationSubjectGenerator
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult

class AuthenticatedActivationRedemptionHttpEndpointContractTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val now = Instant.parse("2026-10-02T11:00:00Z")
    @Test
    fun missing_product_auth_is_rejected_before_redemption() {
        val endpoint = endpoint(
            auth = RequestAuthenticationPort {
                RequestAuthenticationResult.Rejected(
                    pro.liliya.licensing.auth.RequestAuthenticationFailure.MISSING
                )
            }
        )
        val response = endpoint.handle(request(code(), "attempt-1", null))
        assertEquals(401, response.status)
    }

    @Test
    fun valid_code_activates_once_and_distinct_retry_is_exhausted() {
        val store = FakeStore()
        val endpoint = endpoint(store = store)

        val first = endpoint.handle(
            request(code(), "attempt-1", RequestAuthenticationCredential.of(byteArrayOf(1)))
        )
        val second = endpoint.handle(
            request(code(), "attempt-2", RequestAuthenticationCredential.of(byteArrayOf(1)))
        )

        assertEquals(200, first.status)
        assertEquals(409, second.status)
        assertEquals(1, store.createdCount)
    }
    private fun endpoint(
        store: FakeStore = FakeStore(),
        auth: RequestAuthenticationPort =
            RequestAuthenticationPort { RequestAuthenticationResult.Authenticated }
    ) = AuthenticatedActivationRedemptionHttpEndpoint(
        service = ActivationRedemptionService(
            publicKeys = ActivationCodePublicKeyResolver { keyPair.public },
            store = store,
            subjectGenerator = ActivationSubjectGenerator {
                "liliya-subject-v1:AAAAAAAAAAAAAAAAAAAAAA"
            }
        ),
        authentication = auth,
        clock = Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun request(
        code: String,
        attemptId: String,
        credential: RequestAuthenticationCredential?
    ) = LicenseHttpRequest(
        method = LicenseHttpMethod.POST,
        path = AuthenticatedActivationRedemptionHttpEndpoint.PATH,
        body = """{"wireVersion":1,"activationCode":"$code","attemptId":"$attemptId"}"""
            .toByteArray(),
        authentication = credential
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
