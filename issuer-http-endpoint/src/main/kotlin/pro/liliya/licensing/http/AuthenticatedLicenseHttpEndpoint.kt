package pro.liliya.licensing.http

import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse

/**
 * Production authentication wrapper for the frozen/thin licensing HTTP endpoint.
 *
 * Authentication is checked only after route/method and wire-shape validation, and always before
 * the issuer processor can be invoked. Authentication does not become part of the frozen wire DTO
 * or entitlement semantics.
 */
class AuthenticatedLicenseHttpEndpoint(
    private val delegate: LicenseHttpEndpoint,
    private val authentication: RequestAuthenticationPort
) {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != LicenseHttpEndpoint.PATH) {
            return delegate.handle(request)
        }

        if (request.method != LicenseHttpMethod.POST) {
            return delegate.handle(request)
        }

        when (LicenseWireJsonCodec.decodeRequest(request.body)) {
            is LicenseWireDecodeResult.Decoded -> Unit
            is LicenseWireDecodeResult.Rejected -> return delegate.handle(request)
        }

        return when (val result = authentication.authenticate(request.authentication)) {
            RequestAuthenticationResult.Authenticated ->
                delegate.handle(request)

            is RequestAuthenticationResult.Rejected ->
                when (result.reason) {
                    RequestAuthenticationFailure.MISSING,
                    RequestAuthenticationFailure.INVALID ->
                        LicenseHttpResponse(
                            status = 401,
                            contentType = LicenseHttpEndpoint.JSON,
                            body = LicenseWireJsonCodec.encodeResponse(
                                LicenseWireResponse.ServiceRejected(
                                    wireVersion = LicenseWireJsonCodec.currentVersion,
                                    reason = LicenseServiceFailure.AUTHENTICATION_REQUIRED
                                )
                            )
                        )

                    RequestAuthenticationFailure.UNAVAILABLE ->
                        LicenseHttpResponse(
                            status = 503,
                            contentType = null,
                            body = byteArrayOf()
                        )
                }
        }
    }

    override fun toString(): String =
        "AuthenticatedLicenseHttpEndpoint(delegate=<redacted>,authentication=<redacted>)"
}
