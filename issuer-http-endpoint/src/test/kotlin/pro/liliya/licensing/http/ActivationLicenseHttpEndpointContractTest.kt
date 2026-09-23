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
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse

class ActivationLicenseHttpEndpointContractTest {
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
                        requestId = "activation-request-1"
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
                now: Instant
            ): ActivationPreparationResult = result
        }

    private fun activationBody(code: String): ByteArray =
        """{"wireVersion":1,"kind":"activate","activationCode":"$code","activationRequestId":"activation-request-1"}""".encodeToByteArray()

    private fun validCode(): String =
        "LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF"
}
