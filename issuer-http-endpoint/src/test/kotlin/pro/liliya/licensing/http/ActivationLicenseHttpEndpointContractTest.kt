package pro.liliya.licensing.http

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.activation.ActivationCodeHash
import pro.liliya.licensing.activation.ActivationGrant
import pro.liliya.licensing.activation.ActivationGrantStore
import pro.liliya.licensing.activation.ActivationPreparationResult
import pro.liliya.licensing.activation.ActivationPreparedGrant
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.activation.InstallCredentialBinding
import pro.liliya.licensing.activation.InstallCredentialHasher
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

class ActivationLicenseHttpEndpointContractTest {
    @Test
    fun completion_failure_retries_same_grant_bound_issuer_request() {
        val prepared = ActivationPreparedGrant(
            subject = "phone-subject",
            productId = "liliya-pro",
            codeHash = ActivationCodeHash.of(ByteArray(32) { 7 }),
            requestId = "activation-request-1",
            installCredential = InstallCredentialBinding(
                "install-phone-01",
                InstallCredentialHasher.sha256("0123456789abcdef0123456789abcdef")
            )
        )
        var completeCalls = 0
        val store = object : ActivationGrantStore {
            override fun create(grant: ActivationGrant): Boolean = error("not used")
            override fun prepare(
                codeHash: ActivationCodeHash,
                requestId: String,
                installCredential: InstallCredentialBinding,
                now: Instant
            ): ActivationPreparationResult = ActivationPreparationResult.Accepted(prepared)
            override fun complete(
                prepared: ActivationPreparedGrant,
                responseBody: ByteArray,
                now: Instant
            ): Boolean = ++completeCalls == 2
        }
        val issuedIds = mutableListOf<String?>()
        val signed = SignedLicenseEnvelope(
            SigningEnvelopeSchemaVersion(1), SigningAlgorithm("TEST-ED25519"),
            SigningKeyReference("test-key"), byteArrayOf(1), byteArrayOf(2)
        )
        val endpoint = ActivationLicenseHttpEndpoint(
            ActivationRedemptionService(store),
            LicensingIssuerProcessor { request ->
                issuedIds += request.requestId
                LicensingIssuerResult.Issued(DecisionState(0, 0), signed)
            }
        )
        val request = LicenseHttpRequest(
            LicenseHttpMethod.POST, ActivationLicenseHttpEndpoint.PATH,
            activationBody(validCode())
        )
        assertEquals(503, endpoint.handle(request).status)
        assertEquals(200, endpoint.handle(request).status)
        assertEquals(listOf<String?>(prepared.issuerReceiptId, prepared.issuerReceiptId), issuedIds)
    }
    @Test
    fun accepted_code_resolves_identity_server_side_and_reaches_issuer_once() {
        var captured: LicenseServiceRequest? = null
        val endpoint = ActivationLicenseHttpEndpoint(
            redemption = ActivationRedemptionService(
                fixedStore(
                    ActivationPreparationResult.Accepted(ActivationPreparedGrant(
                        subject = "phone-subject",
                        productId = "liliya-pro",
                        codeHash = ActivationCodeHash.of(ByteArray(32) { 1 }),
                        requestId = "activation-request-1",
                        installCredential = InstallCredentialBinding(
                            installId = "install-phone-01",
                            secretHash = InstallCredentialHasher.sha256(
                                "0123456789abcdef0123456789abcdef"
                            )
                        )
                    ))
                )
            ),
            processor = LicensingIssuerProcessor { request ->
                captured = request
                LicensingIssuerResult.Rejected(
                    LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE
                )
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationLicenseHttpEndpoint.PATH,
                body = activationBody(validCode())
            )
        )

        assertEquals(403, response.status)
        val issuedRequest = requireNotNull(captured)
        assertEquals("phone-subject", issuedRequest.subjectReference)
        assertEquals("liliya-pro", issuedRequest.productId)
        assertEquals(LicenseOperation.ISSUE, issuedRequest.operation)
        assertEquals(1, issuedRequest.protocolVersion.value)
        assertIs<LicenseWireResponse.ServiceRejected>(
            assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
                LicenseWireJsonCodec.decodeResponse(response.body)
            ).value
        )
    }

    @Test
    fun invalid_or_reused_code_is_privacy_bounded_and_never_reaches_issuer() {
        var calls = 0
        val endpoint = ActivationLicenseHttpEndpoint(
            redemption = ActivationRedemptionService(
                fixedStore(ActivationPreparationResult.AlreadyRedeemed)
            ),
            processor = LicensingIssuerProcessor {
                calls += 1
                error("must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationLicenseHttpEndpoint.PATH,
                body = activationBody(validCode())
            )
        )

        assertEquals(401, response.status)
        assertEquals(0, calls)
        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        )
        val rejected = assertIs<LicenseWireResponse.ServiceRejected>(decoded.value)
        assertEquals(LicenseServiceFailure.AUTHENTICATION_REQUIRED, rejected.reason)
    }

    @Test
    fun malformed_activation_body_fails_before_store_or_issuer() {
        var storeCalls = 0
        var issuerCalls = 0
        val store = object : ActivationGrantStore {
            override fun create(grant: ActivationGrant): Boolean = error("not used")
            override fun complete(
                prepared: ActivationPreparedGrant,
                responseBody: ByteArray,
                now: Instant
            ): Boolean = true
            override fun prepare(
                codeHash: ActivationCodeHash,
                requestId: String,
                installCredential: InstallCredentialBinding,
                now: Instant
            ): ActivationPreparationResult {
                storeCalls += 1
                return ActivationPreparationResult.Invalid
            }
        }
        val endpoint = ActivationLicenseHttpEndpoint(
            redemption = ActivationRedemptionService(store),
            processor = LicensingIssuerProcessor {
                issuerCalls += 1
                error("must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationLicenseHttpEndpoint.PATH,
                body = """{"wireVersion":1,"kind":"activate"}""".encodeToByteArray()
            )
        )

        assertEquals(400, response.status)
        assertEquals(0, storeCalls)
        assertEquals(0, issuerCalls)
    }

    private fun fixedStore(result: ActivationPreparationResult): ActivationGrantStore =
        object : ActivationGrantStore {
            override fun create(grant: ActivationGrant): Boolean = error("not used")
            override fun complete(
                prepared: ActivationPreparedGrant,
                responseBody: ByteArray,
                now: Instant
            ): Boolean = true
            override fun prepare(
                codeHash: ActivationCodeHash,
                requestId: String,
                installCredential: InstallCredentialBinding,
                now: Instant
            ): ActivationPreparationResult = result
        }

    private fun activationBody(code: String): ByteArray =
        """{"wireVersion":1,"kind":"activate","activationCode":"$code","activationRequestId":"activation-request-1","installId":"install-phone-01","installSecret":"0123456789abcdef0123456789abcdef"}""".encodeToByteArray()

    private fun validCode(): String =
        "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF"
}
