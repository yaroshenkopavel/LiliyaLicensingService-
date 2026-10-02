package pro.liliya.licensing.http

import java.time.Clock
import pro.liliya.licensing.activation.ActivationRedemptionRequest
import pro.liliya.licensing.activation.ActivationRedemptionResult
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.ActivationWireDecodeResult
import pro.liliya.licensing.transport.ActivationWireJsonCodec
import pro.liliya.licensing.transport.ActivationWireResponse

/**
 * Fresh-install activation endpoint.
 *
 * A valid signed Activation Code is the one-time activation capability.
 * Product Auth is intentionally not required before first activation.
 * HTTPS server trust + Activation Code signature protect this bootstrap boundary.
 */
class ActivationRedemptionHttpEndpoint(
    private val service: ActivationRedemptionService,
    private val issuer: LicensingIssuerProcessor,
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

        val redemption = try {
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

        return when (redemption) {
            is ActivationRedemptionResult.Activated ->
                issueLicense(
                    subject = redemption.subject,
                    productId = redemption.claims.productId,
                    attemptId = wire.attemptId
                )

            is ActivationRedemptionResult.IdempotentReplay ->
                issueLicense(
                    subject = redemption.subject,
                    productId = redemption.claims.productId,
                    attemptId = wire.attemptId
                )

            ActivationRedemptionResult.InvalidCode ->
                response(400, ActivationWireResponse.Rejected(1, "INVALID_CODE"))

            ActivationRedemptionResult.ExpiredCode ->
                response(410, ActivationWireResponse.Rejected(1, "EXPIRED_CODE"))

            ActivationRedemptionResult.CodeExhausted ->
                response(409, ActivationWireResponse.Rejected(1, "CODE_EXHAUSTED"))

            ActivationRedemptionResult.StoreUnavailable ->
                empty(503)
        }
    }

    private fun issueLicense(
        subject: String,
        productId: String,
        attemptId: String
    ): LicenseHttpResponse {
        val result = try {
            issuer.process(
                LicenseServiceRequest(
                    protocolVersion = LicenseProtocolVersion(1),
                    operation = LicenseOperation.ISSUE,
                    productId = productId,
                    subjectReference = subject,
                    requestId = attemptId
                )
            )
        } catch (_: Throwable) {
            return empty(500)
        }

        return when (result) {
            is LicensingIssuerResult.Issued ->
                response(
                    200,
                    ActivationWireResponse.Activated(
                        wireVersion = 1,
                        subject = subject,
                        license = result.envelope
                    )
                )

            is LicensingIssuerResult.Rejected ->
                response(
                    503,
                    ActivationWireResponse.Rejected(
                        wireVersion = 1,
                        reason = "LICENSE_ISSUANCE_FAILED"
                    )
                )
        }
    }

    private fun response(
        status: Int,
        body: ActivationWireResponse
    ) = LicenseHttpResponse(
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
