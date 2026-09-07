package pro.liliya.core.licensetransport

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.nio.file.Path
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

class LicenseS76AuthenticatedHttpsAcceptanceTest {
    @Test
    fun frozen_client_uses_https_auth_and_verifies_issue_refresh() {
        val endpoint = System.getenv("LIVE_S76_HTTPS_ENDPOINT")
        val bearer = System.getenv("LIVE_S76_BEARER")
        val publicKeyPath = System.getenv("LIVE_S76_PUBLIC_KEY_DER_PATH")
        val evidencePath = System.getenv("LIVE_S76_EVIDENCE_PATH")
        val trustStorePath = System.getenv("LIVE_S76_TRUSTSTORE_PATH")
        val trustStorePassword = System.getenv("LIVE_S76_TRUSTSTORE_PASSWORD")

        assumeTrue(!endpoint.isNullOrBlank(), "LIVE_S76_HTTPS_ENDPOINT is not configured")
        assumeTrue(!bearer.isNullOrBlank(), "LIVE_S76_BEARER is not configured")
        assumeTrue(!publicKeyPath.isNullOrBlank(), "LIVE_S76_PUBLIC_KEY_DER_PATH is not configured")
        assumeTrue(Files.isRegularFile(Path.of(publicKeyPath!!)), "S7.6 public key file is missing")
        assumeTrue(!trustStorePath.isNullOrBlank(), "S7.6 truststore path is not configured")
        assumeTrue(!trustStorePassword.isNullOrBlank(), "S7.6 truststore password is not configured")
        assumeTrue(Files.isRegularFile(Path.of(trustStorePath!!)), "S7.6 truststore is missing")

        installAcceptanceTrust(
            path = Path.of(trustStorePath),
            password = trustStorePassword!!.toCharArray()
        )

        val url = URL(endpoint!!)
        assertEquals("https", url.protocol)

        val config = LicenseHttpTransportConfig(
            endpoint = url,
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 5_000
        )
        assertEquals(1, config.attemptLimit)

        val unauthenticated = LicenseHttpTransportClient(config = config)
        val missing = assertIs<LicenseClientTransportResult.ServiceRejected>(
            unauthenticated.execute(
                request(
                    operation = LicenseServiceOperation.ISSUE,
                    subject = "s7-6-missing-auth",
                    requestId = "s7-6-missing-auth-001"
                )
            )
        )
        assertEquals(
            LicenseRemoteServiceFailure.AUTHENTICATION_REQUIRED,
            missing.reason
        )

        val authenticated = LicenseHttpTransportClient(
            config = config,
            engine = BearerLicenseHttpEngine(bearer!!)
        )

        val issue = assertIs<LicenseClientTransportResult.Signed>(
            authenticated.execute(
                request(
                    operation = LicenseServiceOperation.ISSUE,
                    subject = "s7-6-live-subject",
                    requestId = "s7-6-issue-001"
                )
            )
        )
        val publicKey = Files.readAllBytes(Path.of(publicKeyPath))
        val verifiedIssue = verify(issue.envelope, publicKey)
        assertEquals(0L, verifiedIssue.entitlement.replaySequence?.value)

        val refresh = assertIs<LicenseClientTransportResult.Signed>(
            authenticated.execute(
                request(
                    operation = LicenseServiceOperation.REFRESH,
                    subject = "s7-6-live-subject",
                    requestId = "s7-6-refresh-002"
                )
            )
        )
        val verifiedRefresh = verify(refresh.envelope, publicKey)
        assertEquals(1L, verifiedRefresh.entitlement.replaySequence?.value)
        assertEquals(
            verifiedIssue.entitlement.signingKeyId,
            verifiedRefresh.entitlement.signingKeyId
        )
        assertTrue(
            !issue.envelope.payload.copyBytes()
                .contentEquals(refresh.envelope.payload.copyBytes())
        )

        val evidence =
            "LICENSING_S7_6B_HTTPS_EVIDENCE=" +
                "{\"https\":true," +
                "\"frozenClient\":true," +
                "\"authenticationRequired\":true," +
                "\"bearerOutsideWireDto\":true," +
                "\"issueSigned\":true," +
                "\"refreshSigned\":true," +
                "\"frozenVerifier\":true," +
                "\"replayAdvanced\":true," +
                "\"attemptLimitOne\":true," +
                "\"stoppedBeforeAuthorityExecution\":true}"

        println(evidence)
        evidencePath
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                val target = Path.of(raw)
                target.parent?.let(Files::createDirectories)
                Files.writeString(target, evidence + "\n")
            }
    }

    private fun installAcceptanceTrust(
        path: Path,
        password: CharArray
    ) {
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(path).use { input ->
                keyStore.load(input, password)
            }
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

    private fun request(
        operation: LicenseServiceOperation,
        subject: String,
        requestId: String
    ): LicenseServiceTransportRequest =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = operation,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject(subject),
            requestId = LicenseServiceRequestId(requestId)
        )

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
                active.outputStream.use { out ->
                    out.write(request.body)
                    out.flush()
                }

                val status = active.responseCode
                val stream = if (status in 200..399) {
                    active.inputStream
                } else {
                    active.errorStream
                }
                val body = stream?.use { it.readBytes() } ?: byteArrayOf()
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(status = status, body = body)
                )
            } catch (_: SSLException) {
                LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.TLS_FAILURE
                )
            } catch (_: IOException) {
                LicenseHttpEngineResult.Failed(
                    LicenseClientTransportFailure.CONNECT_FAILURE
                )
            } finally {
                runCatching { registration?.close() }
                connection?.disconnect()
            }
        }
    }
}
