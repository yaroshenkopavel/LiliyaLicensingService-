package pro.liliya.licensing.http

import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.servicestate.ServiceStateEvidenceResult
import pro.liliya.licensing.servicestate.ServiceStateEvidenceService
import pro.liliya.licensing.servicestate.ServiceStateProtocolVersion
import pro.liliya.licensing.transport.ServiceStateWireDecodeResult
import pro.liliya.licensing.transport.ServiceStateWireFailure
import pro.liliya.licensing.transport.ServiceStateWireJsonCodec
import pro.liliya.licensing.transport.ServiceStateWireResponse

class AuthenticatedServiceStateHttpEndpoint(
    private val service: ServiceStateEvidenceService,
    private val authentication: RequestAuthenticationPort
) {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) {
            return empty(404)
        }
        if (request.method != LicenseHttpMethod.POST) {
            return empty(405)
        }

        val decoded = when (
            val result = ServiceStateWireJsonCodec.decodeRequest(request.body)
        ) {
            is ServiceStateWireDecodeResult.Decoded -> result.value
            ServiceStateWireDecodeResult.ProtocolFailure ->
                return rejected(400, ServiceStateWireFailure.INVALID_REQUEST)
        }

        if (decoded.protocolVersion != ServiceStateProtocolVersion(1)) {
            return rejected(400, ServiceStateWireFailure.INVALID_REQUEST)
        }

        when (val result = authentication.authenticate(request.authentication)) {
            RequestAuthenticationResult.Authenticated -> Unit
            is RequestAuthenticationResult.Rejected -> {
                return when (result.reason) {
                    RequestAuthenticationFailure.MISSING,
                    RequestAuthenticationFailure.INVALID ->
                        rejected(401, ServiceStateWireFailure.AUTHENTICATION_REQUIRED)

                    RequestAuthenticationFailure.UNAVAILABLE ->
                        rejected(503, ServiceStateWireFailure.INTERNAL_FAILURE)
                }
            }
        }

        return when (val result = service.issue(decoded.scope)) {
            is ServiceStateEvidenceResult.Issued ->
                wire(
                    status = 200,
                    response = ServiceStateWireResponse.Evidence(
                        wireVersion = ServiceStateWireJsonCodec.currentVersion,
                        envelope = result.envelope
                    )
                )

            ServiceStateEvidenceResult.StateUnavailable ->
                rejected(404, ServiceStateWireFailure.STATE_UNAVAILABLE)

            ServiceStateEvidenceResult.SigningKeyUnavailable ->
                rejected(503, ServiceStateWireFailure.SIGNING_KEY_UNAVAILABLE)

            ServiceStateEvidenceResult.SigningRejected,
            ServiceStateEvidenceResult.InternalFailure ->
                rejected(500, ServiceStateWireFailure.INTERNAL_FAILURE)
        }
    }

    private fun rejected(
        status: Int,
        reason: ServiceStateWireFailure
    ): LicenseHttpResponse =
        wire(
            status = status,
            response = ServiceStateWireResponse.Rejected(
                wireVersion = ServiceStateWireJsonCodec.currentVersion,
                reason = reason
            )
        )

    private fun wire(
        status: Int,
        response: ServiceStateWireResponse
    ): LicenseHttpResponse =
        LicenseHttpResponse(
            status = status,
            contentType = LicenseHttpEndpoint.JSON,
            body = ServiceStateWireJsonCodec.encodeResponse(response)
        )

    private fun empty(status: Int): LicenseHttpResponse =
        LicenseHttpResponse(status = status, contentType = null, body = byteArrayOf())

    override fun toString(): String =
        "AuthenticatedServiceStateHttpEndpoint(service=<redacted>,authentication=<redacted>)"

    companion object {
        const val PATH = "/v1/license/service-state"
    }
}

/**
 * Additive production router. Existing entitlement endpoint semantics are untouched.
 */
class LicensingHttpRouter(
    private val entitlement: AuthenticatedLicenseHttpEndpoint,
    private val serviceState: AuthenticatedServiceStateHttpEndpoint
) {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse =
        when (request.path) {
            LicenseHttpEndpoint.PATH -> entitlement.handle(request)
            AuthenticatedServiceStateHttpEndpoint.PATH -> serviceState.handle(request)
            else -> LicenseHttpResponse(status = 404, contentType = null, body = byteArrayOf())
        }

    override fun toString(): String =
        "LicensingHttpRouter(entitlement=<redacted>,serviceState=<redacted>)"
}
