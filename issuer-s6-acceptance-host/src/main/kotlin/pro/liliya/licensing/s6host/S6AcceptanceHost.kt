package pro.liliya.licensing.s6host

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import pro.liliya.licensing.http.LicenseHttpEndpoint
import pro.liliya.licensing.http.LicenseHttpMethod
import pro.liliya.licensing.http.LicenseHttpRequest
import pro.liliya.licensing.issuer.EntitlementSourcePort
import pro.liliya.licensing.issuer.EntitlementSourceRecord
import pro.liliya.licensing.issuer.EntitlementSourceResult
import pro.liliya.licensing.issuer.LicensingIssuerCoordinator
import pro.liliya.licensing.openbao.OpenBaoTransitEndpoint
import pro.liliya.licensing.openbao.OpenBaoTransitHttpClient
import pro.liliya.licensing.openbao.OpenBaoTransitKeyBinding
import pro.liliya.licensing.openbao.OpenBaoTransitLicenseEnvelopeSigner
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseRequestValidator
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.testkit.InMemoryDecisionTransactionPort

private const val PORT = 18300
private const val LOGICAL_KEY = "s6-openbao-v2"

fun main() {
    val openBaoAddress = requiredEnv("S6_OPENBAO_ADDR")
    val openBaoToken = requiredEnv("S6_OPENBAO_TOKEN")
    val openBaoKey = requiredEnv("S6_OPENBAO_KEY")
    val openBaoVersion = requiredEnv("S6_OPENBAO_KEY_VERSION").toInt()

    val signer = OpenBaoTransitLicenseEnvelopeSigner(
        bindings = listOf(
            OpenBaoTransitKeyBinding(
                keyReference = SigningKeyReference(LOGICAL_KEY),
                keyName = openBaoKey,
                keyVersion = openBaoVersion
            )
        ),
        client = OpenBaoTransitHttpClient(
            endpoint = OpenBaoTransitEndpoint(openBaoAddress),
            tokenProvider = { openBaoToken }
        )
    )

    val source = EntitlementSourcePort { request ->
        if (
            request.operation == LicenseOperation.REFRESH &&
            request.subjectReference == "s6-refresh-reject-subject"
        ) {
            EntitlementSourceResult.Ineligible(
                LicenseServiceFailure.REFRESH_REJECTED
            )
        } else if (request.productId != "liliya-pro") {
            EntitlementSourceResult.Ineligible(
                LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE
            )
        } else {
            val issuedAt = Instant.parse("2026-09-07T00:00:00Z")
            EntitlementSourceResult.Eligible(
                EntitlementSourceRecord(
                    licenseId = "s6-live-license-001",
                    subject = request.subjectReference,
                    productId = request.productId,
                    features = setOf("model.local"),
                    version = 1,
                    signingKeyId = LOGICAL_KEY,
                    issuedAt = issuedAt,
                    notBefore = issuedAt,
                    expiresAt = Instant.parse("2030-09-07T00:00:00Z"),
                    offlineLeaseUntil = Instant.parse("2027-09-07T00:00:00Z"),
                    revocationEpoch = 7
                )
            )
        }
    }

    val endpoint = LicenseHttpEndpoint(
        LicensingIssuerCoordinator(
            validator = LicenseRequestValidator(
                supportedVersion = LicenseProtocolVersion(1)
            ),
            source = source,
            transactions = InMemoryDecisionTransactionPort(),
            signing = LicenseSigningComposition(signer)
        )
    )

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
    val executor = Executors.newCachedThreadPool()

    server.createContext("/v1/license") { exchange ->
        val method = if (exchange.requestMethod == "POST") {
            LicenseHttpMethod.POST
        } else {
            LicenseHttpMethod.GET
        }
        val response = endpoint.handle(
            LicenseHttpRequest(
                method = method,
                path = exchange.requestURI.path,
                body = exchange.requestBody.use { it.readBytes() }
            )
        )

        response.contentType?.let {
            exchange.responseHeaders.set("Content-Type", it)
        }
        exchange.sendResponseHeaders(response.status, response.body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(response.body)
        }
    }
    server.createContext("/v1/license/malformed-success") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        val body = "PRIVATE-MALFORMED-SUCCESS-MARKER".encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }

    server.createContext("/v1/license/redirect") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        exchange.responseHeaders.set("Location", "/v1/license")
        val body = "redirect acceptance endpoint".encodeToByteArray()
        exchange.sendResponseHeaders(302, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }

    server.createContext("/v1/license/server-secret") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        val body = "PRIVATE-SERVER-SECRET-MARKER".encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain")
        exchange.sendResponseHeaders(503, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }

    server.createContext("/v1/license/slow") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        try {
            Thread.sleep(5_000)
            val body = "slow acceptance endpoint".encodeToByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { output ->
                output.write(body)
            }
        } catch (_: Exception) {
            runCatching { exchange.close() }
        }
    }

    server.executor = executor
    server.start()

    println("LICENSING_S6_4_HOST_READY={\"port\":18300,\"realSlice5Coordinator\":true,\"externalOpenBaoSigner\":true}")
    println("LICENSING_S6_5_HOST_READY={\"refreshReject\":true,\"slowEndpoint\":true,\"concurrentSlowRequests\":true}")
    println("LICENSING_S6_6_HOST_READY={\"malformedSuccess\":true,\"redirect\":true,\"serverSecret\":true,\"malformedRequestNoEcho\":true}")
    System.out.flush()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(0)
            executor.shutdownNow()
        }
    )
    CountDownLatch(1).await()
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required S6 acceptance environment: " + name)
