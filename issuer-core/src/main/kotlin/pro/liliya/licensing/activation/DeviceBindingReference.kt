package pro.liliya.licensing.activation

import java.security.MessageDigest
import java.util.Base64

object DeviceBindingReference {
    fun create(
        installationId: String,
        deviceKeyFingerprint: String
    ): String {
        require(installationId.isNotBlank())
        require(deviceKeyFingerprint.isNotBlank())

        val digest = MessageDigest.getInstance("SHA-256")
        val payload = buildString {
            append(installationId)
            append('\u0000')
            append(deviceKeyFingerprint)
        }.toByteArray(Charsets.UTF_8)

        val hash = try {
            digest.digest(payload)
        } finally {
            payload.fill(0)
        }

        return try {
            "device-binding-v1:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        } finally {
            hash.fill(0)
        }
    }
}
