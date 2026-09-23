package pro.liliya.licensing.http

import com.fasterxml.jackson.databind.ObjectMapper
import pro.liliya.licensing.activation.ActivationResult
import pro.liliya.licensing.activation.ActivationService

/**
 * TLS-only first-run activation endpoint.
 *
 * The one-time activation code is never logged or returned. On success, the newly generated
 * per-installation bearer credential is returned exactly once to the caller.
 */
class ActivationHttpEndpoint(
    private val service: ActivationService
) {
    private val json = ObjectMapper()

    fun handle(request: LicenseHttpRequest): LicenseHttpResponse {
        if (request.path != PATH) {
            return empty(404)
        }
        if (request.method != LicenseHttpMethod.POST) {
            return empty(405)
        }

        val activationCode = try {
            val root = json.readTree(request.body)
            if (!root.isObject) return rejected()
            val codeNode = root.get("activationCode") ?: return rejected()
            if (!codeNode.isTextual) return rejected()
            val code = codeNode.asText()
            if (code.isBlank()) return rejected()
            code.encodeToByteArray()
        } catch (_: Exception) {
            return rejected()
        }

        return try {
            when (val result = service.activate(activationCode)) {
                is ActivationResult.Activated -> {
                    val credential = result.credential.copyBytes()
                    try {
                        val root = json.createObjectNode()
                        root.put("kind", "activated")
                        root.put("productId", result.grant.productId)
                        root.put("credential", credential.toString(Charsets.UTF_8))
                        LicenseHttpResponse(
                            status = 200,
                            contentType = LicenseHttpEndpoint.JSON,
                            body = json.writeValueAsBytes(root)
                        )
                    } finally {
                        credential.fill(0)
                        result.credential.close()
                    }
                }

                ActivationResult.Rejected -> rejected()

                ActivationResult.Unavailable ->
                    empty(503)
            }
        } finally {
            activationCode.fill(0)
        }
    }

    private fun rejected(): LicenseHttpResponse {
        val root = json.createObjectNode()
        root.put("kind", "rejected")
        root.put("reason", "ACTIVATION_REQUIRED")
        return LicenseHttpResponse(
            status = 401,
            contentType = LicenseHttpEndpoint.JSON,
            body = json.writeValueAsBytes(root)
        )
    }

    private fun empty(status: Int): LicenseHttpResponse =
        LicenseHttpResponse(
            status = status,
            contentType = null,
            body = byteArrayOf()
        )

    override fun toString(): String =
        "ActivationHttpEndpoint(service=<redacted>)"

    companion object {
        const val PATH = "/v1/activate"
    }
}
