package pro.liliya.licensing.transport

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import pro.liliya.licensing.signing.SignedLicenseEnvelope

data class DeviceRebindWireRequest(
    val wireVersion: Int,
    val rebindCode: String,
    val attemptId: String,
    val installationId: String,
    val deviceKeyFingerprint: String
)

sealed interface DeviceRebindWireResponse {
    val wireVersion: Int

    data class Rebound(
        override val wireVersion: Int,
        val subject: String,
        val license: SignedLicenseEnvelope
    ) : DeviceRebindWireResponse

    data class Rejected(
        override val wireVersion: Int,
        val reason: String
    ) : DeviceRebindWireResponse
}

sealed interface DeviceRebindWireDecodeResult {
    data class Decoded(val value: DeviceRebindWireRequest) :
        DeviceRebindWireDecodeResult
    data object Rejected : DeviceRebindWireDecodeResult
}

object DeviceRebindWireJsonCodec {
    const val currentVersion = 1
    private val json = ObjectMapper()

    fun decodeRequest(bytes: ByteArray): DeviceRebindWireDecodeResult = try {
        val root = json.readTree(bytes)
        val version = root.path("wireVersion").asInt(-1)
        val code = root.path("rebindCode").asText("")
        val attemptId = root.path("attemptId").asText("")
        val installationId = root.path("installationId").asText("")
        val fingerprint = root.path("deviceKeyFingerprint").asText("")

        if (
            version != currentVersion ||
            code.isBlank() ||
            attemptId.isBlank() ||
            installationId.isBlank() ||
            fingerprint.isBlank()
        ) {
            DeviceRebindWireDecodeResult.Rejected
        } else {
            DeviceRebindWireDecodeResult.Decoded(
                DeviceRebindWireRequest(
                    wireVersion = version,
                    rebindCode = code,
                    attemptId = attemptId,
                    installationId = installationId,
                    deviceKeyFingerprint = fingerprint
                )
            )
        }
    } catch (_: Throwable) {
        DeviceRebindWireDecodeResult.Rejected
    }

    fun encodeResponse(response: DeviceRebindWireResponse): ByteArray {
        val root = json.createObjectNode()
            .put("wireVersion", response.wireVersion)

        when (response) {
            is DeviceRebindWireResponse.Rebound -> {
                root.put("kind", "rebound")
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
            is DeviceRebindWireResponse.Rejected -> {
                root.put("kind", "rejected")
                root.put("reason", response.reason)
            }
        }
        return json.writeValueAsBytes(root)
    }
}
