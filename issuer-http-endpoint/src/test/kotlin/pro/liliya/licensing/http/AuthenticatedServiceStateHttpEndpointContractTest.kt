package pro.liliya.licensing.http

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.servicestate.CurrentDecisionStateReadPort
import pro.liliya.licensing.servicestate.ServiceStateAuthenticationProof
import pro.liliya.licensing.servicestate.ServiceStateEvidenceService
import pro.liliya.licensing.servicestate.ServiceStateProofSigner
import pro.liliya.licensing.servicestate.ServiceStateProtocolVersion
import pro.liliya.licensing.servicestate.ServiceStateScope
import pro.liliya.licensing.servicestate.ServiceStateSigningKeyId
import pro.liliya.licensing.servicestate.ServiceStateSigningResult
import pro.liliya.licensing.transport.ServiceStateWireDecodeResult
import pro.liliya.licensing.transport.ServiceStateWireJsonCodec
import pro.liliya.licensing.transport.ServiceStateWireRequest
import pro.liliya.licensing.transport.ServiceStateWireResponse

class AuthenticatedServiceStateHttpEndpointContractTest {
    @Test
    fun missing_auth_never_reads_authoritative_state_or_signs() {
        val reads = AtomicInteger(0)
        val signs = AtomicInteger(0)
        val endpoint = endpoint(
            reads = reads,
            signs = signs,
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.MISSING)
            }
        )

        val response = endpoint.handle(request(authentication = null))

        assertEquals(401, response.status)
        assertEquals(0, reads.get())
        assertEquals(0, signs.get())
        assertIs<ServiceStateWireResponse.Rejected>(
            assertIs<ServiceStateWireDecodeResult.Decoded<ServiceStateWireResponse>>(
                ServiceStateWireJsonCodec.decodeResponse(response.body)
            ).value
        )
    }

    @Test
    fun invalid_auth_never_reads_authoritative_state_or_signs() {
        val reads = AtomicInteger(0)
        val signs = AtomicInteger(0)
        val endpoint = endpoint(
            reads = reads,
            signs = signs,
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.INVALID)
            }
        )

        val response = endpoint.handle(
            request(authentication = RequestAuthenticationCredential.of("wrong".encodeToByteArray()))
        )

        assertEquals(401, response.status)
        assertEquals(0, reads.get())
        assertEquals(0, signs.get())
    }

    @Test
    fun authenticated_request_reads_once_signs_once_and_returns_separate_evidence() {
        val reads = AtomicInteger(0)
        val signs = AtomicInteger(0)
        val endpoint = endpoint(
            reads = reads,
            signs = signs,
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Authenticated
            }
        )

        val response = endpoint.handle(
            request(authentication = RequestAuthenticationCredential.of("accepted".encodeToByteArray()))
        )

        assertEquals(200, response.status)
        assertEquals(1, reads.get())
        assertEquals(1, signs.get())

        val decoded = assertIs<ServiceStateWireDecodeResult.Decoded<ServiceStateWireResponse>>(
            ServiceStateWireJsonCodec.decodeResponse(response.body)
        ).value
        val evidence = assertIs<ServiceStateWireResponse.Evidence>(decoded)
        assertEquals("ECDSA-P256-SHA256-SERVICE-STATE-V1", evidence.envelope.profile.value)
        assertEquals("service-state-key-v1", evidence.envelope.signingKeyId.value)
    }

    private fun endpoint(
        reads: AtomicInteger,
        signs: AtomicInteger,
        authentication: RequestAuthenticationPort
    ): AuthenticatedServiceStateHttpEndpoint {
        val states = CurrentDecisionStateReadPort {
            reads.incrementAndGet()
            DecisionState(replaySequence = 12, revocationEpoch = 4)
        }
        val signer = ServiceStateProofSigner {
            signs.incrementAndGet()
            ServiceStateSigningResult.Signed(
                ServiceStateAuthenticationProof.of(byteArrayOf(1, 2, 3))
            )
        }
        return AuthenticatedServiceStateHttpEndpoint(
            service = ServiceStateEvidenceService(
                states = states,
                signer = signer,
                signingKeyId = ServiceStateSigningKeyId("service-state-key-v1")
            ),
            authentication = authentication
        )
    }

    private fun request(
        authentication: RequestAuthenticationCredential?
    ): LicenseHttpRequest =
        LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = AuthenticatedServiceStateHttpEndpoint.PATH,
            body = ServiceStateWireJsonCodec.encodeRequest(
                ServiceStateWireRequest(
                    wireVersion = ServiceStateWireJsonCodec.currentVersion,
                    protocolVersion = ServiceStateProtocolVersion(1),
                    scope = ServiceStateScope(
                        productId = "liliya-pro",
                        subject = "PRIVATE-SERVICE-STATE-SUBJECT"
                    ),
                    requestId = "PRIVATE-SERVICE-STATE-REQUEST"
                )
            ),
            authentication = authentication
        )
}
