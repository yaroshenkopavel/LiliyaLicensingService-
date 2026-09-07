package pro.liliya.core.licensetransport

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceAuthenticationProof
import pro.liliya.core.license.LicenseServiceAuthenticationTranscript
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServicePolicyContextResult
import pro.liliya.core.license.LicenseServiceProofVerifier
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseServiceStateAcceptanceComposition
import pro.liliya.core.license.LicenseServiceStateAcceptanceResult
import pro.liliya.core.license.LicenseServiceStateEnvelope
import pro.liliya.core.license.LicenseServiceTrustedKeyResolver
import pro.liliya.core.license.LicenseServiceTrustedVerificationKey
import pro.liliya.core.license.LicenseServiceVerificationRejection
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseS79dServiceStatePolicyAcceptanceTest {
    private val json = ObjectMapper()
    private val profile = LicenseServiceEvidenceProfile(
        "ECDSA-P256-SHA256-SERVICE-STATE-V1"
    )

    @Test
    fun backend_issued_service_state_reaches_frozen_policy_and_stops_before_authority() {
        requirePhase("positive")
        installAcceptanceTrust()

        val entitlementSigned = assertIs<LicenseClientTransportResult.Signed>(
            entitlementClient().execute(
                request(
                    operation = LicenseServiceOperation.ISSUE,
                    requestId = "s7-9d-entitlement-001"
                )
            )
        )
        val verifiedEntitlement = verifyEntitlement(entitlementSigned)
        assertEquals(0L, verifiedEntitlement.entitlement.replaySequence?.value)

        val serviceEnvelope = serviceStateEnvelope("s7-9d-state-001")
        val acceptance = serviceStateAcceptance()
        val accepted = assertIs<LicenseServiceStateAcceptanceResult.Advanced>(
            acceptance.verifyAndAccept(serviceEnvelope)
        )
        assertEquals(0L, accepted.snapshot.state.replaySequence?.value)
        assertEquals(
            verifiedEntitlement.entitlement.revocationEpoch,
            accepted.snapshot.state.revocationEpoch
        )

        val context = assertIs<LicenseServicePolicyContextResult.Available>(
            acceptance.policyContext(
                scope = LicenseServiceSecurityScope(
                    productId = verifiedEntitlement.entitlement.productId,
                    subject = verifiedEntitlement.entitlement.subject
                ),
                now = verifiedEntitlement.entitlement.notBefore.plusSeconds(1),
                suspiciousTimeOrReplayState = false
            )
        ).context

        val decision = assertIs<LicenseDecision.Entitled>(
            LicensePolicy().evaluate(
                verified = verifiedEntitlement,
                request = LicensePolicyRequest(
                    productId = LicenseProductId("liliya-pro"),
                    feature = LicenseFeature("model.local"),
                    subject = LicenseSubject("s7-9d-service-state-subject")
                ),
                context = context
            )
        )
        assertEquals(0L, decision.receipt.replaySequence?.value)

        writeEvidence(
            "LICENSING_S7_9D_POSITIVE_EVIDENCE=" +
                "{\"backendServiceState\":true,\"productionProfile\":true," +
                "\"frozenStateVerification\":true,\"policyContextAvailable\":true," +
                "\"licensePolicyEntitled\":true,\"stoppedBeforeAuthorityExecution\":true}"
        )
    }

    @Test
    fun tampered_backend_proof_is_rejected_before_state_acceptance() {
        requirePhase("negative")
        installAcceptanceTrust()
        val envelope = serviceStateEnvelope("s7-9d-state-negative-001")
        val original = envelope.proof.copyBytes()
        original[original.lastIndex] = (original.last().toInt() xor 1).toByte()

        val tampered = LicenseServiceStateEnvelope(
            protocolVersion = envelope.protocolVersion,
            purpose = envelope.purpose,
            profile = envelope.profile,
            signingKeyId = envelope.signingKeyId,
            payload = envelope.payload,
            proof = LicenseServiceAuthenticationProof.of(original)
        )

        val rejected = assertIs<LicenseServiceStateAcceptanceResult.VerificationRejected>(
            serviceStateAcceptance().verifyAndAccept(tampered)
        )
        assertEquals(
            pro.liliya.core.license.LicenseServiceStateVerificationRejection.INVALID_PROOF,
            rejected.reason
        )

        writeEvidence(
            "LICENSING_S7_9D_NEGATIVE_EVIDENCE=" +
                "{\"tamperedProofRejected\":true,\"acceptedStateCreated\":false}"
        )
    }

    private fun entitlementClient(): LicenseHttpTransportClient {
        val config = LicenseHttpTransportConfig(
            endpoint = URL(requiredEnv("LIVE_S79D_LICENSE_ENDPOINT")),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 5_000
        )
        assertEquals(1, config.attemptLimit)
        return LicenseHttpTransportClient(
            config = config,
            engine = BearerLicenseHttpEngine(requiredEnv("LIVE_S79D_BEARER"))
        )
    }

    private fun request(
        operation: LicenseServiceOperation,
        requestId: String
    ): LicenseServiceTransportRequest =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = operation,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("s7-9d-service-state-subject"),
            requestId = LicenseServiceRequestId(requestId)
        )

    private fun verifyEntitlement(
        signed: LicenseClientTransportResult.Signed
    ): LicenseVerificationResult.Verified {
        val trusted = LicenseTrustedVerificationKey.of(
            keyId = signed.envelope.signingKeyId,
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            material = Files.readAllBytes(
                Path.of(requiredEnv("LIVE_S79D_ENTITLEMENT_PUBLIC_KEY_DER_PATH"))
            )
        )
        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(signed.envelope)
        )
    }

    private fun serviceStateEnvelope(requestId: String): LicenseServiceStateEnvelope {
        val connection = URL(requiredEnv("LIVE_S79D_SERVICE_STATE_ENDPOINT"))
            .openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Authorization",
                "Bearer " + requiredEnv("LIVE_S79D_BEARER")
            )
            val body = json.createObjectNode().apply {
                put("wireVersion", 1)
                put("kind", "service-state-request")
                put("protocolVersion", 1)
                put("productId", "liliya-pro")
                put("subjectReference", "s7-9d-service-state-subject")
                put("requestId", requestId)
            }
            connection.outputStream.use { it.write(json.writeValueAsBytes(body)) }
            assertEquals(200, connection.responseCode)
            val root = json.readTree(connection.inputStream.use { it.readBytes() })
            assertEquals(1, root.path("wireVersion").asInt())
            assertEquals("service-state", root.path("kind").asText())

            LicenseServiceStateEnvelope(
                protocolVersion = LicenseServiceProtocolVersion(
                    root.path("protocolVersion").asLong()
                ),
                purpose = LicenseServiceEvidencePurpose.valueOf(
                    root.path("purpose").asText()
                ),
                profile = LicenseServiceEvidenceProfile(root.path("profile").asText()),
                signingKeyId = LicenseKeyId(root.path("signingKeyId").asText()),
                payload = pro.liliya.core.license.LicenseServiceOpaquePayload.of(
                    Base64.getDecoder().decode(root.path("payloadBase64").asText())
                ),
                proof = LicenseServiceAuthenticationProof.of(
                    Base64.getDecoder().decode(root.path("proofBase64").asText())
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun serviceStateAcceptance(): LicenseServiceStateAcceptanceComposition {
        val keyId = LicenseKeyId(requiredEnv("LIVE_S79D_SERVICE_STATE_KEY_ID"))
        val trusted = LicenseServiceTrustedVerificationKey.of(
            keyId = keyId,
            profile = profile,
            material = Files.readAllBytes(
                Path.of(requiredEnv("LIVE_S79D_SERVICE_STATE_PUBLIC_KEY_DER_PATH"))
            )
        )
        return LicenseServiceStateAcceptanceComposition(
            foundation = foundation(),
            supportedProtocolVersion = LicenseServiceProtocolVersion(1),
            supportedPurposes = setOf(LicenseServiceEvidencePurpose.SECURITY_STATE),
            supportedProfiles = setOf(profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { requestedId, requestedProfile ->
                trusted.takeIf {
                    it.keyId == requestedId && it.profile == requestedProfile
                }
            },
            proofVerifier = EcdsaP256ServiceStateProofVerifier
        )
    }

    private object EcdsaP256ServiceStateProofVerifier : LicenseServiceProofVerifier {
        private val supported = LicenseServiceEvidenceProfile(
            "ECDSA-P256-SHA256-SERVICE-STATE-V1"
        )

        override fun verify(
            profile: LicenseServiceEvidenceProfile,
            key: LicenseServiceTrustedVerificationKey,
            transcript: LicenseServiceAuthenticationTranscript,
            proof: LicenseServiceAuthenticationProof
        ): Boolean {
            if (profile != supported || key.profile != supported) return false
            return try {
                val publicKey = KeyFactory.getInstance("EC").generatePublic(
                    X509EncodedKeySpec(key.copyMaterial())
                )
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(transcript.copyBytes())
                verifier.verify(proof.copyBytes())
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "s7-9d-policy-" + sequence.incrementAndGet()
            }
        )
    }

    private fun installAcceptanceTrust() {
        val password = requiredEnv("LIVE_S79D_TRUSTSTORE_PASSWORD").toCharArray()
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(
                Path.of(requiredEnv("LIVE_S79D_TRUSTSTORE_PATH"))
            ).use { keyStore.load(it, password) }
            val trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagers.init(keyStore)
            val context = SSLContext.getInstance("TLS")
            context.init(null, trustManagers.trustManagers, null)
            HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun requirePhase(expected: String) {
        assumeTrue(requiredEnv("LIVE_S79D_PHASE") == expected, "wrong S7.9D phase")
    }

    private fun writeEvidence(value: String) {
        println(value)
        val target = Path.of(requiredEnv("LIVE_S79D_EVIDENCE_PATH"))
        target.parent?.let(Files::createDirectories)
        Files.writeString(target, value + "\n")
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("missing S7.9D acceptance environment: " + name)

    private class BearerLicenseHttpEngine(
        private val bearer: String
    ) : LicenseHttpEngine {
        override fun execute(
            request: LicenseHttpEngineRequest,
            cancellation: LicenseTransportCancellation
        ): LicenseHttpEngineResult {
            if (cancellation.isCancelled()) {
                return LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.CANCELLED
                )
            }
            var connection: HttpURLConnection? = null
            var registration: AutoCloseable? = null
            return try {
                val active = request.endpoint.openConnection() as HttpURLConnection
                connection = active
                active.requestMethod = "POST"
                active.connectTimeout = request.connectTimeoutMillis
                active.readTimeout = request.readTimeoutMillis
                active.instanceFollowRedirects = false
                active.doOutput = true
                active.setRequestProperty("Content-Type", "application/json")
                active.setRequestProperty("Accept", "application/json")
                active.setRequestProperty("Authorization", "Bearer $bearer")
                registration = cancellation.register { active.disconnect() }
                active.outputStream.use { out -> out.write(request.body) }
                val status = active.responseCode
                val stream = if (status in 200..399) active.inputStream else active.errorStream
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(
                        status = status,
                        body = stream?.use { it.readBytes() } ?: byteArrayOf()
                    )
                )
            } catch (_: SSLException) {
                LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.TLS_FAILURE)
            } catch (_: IOException) {
                LicenseHttpEngineResult.Failed(LicenseClientTransportFailure.CONNECT_FAILURE)
            } finally {
                runCatching { registration?.close() }
                connection?.disconnect()
            }
        }
    }
}
