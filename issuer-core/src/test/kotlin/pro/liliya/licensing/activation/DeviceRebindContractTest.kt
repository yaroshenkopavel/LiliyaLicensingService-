package pro.liliya.licensing.activation

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeviceRebindContractTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun valid_code_rebinds_and_exact_retry_is_idempotent() {
        val store = FakeStore()
        val service = service(store)
        val code = signedCode()
        val request = DeviceRebindRequest(
            rebindCode = code,
            attemptId = "attempt-1",
            installationId = "installation-B",
            deviceKeyFingerprint = "sha256:device-B"
        )
        val now = Instant.parse("2026-10-02T18:00:00Z")

        val first = assertIs<DeviceRebindResult.Rebound>(
            service.rebind(request, now)
        )
        val replay = assertIs<DeviceRebindResult.IdempotentReplay>(
            service.rebind(request, now.plusSeconds(1))
        )

        assertEquals("subject-1", first.subject)
        assertEquals(first.subject, replay.subject)
    }

    @Test
    fun active_old_device_blocks_rebind() {
        val store = FakeStore(activeDeviceExists = true)
        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = signedCode(),
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.DeviceLimitReached>(result)
    }

    @Test
    fun same_code_for_different_device_is_exhausted() {
        val store = FakeStore()
        val service = service(store)
        val code = signedCode()
        val now = Instant.parse("2026-10-02T18:00:00Z")

        assertIs<DeviceRebindResult.Rebound>(
            service.rebind(
                DeviceRebindRequest(
                    code, "attempt-1", "installation-B", "sha256:device-B"
                ),
                now
            )
        )
        assertIs<DeviceRebindResult.CodeExhausted>(
            service.rebind(
                DeviceRebindRequest(
                    code, "attempt-2", "installation-C", "sha256:device-C"
                ),
                now.plusSeconds(1)
            )
        )
    }

    @Test
    fun tampered_code_is_rejected_before_store() {
        val store = FakeStore()
        val code = signedCode()
        val envelope = requireNotNull(DeviceRebindCodeCodec.parse(code))
        val tamperedSignature = envelope.signature.copyOf().also {
            it[0] = (it[0].toInt() xor 0x01).toByte()
        }
        val tampered = DeviceRebindCodeCodec.encode(
            envelope.copy(signature = tamperedSignature)
        )

        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = tampered,
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.InvalidCode>(result)
        assertEquals(0, store.calls)
    }

    @Test
    fun unavailable_or_revoked_entitlement_is_rejected() {
        val store = FakeStore(entitlementUnavailable = true)
        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = signedCode(),
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.EntitlementUnavailable>(result)
    }

    @Test
    fun rebind_code_without_expiry_is_rejected_before_store() {
        val store = FakeStore()
        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = signedCode(expiresAt = null),
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.InvalidCode>(result)
        assertEquals(0, store.calls)
    }

    @Test
    fun stale_binding_epoch_is_rejected() {
        val store = FakeStore(currentBindingEpoch = 2)
        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = signedCode(deviceBindingEpoch = 1),
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.ReplacementStateChanged>(result)
    }

    @Test
    fun ldr2_claims_reject_legacy_ldr1_code_id_namespace() {
        assertFailsWith<IllegalArgumentException> {
            DeviceRebindCodeClaims(
                version = 2,
                codeId = "device-rebind-v1:legacy",
                subject = "subject-1",
                productId = "liliya-pro",
                deviceBindingEpoch = 1L,
                expiresAt = Instant.parse("2026-10-03T00:00:00Z")
            )
        }
    }

    @Test
    fun expired_code_is_rejected_before_store() {
        val store = FakeStore()
        val result = service(store).rebind(
            DeviceRebindRequest(
                rebindCode = signedCode(
                    expiresAt = Instant.parse("2026-10-01T00:00:00Z")
                ),
                attemptId = "attempt-1",
                installationId = "installation-B",
                deviceKeyFingerprint = "sha256:device-B"
            ),
            Instant.parse("2026-10-02T18:00:00Z")
        )

        assertIs<DeviceRebindResult.ExpiredCode>(result)
        assertEquals(0, store.calls)
    }

    private fun service(store: FakeStore) = DeviceRebindService(
        publicKeys = ActivationCodePublicKeyResolver { keyPair.public },
        store = store
    )

    private fun signedCode(
        deviceBindingEpoch: Long = 1L,
        expiresAt: Instant? = Instant.parse("2026-10-03T00:00:00Z")
    ): String {
        val claims = DeviceRebindCodeClaims(
            version = 2,
            codeId = "device-rebind-v2:test",
            subject = "subject-1",
            productId = "liliya-pro",
            deviceBindingEpoch = deviceBindingEpoch,
            expiresAt = expiresAt
        )
        val payload = DeviceRebindCodeCodec.signingPayload(claims)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return DeviceRebindCodeCodec.encode(
            DeviceRebindCodeEnvelope(
                keyId = "activation-key-v1",
                claims = claims,
                signature = signature
            )
        )
    }

    private class FakeStore(
        private val activeDeviceExists: Boolean = false,
        private val entitlementUnavailable: Boolean = false,
        private val currentBindingEpoch: Long = 1L
    ) : DeviceRebindStore {
        var calls = 0
        private var record: DeviceRebindRecord? = null

        override fun rebind(
            claims: DeviceRebindCodeClaims,
            attemptId: String,
            installationId: String,
            deviceKeyFingerprint: String,
            now: Instant
        ): DeviceRebindStoreResult {
            calls += 1
            if (activeDeviceExists) return DeviceRebindStoreResult.ActiveDeviceExists
            if (entitlementUnavailable) {
                return DeviceRebindStoreResult.EntitlementUnavailable
            }
            if (claims.deviceBindingEpoch != currentBindingEpoch) {
                return DeviceRebindStoreResult.StaleBindingEpoch
            }

            val existing = record
            if (existing != null) {
                return if (
                    existing.attemptId == attemptId &&
                    existing.installationId == installationId &&
                    existing.deviceKeyFingerprint == deviceKeyFingerprint
                ) {
                    DeviceRebindStoreResult.Replay(existing)
                } else {
                    DeviceRebindStoreResult.Exhausted
                }
            }

            val created = DeviceRebindRecord(
                codeId = claims.codeId,
                attemptId = attemptId,
                subject = claims.subject,
                productId = claims.productId,
                deviceBindingEpoch = claims.deviceBindingEpoch,
                installationId = installationId,
                deviceKeyFingerprint = deviceKeyFingerprint,
                reboundAt = now
            )
            record = created
            return DeviceRebindStoreResult.Created(created)
        }
    }
}
