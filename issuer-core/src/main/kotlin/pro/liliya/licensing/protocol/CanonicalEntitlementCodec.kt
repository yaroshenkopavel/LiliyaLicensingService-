package pro.liliya.licensing.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Exact S5.1 encoding compatibility seam for frozen LiliyaCore LicenseEntitlementCanonicalCodec.
 *
 * Format is intentionally duplicated here rather than adding a binary dependency from the backend
 * to the Android/client repository. Compatibility is locked by fixed-vector tests.
 */
object CanonicalEntitlementCodec {
    private const val MAGIC = 0x4C494331

    fun encode(entitlement: CanonicalLicenseEntitlement): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(entitlement.id)
                data.writeString(entitlement.subject)
                data.writeString(entitlement.productId)
                val features = entitlement.features.sorted()
                data.writeInt(features.size)
                features.forEach { data.writeString(it) }
                data.writeLong(entitlement.version)
                data.writeString(entitlement.signingKeyId)
                data.writeInstant(entitlement.issuedAt)
                data.writeInstant(entitlement.notBefore)
                data.writeNullableInstant(entitlement.expiresAt)
                data.writeNullableInstant(entitlement.offlineLeaseUntil)
                data.writeLong(entitlement.revocationEpoch)
                data.writeBoolean(entitlement.replaySequence != null)
                entitlement.replaySequence?.let { data.writeLong(it) }
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataOutputStream.writeNullableInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) writeInstant(value)
    }
}
