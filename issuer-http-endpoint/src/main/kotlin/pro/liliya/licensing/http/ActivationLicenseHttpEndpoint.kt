package pro.liliya.licensing.http

import com.fasterxml.jackson.databind.ObjectMapper
import pro.liliya.licensing.activation.ActivationPreparationResult
import pro.liliya.licensing.activation.ActivationRedemptionService
import pro.liliya.licensing.issuer.LicensingIssuerCoordinator
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.transport.LicenseWireJsonCodec
import pro.liliya.licensing.transport.LicenseWireResponse

/**
 * First-run activation endpoint.
 *
 * The activation code is the only user-entered secret. A successful one-time redemption resolves
 * the exact subject/product server-side, so the client cannot use the code to mint entitlement for
 * a different identity or product.
 *
 * Activation != Entitlement. The issuer still resolves the authoritative entitlement from its
 * configured entitlement source and independently signs the resulting license.
 */
class ActivationLicenseHttpEndpoint(
    private val redemption: ActivationRedemptionService,
    private val processor: LicensingIssuerProcessor
) {
    constructor(
        redemption: ActivationRedemptionService,
        coordinator: LicensingIssuerCoordinator
    ) : this(redemption, LicensingIssuerProcessor(coordinator::process))

    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) return empty(404)
        if (request.method != LicenseHttpMethod.POST) return empty(405)

        val activation = decodeActivation(request.body)
            ?: return rejected(400, LicenseServiceFailure.INVALID_REQUEST)

        val prepared = when (
            val result = redemption.prepare(
                rawCode = activation.code,
                requestId = activation.requestId,
                installId = activation.installId,
                installSecret = activation.installSecret
            )
        ) {
            is ActivationPreparationResult.Accepted -> result.grant
            is ActivationPreparationResult.Completed ->
                return LicenseHttpResponse(
                    status = 200,
                    contentType = LicenseHttpEndpoint.JSON,
                    body = result.responseBody
                )
            ActivationPreparationResult.Invalid,
            ActivationPreparationResult.Expired,
            ActivationPreparationResult.AlreadyRedeemed ->
                return rejected(401, LicenseServiceFailure.AUTHENTICATION_REQUIRED)
            ActivationPreparationResult.InProgress ->
                return rejected(409, LicenseServiceFailure.IDEMPOTENCY_CONFLICT)
            ActivationPreparationResult.Unavailable ->
                return rejected(503, LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE)
        }

        val issueRequest = LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = prepared.productId,
            subjectReference = prepared.subject,
            requestId = activation.requestId
        )

        return when (val result = try {
            processor.process(issueRequest)
        } catch (_: Exception) {
            null
        }) {
            is LicensingIssuerResult.Issued -> {
                val response = LicenseWireJsonCodec.encodeResponse(
                    LicenseWireResponse.SignedSuccess(
                        wireVersion = LicenseWireJsonCodec.currentVersion,
                        envelope = result.envelope
                    )
                )
                if (!redemption.complete(prepared, response)) {
                    rejected(503, LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE)
                } else {
                    LicenseHttpResponse(
                        status = 200,
                        contentType = LicenseHttpEndpoint.JSON,
                        body = response
                    )
                }
            }
            is LicensingIssuerResult.Rejected ->
                rejected(statusFor(result.reason), result.reason)
            null -> empty(500)
        }
    }

    private fun decodeActivation(body: ByteArray): ActivationRequest? {
        return try {
            val root = JSON.readTree(body)
            if (!root.isObject) return null
            if (root.path("wireVersion").asInt(-1) != 1) return null
            if (root.path("kind").asText("") != "activate") return null
            val code = root.path("activationCode")
            val requestId = root.path("activationRequestId")
            val installId = root.path("installId")
            val installSecret = root.path("installSecret")
            if (
                !code.isTextual ||
                !requestId.isTextual ||
                !installId.isTextual ||
                !installSecret.isTextual
            ) return null
            val codeValue = code.asText()
            val requestIdValue = requestId.asText()
            val installIdValue = installId.asText()
            val installSecretValue = installSecret.asText()
            if (codeValue.isBlank() || codeValue.length > 128) return null
            if (requestIdValue.isBlank() || requestIdValue.length > 128) return null
            if (installIdValue.length !in 8..128) return null
            if (installSecretValue.length !in 32..256) return null
            ActivationRequest(
                code = codeValue,
                requestId = requestIdValue,
                installId = installIdValue,
                installSecret = installSecretValue
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class ActivationRequest(
        val code: String,
        val requestId: String,
        val installId: String,
        val installSecret: String
    ) {
        override fun toString(): String =
            "ActivationRequest(code=<redacted>,requestId=<redacted>," +
                "installId=<redacted>,installSecret=<redacted>)"
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

    private fun rejected(status: Int, reason: LicenseServiceFailure): LicenseHttpResponse =
        wire(
            status,
            LicenseWireResponse.ServiceRejected(
                wireVersion = LicenseWireJsonCodec.currentVersion,
                reason = reason
            )
        )

    private fun wire(status: Int, response: LicenseWireResponse): LicenseHttpResponse =
        LicenseHttpResponse(
            status = status,
            contentType = LicenseHttpEndpoint.JSON,
            body = LicenseWireJsonCodec.encodeResponse(response)
        )

    private fun empty(status: Int): LicenseHttpResponse =
        LicenseHttpResponse(status, null, byteArrayOf())

    companion object {
        const val PATH = "/v1/activate"
        private val JSON = ObjectMapper()
    }
}
