package pro.liliya.licensing.transport

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import pro.liliya.licensing.signing.SignedLicenseEnvelope

data class ActivationWireRequest(
    val wireVersion: Int,
    val activationCode: String,
    val attemptId: String
)

sealed interface ActivationWireResponse {
    val wireVersion: Int

    data class Activated(
        override val wireVersion: Int,
        val subject: String,
        val license: SignedLicenseEnvelope
    ) : ActivationWireResponse

    data class Rejected(
        override val wireVersion: Int,
        val reason: String
    ) : ActivationWireResponse
}

sealed interface ActivationWireDecodeResult {
    data class Decoded(val value: ActivationWireRequest) : ActivationWireDecodeResult
    data object Rejected : ActivationWireDecodeResult
}

object ActivationWireJsonCodec {
    const val currentVersion = 1
    private val json = ObjectMapper()

    fun decodeRequest(bytes: ByteArray): ActivationWireDecodeResult = try {
        val root = json.readTree(bytes)
        val version = root.path("wireVersion").asInt(-1)
        val code = root.path("activationCode").asText("")
        val attemptId = root.path("attemptId").asText("")
        if (
            version != currentVersion ||
            code.isBlank() ||
            attemptId.isBlank()
        ) {
            ActivationWireDecodeResult.Rejected
        } else {
            ActivationWireDecodeResult.Decoded(
                ActivationWireRequest(version, code, attemptId)
            )
        }
    } catch (_: Throwable) {
        ActivationWireDecodeResult.Rejected
    }

    fun encodeResponse(response: ActivationWireResponse): ByteArray {
        val root = json.createObjectNode()
            .put("wireVersion", response.wireVersion)

        when (response) {
            is ActivationWireResponse.Activated -> {
                root.put("kind", "activated")
                root.put("subject", response.subject)
                root.put("schemaVersion", response.license.schemaVersion.value)
                root.put("algorithm", response.license.algorithm.value)
                root.put("keyReference", response.license.keyReference.value)
                root.put(
                    "payloadBase64",
                    Base64.getEncoder().encodeToString(
                        response.license.copyCanonicalPayload()
                    )
                )
                root.put(
                    "signatureBase64",
                    Base64.getEncoder().encodeToString(
                        response.license.copySignature()
                    )
                )
            }

            is ActivationWireResponse.Rejected -> {
                root.put("kind", "rejected")
                root.put("reason", response.reason)
            }
        }
        return json.writeValueAsBytes(root)
    }
}
