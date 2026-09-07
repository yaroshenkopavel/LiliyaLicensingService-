package pro.liliya.licensing.openbao

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

data class OpenBaoTransitEndpoint(
    val address: String,
    val mount: String = "transit"
) {
    init {
        require(address.startsWith("http://") || address.startsWith("https://")) {
            "OpenBao address must use http or https"
        }
        require(mount.isNotBlank()) { "OpenBao transit mount must not be blank" }
        require('/' !in mount) { "OpenBao transit mount must be one path segment" }
    }

    val normalizedAddress: String = address.removeSuffix("/")

    override fun toString(): String =
        "OpenBaoTransitEndpoint(address=" + normalizedAddress + ",mount=" + mount + ")"
}

data class OpenBaoTransitKeyDescription(
    val type: String,
    val supportsSigning: Boolean,
    val availableVersions: Set<Int>
)

sealed interface OpenBaoTransitDescribeResult {
    data class Available(val description: OpenBaoTransitKeyDescription) :
        OpenBaoTransitDescribeResult
    data object Unavailable : OpenBaoTransitDescribeResult
    data object Failed : OpenBaoTransitDescribeResult
}

data class OpenBaoTransitSignature(
    val version: Int,
    val bytes: ByteArray
) {
    init {
        require(version > 0) { "OpenBao signature version must be positive" }
        require(bytes.isNotEmpty()) { "OpenBao signature must not be empty" }
    }
}

sealed interface OpenBaoTransitSignResult {
    data class Signed(val signature: OpenBaoTransitSignature) : OpenBaoTransitSignResult
    data object Rejected : OpenBaoTransitSignResult
    data object Unavailable : OpenBaoTransitSignResult
    data object Failed : OpenBaoTransitSignResult
}

sealed interface OpenBaoTransitPublicKeyResult {
    data class Available(val pem: String) : OpenBaoTransitPublicKeyResult
    data object Unavailable : OpenBaoTransitPublicKeyResult
    data object Failed : OpenBaoTransitPublicKeyResult
}

interface OpenBaoTransitClient {
    fun describeKey(keyName: String): OpenBaoTransitDescribeResult

    fun sign(
        keyName: String,
        keyVersion: Int,
        input: ByteArray
    ): OpenBaoTransitSignResult

    fun readPublicKeyPem(
        keyName: String,
        keyVersion: Int
    ): OpenBaoTransitPublicKeyResult
}

/**
 * OpenBao Transit HTTP adapter.
 *
 * Token material is supplied lazily and is never retained in a printable configuration object.
 */
class OpenBaoTransitHttpClient(
    private val endpoint: OpenBaoTransitEndpoint,
    private val tokenProvider: () -> String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val json: ObjectMapper = ObjectMapper()
) : OpenBaoTransitClient {

    override fun describeKey(keyName: String): OpenBaoTransitDescribeResult {
        val response = request(
            method = "GET",
            path = "/v1/" + endpoint.mount + "/keys/" + encodePath(keyName)
        ) ?: return OpenBaoTransitDescribeResult.Failed

        if (response.statusCode() == 404) return OpenBaoTransitDescribeResult.Unavailable
        if (response.statusCode() !in 200..299) return OpenBaoTransitDescribeResult.Failed

        return try {
            val data = json.readTree(response.body()).path("data")
            val type = data.path("type").asText("")
            val supportsSigning = data.path("supports_signing").asBoolean(false)
            val versions = data.path("keys")
                .fieldNames()
                .asSequence()
                .mapNotNull { it.toIntOrNull() }
                .toSet()

            if (type.isBlank() || versions.isEmpty()) {
                OpenBaoTransitDescribeResult.Failed
            } else {
                OpenBaoTransitDescribeResult.Available(
                    OpenBaoTransitKeyDescription(
                        type = type,
                        supportsSigning = supportsSigning,
                        availableVersions = versions
                    )
                )
            }
        } catch (_: RuntimeException) {
            OpenBaoTransitDescribeResult.Failed
        }
    }

    override fun sign(
        keyName: String,
        keyVersion: Int,
        input: ByteArray
    ): OpenBaoTransitSignResult {
        if (keyVersion <= 0 || input.isEmpty()) {
            return OpenBaoTransitSignResult.Rejected
        }

        val body = json.createObjectNode()
            .put("input", Base64.getEncoder().encodeToString(input))
            .put("key_version", keyVersion)
            .toString()

        val response = request(
            method = "POST",
            path = "/v1/" + endpoint.mount + "/sign/" +
                encodePath(keyName) + "/sha2-256",
            body = body
        ) ?: return OpenBaoTransitSignResult.Failed

        if (response.statusCode() == 404) return OpenBaoTransitSignResult.Unavailable
        if (response.statusCode() == 400) return OpenBaoTransitSignResult.Rejected
        if (response.statusCode() !in 200..299) return OpenBaoTransitSignResult.Failed

        return try {
            val encoded = json.readTree(response.body())
                .path("data")
                .path("signature")
                .asText("")
            parseSignature(encoded)
                ?.let(OpenBaoTransitSignResult::Signed)
                ?: OpenBaoTransitSignResult.Failed
        } catch (_: RuntimeException) {
            OpenBaoTransitSignResult.Failed
        }
    }

    override fun readPublicKeyPem(
        keyName: String,
        keyVersion: Int
    ): OpenBaoTransitPublicKeyResult {
        if (keyVersion <= 0) return OpenBaoTransitPublicKeyResult.Unavailable

        val response = request(
            method = "GET",
            path = "/v1/" + endpoint.mount + "/keys/" + encodePath(keyName)
        ) ?: return OpenBaoTransitPublicKeyResult.Failed

        if (response.statusCode() == 404) return OpenBaoTransitPublicKeyResult.Unavailable
        if (response.statusCode() !in 200..299) return OpenBaoTransitPublicKeyResult.Failed

        return try {
            val data = json.readTree(response.body()).path("data")
            val versionNode = data.path("keys").path(keyVersion.toString())
            val candidate = publicKeyFrom(versionNode)
                ?: data.path("public_key")
                    .takeIf(JsonNode::isTextual)
                    ?.asText()
                    ?.takeIf { it.contains("BEGIN PUBLIC KEY") }

            if (candidate == null) {
                OpenBaoTransitPublicKeyResult.Unavailable
            } else {
                OpenBaoTransitPublicKeyResult.Available(candidate)
            }
        } catch (_: RuntimeException) {
            OpenBaoTransitPublicKeyResult.Failed
        }
    }

    private fun publicKeyFrom(node: JsonNode): String? {
        if (node.isTextual) {
            return node.asText().takeIf { it.contains("BEGIN PUBLIC KEY") }
        }
        if (node.isObject) {
            return node.path("public_key")
                .takeIf(JsonNode::isTextual)
                ?.asText()
                ?.takeIf { it.contains("BEGIN PUBLIC KEY") }
        }
        return null
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null
    ): HttpResponse<String>? =
        try {
            val token = tokenProvider().takeIf { it.isNotBlank() } ?: return null
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.normalizedAddress + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Vault-Token", token)
                .header("Accept", "application/json")

            val request = if (method == "POST") {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
                    .build()
            } else {
                builder.GET().build()
            }

            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            null
        }

    private fun parseSignature(encoded: String): OpenBaoTransitSignature? {
        val parts = encoded.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "vault") return null
        val versionText = parts[1]
        if (!versionText.startsWith("v")) return null
        val version = versionText.removePrefix("v").toIntOrNull() ?: return null
        val bytes = try {
            Base64.getDecoder().decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        return OpenBaoTransitSignature(version, bytes)
    }

    private fun encodePath(value: String): String {
        require(value.isNotBlank()) { "OpenBao key name must not be blank" }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
