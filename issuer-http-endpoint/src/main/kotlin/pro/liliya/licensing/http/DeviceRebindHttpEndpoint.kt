package pro.liliya.licensing.http

import java.time.Clock
import pro.liliya.licensing.activation.DeviceBindingReference
import pro.liliya.licensing.activation.DeviceRebindRequest
import pro.liliya.licensing.activation.DeviceRebindResult
import pro.liliya.licensing.activation.DeviceRebindService
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.DeviceRebindWireDecodeResult
import pro.liliya.licensing.transport.DeviceRebindWireJsonCodec
import pro.liliya.licensing.transport.DeviceRebindWireResponse

class DeviceRebindHttpEndpoint(
    private val service: DeviceRebindService,
    private val issuer: LicensingIssuerProcessor,
    private val clock: Clock = Clock.systemUTC()
) {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) return empty(404)
        if (request.method != LicenseHttpMethod.POST) return empty(405)

        val wire = when (
            val decoded = DeviceRebindWireJsonCodec.decodeRequest(request.body)
        ) {
            is DeviceRebindWireDecodeResult.Decoded -> decoded.value
            DeviceRebindWireDecodeResult.Rejected ->
                return response(
                    400,
                    DeviceRebindWireResponse.Rejected(1, "INVALID_REQUEST")
                )
        }

        val result = try {
            service.rebind(
                DeviceRebindRequest(
                    rebindCode = wire.rebindCode,
                    attemptId = wire.attemptId,
                    installationId = wire.installationId,
                    deviceKeyFingerprint = wire.deviceKeyFingerprint
                ),
                clock.instant()
            )
        } catch (_: Throwable) {
            return empty(500)
        }

        return when (result) {
            is DeviceRebindResult.Rebound ->
                issueLicense(
                    subject = result.subject,
                    productId = result.productId,
                    attemptId = wire.attemptId,
                    installationId = wire.installationId,
                    deviceKeyFingerprint = wire.deviceKeyFingerprint
                )

            is DeviceRebindResult.IdempotentReplay ->
                issueLicense(
                    subject = result.subject,
                    productId = result.productId,
                    attemptId = wire.attemptId,
                    installationId = wire.installationId,
                    deviceKeyFingerprint = wire.deviceKeyFingerprint
                )

            DeviceRebindResult.InvalidCode ->
                response(400, DeviceRebindWireResponse.Rejected(1, "INVALID_CODE"))

            DeviceRebindResult.ExpiredCode ->
                response(410, DeviceRebindWireResponse.Rejected(1, "EXPIRED_CODE"))

            DeviceRebindResult.CodeExhausted ->
                response(409, DeviceRebindWireResponse.Rejected(1, "CODE_EXHAUSTED"))

            DeviceRebindResult.DeviceLimitReached ->
                response(
                    409,
                    DeviceRebindWireResponse.Rejected(1, "DEVICE_LIMIT_REACHED")
                )

            DeviceRebindResult.ReplacementStateChanged ->
                response(
                    409,
                    DeviceRebindWireResponse.Rejected(1, "REPLACEMENT_STATE_CHANGED")
                )

            DeviceRebindResult.EntitlementUnavailable ->
                response(
                    409,
                    DeviceRebindWireResponse.Rejected(1, "ENTITLEMENT_UNAVAILABLE")
                )

            DeviceRebindResult.StoreUnavailable -> empty(503)
        }
    }

    private fun issueLicense(
        subject: String,
        productId: String,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String
    ): LicenseHttpResponse {
        val bindingReference = DeviceBindingReference.create(
            installationId = installationId,
            deviceKeyFingerprint = deviceKeyFingerprint
        )

        val result = try {
            issuer.process(
                LicenseServiceRequest(
                    protocolVersion = LicenseProtocolVersion(1),
                    operation = LicenseOperation.ISSUE,
                    productId = productId,
                    subjectReference = subject,
                    requestId = attemptId,
                    enrollmentReference = bindingReference
                )
            )
        } catch (_: Throwable) {
            return empty(500)
        }

        return when (result) {
            is LicensingIssuerResult.Issued ->
                response(
                    200,
                    DeviceRebindWireResponse.Rebound(
                        wireVersion = 1,
                        subject = subject,
                        license = result.envelope
                    )
                )

            is LicensingIssuerResult.Rejected ->
                response(
                    503,
                    DeviceRebindWireResponse.Rejected(
                        wireVersion = 1,
                        reason = "LICENSE_ISSUANCE_FAILED"
                    )
                )
        }
    }

    private fun response(
        status: Int,
        body: DeviceRebindWireResponse
    ) = LicenseHttpResponse(
        status = status,
        contentType = LicenseHttpEndpoint.JSON,
        body = DeviceRebindWireJsonCodec.encodeResponse(body)
    )

    private fun empty(status: Int) =
        LicenseHttpResponse(status, null, byteArrayOf())

    companion object {
        const val PATH = "/v1/activation/rebind"
    }
}
