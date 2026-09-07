package pro.liliya.licensing.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireRequest
import pro.liliya.licensing.transport.LicenseWireResponse

class AuthenticatedLicenseHttpEndpointContractTest {
    @Test
    fun valid_authenticated_request_reaches_processor_exactly_once() {
        var authCalls = 0
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                authCalls++
                RequestAuthenticationResult.Authenticated
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                LicensingIssuerResult.Issued(
                    DecisionState(0, 0),
                    envelope()
                )
            }
        )

        val response = endpoint.handle(validRequest(credential()))

        assertEquals(1, authCalls)
        assertEquals(1, processorCalls)
        assertEquals(200, response.status)
    }

    @Test
    fun missing_authentication_returns_typed_401_and_processor_zero_calls() {
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                if (it == null) {
                    RequestAuthenticationResult.Rejected(
                        RequestAuthenticationFailure.MISSING
                    )
                } else {
                    RequestAuthenticationResult.Authenticated
                }
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                error("processor must not be called")
            }
        )

        val response = endpoint.handle(validRequest(authentication = null))

        assertEquals(0, processorCalls)
        assertEquals(401, response.status)
        val decoded = assertIs<LicenseWireDecodeResult.Decoded<LicenseWireResponse>>(
            LicenseWireJsonCodec.decodeResponse(response.body)
        ).value
        assertEquals(
            LicenseServiceFailure.AUTHENTICATION_REQUIRED,
            assertIs<LicenseWireResponse.ServiceRejected>(decoded).reason
        )
    }

    @Test
    fun invalid_authentication_returns_typed_401_and_processor_zero_calls() {
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Rejected(
                    RequestAuthenticationFailure.INVALID
                )
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                error("processor must not be called")
            }
        )

        val response = endpoint.handle(validRequest(credential()))

        assertEquals(0, processorCalls)
        assertEquals(401, response.status)
    }

    @Test
    fun authentication_dependency_unavailable_returns_503_and_processor_zero_calls() {
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Rejected(
                    RequestAuthenticationFailure.UNAVAILABLE
                )
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                error("processor must not be called")
            }
        )

        val response = endpoint.handle(validRequest(credential()))

        assertEquals(0, processorCalls)
        assertEquals(503, response.status)
        assertTrue(response.body.isEmpty())
    }

    @Test
    fun malformed_wire_is_rejected_before_authentication_and_processor() {
        var authCalls = 0
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                authCalls++
                RequestAuthenticationResult.Authenticated
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                error("processor must not be called")
            }
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = LicenseHttpEndpoint.PATH,
                body = "{broken".encodeToByteArray(),
                authentication = credential()
            )
        )

        assertEquals(0, authCalls)
        assertEquals(0, processorCalls)
        assertEquals(400, response.status)
    }

    @Test
    fun wrong_route_and_method_do_not_consult_authentication() {
        var authCalls = 0
        var processorCalls = 0
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                authCalls++
                RequestAuthenticationResult.Authenticated
            },
            processor = LicensingIssuerProcessor {
                processorCalls++
                error("processor must not be called")
            }
        )

        assertEquals(
            404,
            endpoint.handle(
                LicenseHttpRequest(
                    method = LicenseHttpMethod.POST,
                    path = "/wrong",
                    body = byteArrayOf(),
                    authentication = credential()
                )
            ).status
        )
        assertEquals(
            405,
            endpoint.handle(
                LicenseHttpRequest(
                    method = LicenseHttpMethod.GET,
                    path = LicenseHttpEndpoint.PATH,
                    body = byteArrayOf(),
                    authentication = credential()
                )
            ).status
        )

        assertEquals(0, authCalls)
        assertEquals(0, processorCalls)
    }

    @Test
    fun request_and_endpoint_rendering_never_expose_credential() {
        val secret = "VERY-PRIVATE-TRANSPORT-CREDENTIAL"
        val request = validRequest(
            RequestAuthenticationCredential.of(secret.encodeToByteArray())
        )
        val endpoint = endpoint(
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.Authenticated
            },
            processor = LicensingIssuerProcessor {
                LicensingIssuerResult.Rejected(
                    LicenseServiceFailure.AUTHENTICATION_REQUIRED
                )
            }
        )

        assertTrue(secret !in request.toString())
        assertTrue(secret !in endpoint.toString())
        assertTrue("<redacted>" in request.toString())
    }

    private fun endpoint(
        authentication: RequestAuthenticationPort,
        processor: LicensingIssuerProcessor
    ): AuthenticatedLicenseHttpEndpoint =
        AuthenticatedLicenseHttpEndpoint(
            delegate = LicenseHttpEndpoint(processor),
            authentication = authentication
        )

    private fun validRequest(
        authentication: RequestAuthenticationCredential?
    ): LicenseHttpRequest =
        LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = LicenseHttpEndpoint.PATH,
            body = LicenseWireJsonCodec.encodeRequest(
                LicenseWireRequest.ServiceRequest(
                    wireVersion = LicenseWireJsonCodec.currentVersion,
                    request = serviceRequest()
                )
            ),
            authentication = authentication
        )

    private fun serviceRequest(): LicenseServiceRequest =
        LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = "liliya-pro",
            subjectReference = "subject-private",
            requestId = "attempt-private"
        )

    private fun credential(): RequestAuthenticationCredential =
        RequestAuthenticationCredential.of(
            "test-credential".encodeToByteArray()
        )

    private fun envelope(): SignedLicenseEnvelope =
        SignedLicenseEnvelope(
            schemaVersion = SigningEnvelopeSchemaVersion(1),
            algorithm = SigningAlgorithm("ECDSA-P256-SHA256"),
            keyReference = SigningKeyReference("openbao-test"),
            canonicalPayload = byteArrayOf(1, 2, 3),
            signature = byteArrayOf(4, 5, 6)
        )
}
