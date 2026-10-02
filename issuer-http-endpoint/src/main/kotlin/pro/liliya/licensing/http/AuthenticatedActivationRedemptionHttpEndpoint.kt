package pro.liliya.licensing.http

import java.time.Clock
import pro.liliya.licensing.activation.ActivationRedemptionRequest
import pro.liliya.licensing.activation.ActivationRedemptionResult
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.transport.ActivationWireDecodeResult
import pro.liliya.licensing.transport.ActivationWireJsonCodec
import pro.liliya.licensing.transport.ActivationWireResponse

class AuthenticatedActivationRedemptionHttpEndpoint(
    private val service: ActivationRedemptionService,
    private val authentication: RequestAuthenticationPort,
    private val clock: Clock = Clock.systemUTC()
) {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) return empty(404)
        if (request.method != LicenseHttpMethod.POST) return empty(405)

        val wire = when (val decoded = ActivationWireJsonCodec.decodeRequest(request.body)) {
            is ActivationWireDecodeResult.Decoded -> decoded.value
            ActivationWireDecodeResult.Rejected ->
                return response(400, ActivationWireResponse.Rejected(1, "INVALID_REQUEST"))
        }
        when (val auth = authentication.authenticate(request.authentication)) {
            RequestAuthenticationResult.Authenticated -> Unit
            is RequestAuthenticationResult.Rejected -> {
                return when (auth.reason) {
                    RequestAuthenticationFailure.MISSING,
                    RequestAuthenticationFailure.INVALID ->
                        response(401, ActivationWireResponse.Rejected(1, "AUTHENTICATION_REQUIRED"))
                    RequestAuthenticationFailure.UNAVAILABLE -> empty(503)
                }
            }
        }

        val result = try {
            service.redeem(
                ActivationRedemptionRequest(
                    activationCode = wire.activationCode,
                    attemptId = wire.attemptId
                ),
                clock.instant()
            )
        } catch (_: Throwable) {
            return empty(500)
        }

        return when (result) {
            is ActivationRedemptionResult.Activated ->
                response(200, ActivationWireResponse.Activated(1, result.subject))
            is ActivationRedemptionResult.IdempotentReplay ->
                response(200, ActivationWireResponse.Activated(1, result.subject))
            ActivationRedemptionResult.InvalidCode ->
                response(400, ActivationWireResponse.Rejected(1, "INVALID_CODE"))
            ActivationRedemptionResult.ExpiredCode ->
                response(410, ActivationWireResponse.Rejected(1, "EXPIRED_CODE"))
            ActivationRedemptionResult.CodeExhausted ->
                response(409, ActivationWireResponse.Rejected(1, "CODE_EXHAUSTED"))
            ActivationRedemptionResult.StoreUnavailable -> empty(503)
        }
    }

    private fun response(status: Int, body: ActivationWireResponse) =
        LicenseHttpResponse(
            status = status,
            contentType = LicenseHttpEndpoint.JSON,
            body = ActivationWireJsonCodec.encodeResponse(body)
        )

    private fun empty(status: Int) =
        LicenseHttpResponse(status, null, byteArrayOf())

    companion object {
        const val PATH = "/v1/activation/redeem"
    }
}
