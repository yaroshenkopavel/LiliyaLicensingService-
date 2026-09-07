package pro.liliya.licensing.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import pro.liliya.licensing.servicestate.ServiceStateAuthenticationProof
import pro.liliya.licensing.servicestate.ServiceStateEnvelope
import pro.liliya.licensing.servicestate.ServiceStateEvidenceProfile
import pro.liliya.licensing.servicestate.ServiceStateEvidencePurpose
import pro.liliya.licensing.servicestate.ServiceStateOpaquePayload
import pro.liliya.licensing.servicestate.ServiceStateProtocolVersion
import pro.liliya.licensing.servicestate.ServiceStateScope
import pro.liliya.licensing.servicestate.ServiceStateSigningKeyId

@JvmInline
value class ServiceStateWireVersion(val value: Int) {
    init {
        require(value > 0) { "service-state wire version must be positive" }
    }
}

data class ServiceStateWireRequest(
    val wireVersion: ServiceStateWireVersion,
    val protocolVersion: ServiceStateProtocolVersion,
    val scope: ServiceStateScope,
    val requestId: String
) {
    init {
        require(requestId.isNotBlank()) { "service-state requestId must not be blank" }
    }

    override fun toString(): String =
        "ServiceStateWireRequest(wireVersion=" + wireVersion.value +
            ",protocolVersion=" + protocolVersion.value +
            ",scope=" + scope +
            ",requestId=<redacted>)"
}

enum class ServiceStateWireFailure {
    INVALID_REQUEST,
    AUTHENTICATION_REQUIRED,
    STATE_UNAVAILABLE,
    SIGNING_KEY_UNAVAILABLE,
    INTERNAL_FAILURE
}

sealed interface ServiceStateWireResponse {
    val wireVersion: ServiceStateWireVersion

    data class Evidence(
        override val wireVersion: ServiceStateWireVersion,
        val envelope: ServiceStateEnvelope
    ) : ServiceStateWireResponse

    data class Rejected(
        override val wireVersion: ServiceStateWireVersion,
        val reason: ServiceStateWireFailure
    ) : ServiceStateWireResponse
}

sealed interface ServiceStateWireDecodeResult<out T> {
    data class Decoded<T>(val value: T) : ServiceStateWireDecodeResult<T>
    data object ProtocolFailure : ServiceStateWireDecodeResult<Nothing>
}

object ServiceStateWireJsonCodec {
    private val json = ObjectMapper()
    val currentVersion = ServiceStateWireVersion(1)

    fun encodeRequest(request: ServiceStateWireRequest): ByteArray {
        val root = json.createObjectNode()
        root.put("wireVersion", request.wireVersion.value)
        root.put("kind", "service-state-request")
        root.put("protocolVersion", request.protocolVersion.value)
        root.put("productId", request.scope.productId)
        root.put("subjectReference", request.scope.subject)
        root.put("requestId", request.requestId)
        return json.writeValueAsBytes(root)
    }

    fun decodeRequest(bytes: ByteArray): ServiceStateWireDecodeResult<ServiceStateWireRequest> =
        try {
            val root = json.readTree(bytes)
            if (
                !root.isObject ||
                requiredInt(root, "wireVersion") != currentVersion.value ||
                requiredText(root, "kind") != "service-state-request"
            ) {
                ServiceStateWireDecodeResult.ProtocolFailure
            } else {
                val protocolVersion = requiredLong(root, "protocolVersion")
                if (protocolVersion <= 0L) {
                    ServiceStateWireDecodeResult.ProtocolFailure
                } else {
                    ServiceStateWireDecodeResult.Decoded(
                        ServiceStateWireRequest(
                            wireVersion = currentVersion,
                            protocolVersion = ServiceStateProtocolVersion(protocolVersion),
                            scope = ServiceStateScope(
                                productId = requiredText(root, "productId"),
                                subject = requiredText(root, "subjectReference")
                            ),
                            requestId = requiredText(root, "requestId")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            ServiceStateWireDecodeResult.ProtocolFailure
        }

    fun encodeResponse(response: ServiceStateWireResponse): ByteArray {
        val root = json.createObjectNode()
        root.put("wireVersion", response.wireVersion.value)
        when (response) {
            is ServiceStateWireResponse.Evidence -> {
                root.put("kind", "service-state")
                root.put("protocolVersion", response.envelope.protocolVersion.value)
                root.put("purpose", response.envelope.purpose.name)
                root.put("profile", response.envelope.profile.value)
                root.put("signingKeyId", response.envelope.signingKeyId.value)
                root.put(
                    "payloadBase64",
                    Base64.getEncoder().encodeToString(
                        response.envelope.payload.copyBytes()
                    )
                )
                root.put(
                    "proofBase64",
                    Base64.getEncoder().encodeToString(
                        response.envelope.proof.copyBytes()
                    )
                )
            }
            is ServiceStateWireResponse.Rejected -> {
                root.put("kind", "rejected")
                root.put("reason", response.reason.name)
            }
        }
        return json.writeValueAsBytes(root)
    }

    fun decodeResponse(bytes: ByteArray): ServiceStateWireDecodeResult<ServiceStateWireResponse> {
        return try {
            val root = json.readTree(bytes)
            val wireVersion = requiredInt(root, "wireVersion")
            if (wireVersion != currentVersion.value) {
                return ServiceStateWireDecodeResult.ProtocolFailure
            }

            when (requiredText(root, "kind")) {
                "service-state" -> {
                    val purpose = try {
                        ServiceStateEvidencePurpose.valueOf(requiredText(root, "purpose"))
                    } catch (_: IllegalArgumentException) {
                        return ServiceStateWireDecodeResult.ProtocolFailure
                    }
                    val payload = Base64.getDecoder().decode(
                        requiredText(root, "payloadBase64")
                    )
                    val proof = Base64.getDecoder().decode(
                        requiredText(root, "proofBase64")
                    )
                    ServiceStateWireDecodeResult.Decoded(
                        ServiceStateWireResponse.Evidence(
                            wireVersion = currentVersion,
                            envelope = ServiceStateEnvelope(
                                protocolVersion = ServiceStateProtocolVersion(
                                    requiredLong(root, "protocolVersion")
                                ),
                                purpose = purpose,
                                profile = ServiceStateEvidenceProfile(
                                    requiredText(root, "profile")
                                ),
                                signingKeyId = ServiceStateSigningKeyId(
                                    requiredText(root, "signingKeyId")
                                ),
                                payload = ServiceStateOpaquePayload.of(payload),
                                proof = ServiceStateAuthenticationProof.of(proof)
                            )
                        )
                    )
                }
                "rejected" -> {
                    val reason = try {
                        ServiceStateWireFailure.valueOf(requiredText(root, "reason"))
                    } catch (_: IllegalArgumentException) {
                        return ServiceStateWireDecodeResult.ProtocolFailure
                    }
                    ServiceStateWireDecodeResult.Decoded(
                        ServiceStateWireResponse.Rejected(
                            wireVersion = currentVersion,
                            reason = reason
                        )
                    )
                }
                else -> ServiceStateWireDecodeResult.ProtocolFailure
            }
        } catch (_: Exception) {
            ServiceStateWireDecodeResult.ProtocolFailure
        }
    }

    private fun requiredText(root: JsonNode, name: String): String {
        val node = root.get(name) ?: error("missing " + name)
        require(node.isTextual)
        val value = node.asText()
        require(value.isNotBlank())
        return value
    }

    private fun requiredInt(root: JsonNode, name: String): Int {
        val node = root.get(name) ?: error("missing " + name)
        require(node.canConvertToInt())
        return node.intValue()
    }

    private fun requiredLong(root: JsonNode, name: String): Long {
        val node = root.get(name) ?: error("missing " + name)
        require(node.isIntegralNumber)
        return node.longValue()
    }
}
