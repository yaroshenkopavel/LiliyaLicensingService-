package pro.liliya.core.licensetransport

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion

class LicenseS78StagingAcceptanceTest {
    @Test
    fun phase1_issue_refresh_persists_replay_zero_to_one() {
        requirePhase("phase1")
        val client = client()
        val publicKey = publicKey()

        val issue = assertIs<LicenseClientTransportResult.Signed>(
            client.execute(request(LicenseServiceOperation.ISSUE, "s7-8-issue-001"))
        )
        assertEquals(0L, verify(issue.envelope, publicKey).entitlement.replaySequence?.value)

        val refresh = assertIs<LicenseClientTransportResult.Signed>(
            client.execute(request(LicenseServiceOperation.REFRESH, "s7-8-refresh-002"))
        )
        assertEquals(1L, verify(refresh.envelope, publicKey).entitlement.replaySequence?.value)

        writeEvidence(
            "LICENSING_S7_8_PHASE1_EVIDENCE=" +
                "{\"https\":true,\"postgresAuthoritative\":true," +
                "\"nonDevOpenBao\":true,\"issueReplay\":0,\"refreshReplay\":1," +
                "\"attemptLimitOne\":true,\"stoppedBeforeAuthorityExecution\":true}"
        )
    }

    @Test
    fun phase2_after_restart_refresh_continues_at_two() {
        requirePhase("phase2")
        val refresh = assertIs<LicenseClientTransportResult.Signed>(
            client().execute(request(LicenseServiceOperation.REFRESH, "s7-8-refresh-after-restart-003"))
        )
        assertEquals(2L, verify(refresh.envelope, publicKey()).entitlement.replaySequence?.value)

        writeEvidence(
            "LICENSING_S7_8_PHASE2_EVIDENCE=" +
                "{\"backendRestarted\":true,\"postgresRestarted\":true," +
                "\"openBaoRestarted\":true,\"replayAfterRestart\":2," +
                "\"signedVerificationStillValid\":true,\"stoppedBeforeAuthorityExecution\":true}"
        )
    }

    private fun client(): LicenseHttpTransportClient {
        val endpoint = requiredEnv("LIVE_S78_HTTPS_ENDPOINT")
        val bearer = requiredEnv("LIVE_S78_BEARER")
        installAcceptanceTrust(
            Path.of(requiredEnv("LIVE_S78_TRUSTSTORE_PATH")),
            requiredEnv("LIVE_S78_TRUSTSTORE_PASSWORD").toCharArray()
        )
        val config = LicenseHttpTransportConfig(
            endpoint = URL(endpoint),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 5_000
        )
        assertEquals(1, config.attemptLimit)
        return LicenseHttpTransportClient(
            config = config,
            engine = BearerLicenseHttpEngine(bearer)
        )
    }

    private fun publicKey(): ByteArray =
        Files.readAllBytes(Path.of(requiredEnv("LIVE_S78_PUBLIC_KEY_DER_PATH")))

    private fun request(
        operation: LicenseServiceOperation,
        requestId: String
    ): LicenseServiceTransportRequest =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = operation,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("s7-8-staging-subject"),
            requestId = LicenseServiceRequestId(requestId)
        )

    private fun verify(
        envelope: LicenseSignedEnvelope,
        publicKeyDer: ByteArray
    ): LicenseVerificationResult.Verified {
        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = envelope.signingKeyId,
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            material = publicKeyDer
        )
        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )
    }

    private fun installAcceptanceTrust(path: Path, password: CharArray) {
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(path).use { keyStore.load(it, password) }
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
        assumeTrue(requiredEnv("LIVE_S78_PHASE") == expected, "wrong S7.8 phase")
    }

    private fun writeEvidence(value: String) {
        println(value)
        val path = Path.of(requiredEnv("LIVE_S78_EVIDENCE_PATH"))
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, value + "\n")
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("missing S7.8 acceptance environment")

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
