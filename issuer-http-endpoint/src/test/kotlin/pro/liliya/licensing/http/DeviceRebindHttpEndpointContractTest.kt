package pro.liliya.licensing.http

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pro.liliya.licensing.activation.ActivationCodePublicKeyResolver
import pro.liliya.licensing.activation.DeviceRebindCodeClaims
import pro.liliya.licensing.activation.DeviceRebindCodeCodec
import pro.liliya.licensing.activation.DeviceRebindCodeEnvelope
import pro.liliya.licensing.activation.DeviceRebindRecord
import pro.liliya.licensing.activation.DeviceRebindService
import pro.liliya.licensing.activation.DeviceRebindStore
import pro.liliya.licensing.activation.DeviceRebindStoreResult
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.LicensingIssuerResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

class DeviceRebindHttpEndpointContractTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val now = Instant.parse("2026-10-02T18:00:00Z")

    @Test
    fun rebind_needs_no_product_auth_and_returns_new_bound_license() {
        val store = FakeStore()
        var enrollmentReference: String? = null
        val endpoint = endpoint(
            store = store,
            issuer = LicensingIssuerProcessor { request ->
                enrollmentReference = request.enrollmentReference
                LicensingIssuerResult.Issued(
                    state = DecisionState(0, 0),
                    envelope = envelope()
                )
            }
        )

        val response = endpoint.handle(request(code(), "attempt-1"))

        assertEquals(200, response.status)
        val body = response.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"kind\":\"rebound\""))
        assertTrue(body.contains("\"payloadBase64\""))
        assertTrue(enrollmentReference.orEmpty().startsWith("device-binding-v1:"))
    }

    @Test
    fun active_device_exists_is_rejected_without_license_issue() {
        val endpoint = endpoint(
            store = FakeStore(activeDeviceExists = true),
            issuer = LicensingIssuerProcessor {
                error("issuer must not run when active device exists")
            }
        )

        val response = endpoint.handle(request(code(), "attempt-1"))

        assertEquals(409, response.status)
        assertTrue(
            response.body.toString(Charsets.UTF_8)
                .contains("\"reason\":\"DEVICE_LIMIT_REACHED\"")
        )
    }

    private fun endpoint(
        store: FakeStore,
        issuer: LicensingIssuerProcessor
    ) = DeviceRebindHttpEndpoint(
        service = DeviceRebindService(
            publicKeys = ActivationCodePublicKeyResolver { keyPair.public },
            store = store
        ),
        issuer = issuer,
        clock = Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun request(
        code: String,
        attemptId: String
    ) = LicenseHttpRequest(
        method = LicenseHttpMethod.POST,
        path = DeviceRebindHttpEndpoint.PATH,
        body = """
            {
              "wireVersion":1,
              "rebindCode":"$code",
              "attemptId":"$attemptId",
              "installationId":"installation-B",
              "deviceKeyFingerprint":"sha256:device-B"
            }
        """.trimIndent().toByteArray(),
        authentication = null
    )

    private fun code(): String {
        val claims = DeviceRebindCodeClaims(
            version = 1,
            codeId = "device-rebind-http-001",
            subject = "subject-1",
            productId = "liliya-pro",
            expiresAt = Instant.parse("2026-10-03T00:00:00Z")
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(DeviceRebindCodeCodec.signingPayload(claims))
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

    private fun envelope() = SignedLicenseEnvelope(
        schemaVersion = SigningEnvelopeSchemaVersion(1),
        algorithm = SigningAlgorithm("ECDSA_P256_SHA256"),
        keyReference = SigningKeyReference("liliya-prod-license-signing-v1"),
        canonicalPayload = "payload".encodeToByteArray(),
        signature = "signature".encodeToByteArray()
    )

    private class FakeStore(
        private val activeDeviceExists: Boolean = false
    ) : DeviceRebindStore {
        private var record: DeviceRebindRecord? = null

        override fun rebind(
            claims: DeviceRebindCodeClaims,
            attemptId: String,
            installationId: String,
            deviceKeyFingerprint: String,
            now: Instant
        ): DeviceRebindStoreResult {
            if (activeDeviceExists) {
                return DeviceRebindStoreResult.ActiveDeviceExists
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
                installationId = installationId,
                deviceKeyFingerprint = deviceKeyFingerprint,
                reboundAt = now
            )
            record = created
            return DeviceRebindStoreResult.Created(created)
        }
    }
}
