package pro.liliya.licensing.http

import pro.liliya.licensing.issuer.LicensingIssuerCoordinator
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.LicenseWireDecodeResult
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse

enum class LicenseHttpMethod {
    GET,
    POST
}

data class LicenseHttpRequest(
    val method: LicenseHttpMethod,
    val path: String,
    val body: ByteArray
) {
    override fun toString(): String =
        "LicenseHttpRequest(method=" + method +
            ",path=" + path +
            ",body=<redacted>)"
}

data class LicenseHttpResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray
) {
    init {
        require(status in 100..599) { "invalid HTTP status" }
    }

    override fun toString(): String =
        "LicenseHttpResponse(status=" + status +
            ",contentType=" + contentType +
            ",body=<redacted>)"
}

fun interface LicensingIssuerProcessor {
    fun process(request: LicenseServiceRequest): LicensingIssuerResult
}

class LicenseHttpEndpoint(
    private val processor: LicensingIssuerProcessor
) {
    constructor(coordinator: LicensingIssuerCoordinator) :
        this(LicensingIssuerProcessor(coordinator::process))

    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) {
            return empty(status = 404)
        }

        if (request.method != LicenseHttpMethod.POST) {
            return empty(status = 405)
        }

        val wireRequest = when (
            val decoded = LicenseWireJsonCodec.decodeRequest(request.body)
        ) {
            is LicenseWireDecodeResult.Decoded -> decoded.value
            is LicenseWireDecodeResult.Rejected -> {
                return wire(
                    status = 400,
                    response = LicenseWireResponse.ServiceRejected(
                        wireVersion = LicenseWireJsonCodec.currentVersion,
                        reason = LicenseServiceFailure.INVALID_REQUEST
                    )
                )
            }
        }

        val result = try {
            processor.process(wireRequest.request)
        } catch (_: Exception) {
            return empty(status = 500)
        }

        return when (result) {
            is LicensingIssuerResult.Issued ->
                wire(
                    status = 200,
                    response = LicenseWireResponse.SignedSuccess(
                        wireVersion = LicenseWireJsonCodec.currentVersion,
                        envelope = result.envelope
                    )
                )

            is LicensingIssuerResult.Rejected ->
                wire(
                    status = statusFor(result.reason),
                    response = LicenseWireResponse.ServiceRejected(
                        wireVersion = LicenseWireJsonCodec.currentVersion,
                        reason = result.reason
                    )
                )
        }
    }

    private fun statusFor(reason: LicenseServiceFailure): Int =
        when (reason) {
            LicenseServiceFailure.INVALID_REQUEST,
            LicenseServiceFailure.UNSUPPORTED_PROTOCOL -> 400

            LicenseServiceFailure.AUTHENTICATION_REQUIRED -> 401

            LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE,
            LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE,
            LicenseServiceFailure.ENROLLMENT_REQUIRED,
            LicenseServiceFailure.ENROLLMENT_REJECTED,
            LicenseServiceFailure.DEVICE_PROOF_REJECTED -> 403

            LicenseServiceFailure.REFRESH_REJECTED,
            LicenseServiceFailure.REPLAY_CONFLICT,
            LicenseServiceFailure.REVOCATION_CONFLICT,
            LicenseServiceFailure.IDEMPOTENCY_CONFLICT -> 409

            LicenseServiceFailure.SIGNING_KEY_UNAVAILABLE,
            LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE -> 503

            LicenseServiceFailure.INTERNAL_FAILURE -> 500
        }

    private fun wire(
        status: Int,
        response: LicenseWireResponse
    ): LicenseHttpResponse =
        LicenseHttpResponse(
            status = status,
            contentType = JSON,
            body = LicenseWireJsonCodec.encodeResponse(response)
        )

    private fun empty(status: Int): LicenseHttpResponse =
        LicenseHttpResponse(
            status = status,
            contentType = null,
            body = byteArrayOf()
        )

    companion object {
        const val PATH = "/v1/license"
        const val JSON = "application/json"
    }
}
