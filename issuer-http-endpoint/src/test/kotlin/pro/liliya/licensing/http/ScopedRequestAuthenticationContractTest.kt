package pro.liliya.licensing.http

import kotlin.test.Test
import kotlin.test.assertEquals
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.auth.RequestAuthenticationScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireRequest

class ScopedRequestAuthenticationContractTest {
    @Test
    fun scoped_credential_reaches_issuer_only_for_exact_subject_and_product() {
        var processorCalls = 0
        val endpoint = AuthenticatedLicenseHttpEndpoint(
            delegate = LicenseHttpEndpoint(
                LicensingIssuerProcessor {
                    processorCalls += 1
                    LicensingIssuerResult.Issued(
                        DecisionState(0, 0),
                        envelope()
                    )
                }
            ),
            authentication = RequestAuthenticationPort {
                RequestAuthenticationResult.AuthenticatedScoped(
                    RequestAuthenticationScope(
                        subject = "subject-a",
                        productId = "liliya-pro"
                    )
                )
            }
        )

        val accepted = endpoint.handle(request("subject-a", "liliya-pro"))
        val wrongSubject = endpoint.handle(request("subject-b", "liliya-pro"))
        val wrongProduct = endpoint.handle(request("subject-a", "other-product"))

        assertEquals(200, accepted.status)
        assertEquals(401, wrongSubject.status)
        assertEquals(401, wrongProduct.status)
        assertEquals(1, processorCalls)
    }

    private fun request(
        subject: String,
        productId: String
    ): LicenseHttpRequest =
        LicenseHttpRequest(
            method = LicenseHttpMethod.POST,
            path = LicenseHttpEndpoint.PATH,
            body = LicenseWireJsonCodec.encodeRequest(
                LicenseWireRequest.ServiceRequest(
                    wireVersion = LicenseWireJsonCodec.currentVersion,
                    request = LicenseServiceRequest(
                        protocolVersion = LicenseProtocolVersion(1),
                        operation = LicenseOperation.ISSUE,
                        productId = productId,
                        subjectReference = subject,
                        requestId = "scoped-auth-test"
                    )
                )
            ),
            authentication = pro.liliya.licensing.auth.RequestAuthenticationCredential.of(
                "credential".encodeToByteArray()
            )
        )

    private fun envelope(): SignedLicenseEnvelope =
        SignedLicenseEnvelope(
            schemaVersion = SigningEnvelopeSchemaVersion(1),
            algorithm = SigningAlgorithm("ECDSA-P256-SHA256"),
            keyReference = SigningKeyReference("test-key"),
            canonicalPayload = byteArrayOf(1),
            signature = byteArrayOf(2)
        )
}
