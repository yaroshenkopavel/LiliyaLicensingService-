package pro.liliya.licensing.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequestCreationResult
import pro.liliya.licensing.protocol.LicenseServiceRequestFactory
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

object LicenseWireJsonCodec {
    private val json = ObjectMapper()
    val currentVersion = LicenseWireProtocolVersion(1)

    fun encodeRequest(request: LicenseWireRequest.ServiceRequest): ByteArray {
        val root = json.createObjectNode()
        root.put("wireVersion", request.wireVersion.value)
        root.put("kind", "request")
        root.put("protocolVersion", request.request.protocolVersion.value)
        root.put("operation", request.request.operation.name)
        root.put("productId", request.request.productId)
        root.put("subjectReference", request.request.subjectReference)
        request.request.requestId?.let { root.put("requestId", it) }
        request.request.enrollmentReference?.let { root.put("enrollmentReference", it) }
        return json.writeValueAsBytes(root)
    }

    fun decodeRequest(bytes: ByteArray): LicenseWireDecodeResult<LicenseWireRequest.ServiceRequest> {
        return try {
            val root = json.readTree(bytes)
            if (!validWireRoot(root, "request")) {
                return LicenseWireDecodeResult.Rejected(
                    LicenseTransportFailure.PROTOCOL_FAILURE
                )
            }
            val created = LicenseServiceRequestFactory.create(
                protocolVersion = requiredInt(root, "protocolVersion"),
                operation = requiredText(root, "operation"),
                productId = requiredText(root, "productId"),
                subjectReference = requiredText(root, "subjectReference"),
                requestId = optionalText(root, "requestId"),
                enrollmentReference = optionalText(root, "enrollmentReference")
            )
            when (created) {
                is LicenseServiceRequestCreationResult.Created ->
                    LicenseWireDecodeResult.Decoded(
                        LicenseWireRequest.ServiceRequest(
                            wireVersion = currentVersion,
                            request = created.request
                        )
                    )
                is LicenseServiceRequestCreationResult.Rejected ->
                    LicenseWireDecodeResult.Rejected(
                        LicenseTransportFailure.INVALID_LOCAL_REQUEST
                    )
            }
        } catch (_: Exception) {
            LicenseWireDecodeResult.Rejected(LicenseTransportFailure.PROTOCOL_FAILURE)
        }
    }

    fun encodeResponse(response: LicenseWireResponse): ByteArray {
        val root = json.createObjectNode()
        root.put("wireVersion", response.wireVersion.value)
        when (response) {
            is LicenseWireResponse.SignedSuccess -> {
                root.put("kind", "success")
                root.put("schemaVersion", response.envelope.schemaVersion.value)
                root.put("algorithm", response.envelope.algorithm.value)
                root.put("keyReference", response.envelope.keyReference.value)
                root.put(
                    "payloadBase64",
                    Base64.getEncoder().encodeToString(
                        response.envelope.copyCanonicalPayload()
                    )
                )
                root.put(
                    "signatureBase64",
                    Base64.getEncoder().encodeToString(
                        response.envelope.copySignature()
                    )
                )
            }
            is LicenseWireResponse.ServiceRejected -> {
                root.put("kind", "rejected")
                root.put("reason", response.reason.name)
            }
        }
        return json.writeValueAsBytes(root)
    }

    fun decodeResponse(bytes: ByteArray): LicenseWireDecodeResult<LicenseWireResponse> {
        return try {
            val root = json.readTree(bytes)
            val wireVersion = requiredInt(root, "wireVersion")
            if (wireVersion != currentVersion.value) {
                return LicenseWireDecodeResult.Rejected(
                    LicenseTransportFailure.PROTOCOL_FAILURE
                )
            }
            when (requiredText(root, "kind")) {
                "success" -> {
                    val payload = Base64.getDecoder().decode(
                        requiredText(root, "payloadBase64")
                    )
                    val signature = Base64.getDecoder().decode(
                        requiredText(root, "signatureBase64")
                    )
                    LicenseWireDecodeResult.Decoded(
                        LicenseWireResponse.SignedSuccess(
                            wireVersion = currentVersion,
                            envelope = SignedLicenseEnvelope(
                                schemaVersion = SigningEnvelopeSchemaVersion(
                                    requiredLong(root, "schemaVersion")
                                ),
                                algorithm = SigningAlgorithm(
                                    requiredText(root, "algorithm")
                                ),
                                keyReference = SigningKeyReference(
                                    requiredText(root, "keyReference")
                                ),
                                canonicalPayload = payload,
                                signature = signature
                            )
                        )
                    )
                }
                "rejected" -> {
                    val reason = LicenseServiceFailure.valueOf(
                        requiredText(root, "reason")
                    )
                    LicenseWireDecodeResult.Decoded(
                        LicenseWireResponse.ServiceRejected(
                            wireVersion = currentVersion,
                            reason = reason
                        )
                    )
                }
                else -> LicenseWireDecodeResult.Rejected(
                    LicenseTransportFailure.PROTOCOL_FAILURE
                )
            }
        } catch (_: Exception) {
            LicenseWireDecodeResult.Rejected(LicenseTransportFailure.PROTOCOL_FAILURE)
        }
    }

    private fun validWireRoot(root: JsonNode, kind: String): Boolean =
        root.isObject &&
            root.path("wireVersion").asInt(-1) == currentVersion.value &&
            root.path("kind").asText("") == kind

    private fun requiredText(root: JsonNode, name: String): String {
        val node = root.get(name) ?: error("missing " + name)
        require(node.isTextual)
        val value = node.asText()
        require(value.isNotBlank())
        return value
    }

    private fun optionalText(root: JsonNode, name: String): String? {
        val node = root.get(name) ?: return null
        if (node.isNull) return null
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
