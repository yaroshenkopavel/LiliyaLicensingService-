package pro.liliya.licensing.https

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.http.AuthenticatedLicenseHttpEndpoint
import pro.liliya.licensing.http.LicenseHttpEndpoint
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
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.testkit.InMemoryDecisionTransactionPort
import pro.liliya.licensing.runtime.LicensingRuntimeListenerResult

private const val LOGICAL_KEY = "s7-6-openbao-v2"

fun main() {
    val openBaoAddress = requiredEnv("S7_6_OPENBAO_ADDR")
    val openBaoToken = requiredEnv("S7_6_OPENBAO_TOKEN")
    val openBaoKey = requiredEnv("S7_6_OPENBAO_KEY")
    val openBaoVersion = requiredEnv("S7_6_OPENBAO_KEY_VERSION").toInt()
    val authSecret = requiredEnv("S7_6_REQUEST_AUTH_SECRET").encodeToByteArray()
    val keyStorePath = Path.of(requiredEnv("S7_6_TLS_KEYSTORE_PATH"))
    val keyStorePassword = requiredEnv("S7_6_TLS_KEYSTORE_PASSWORD")
    val port = System.getenv("S7_6_TLS_PORT")?.toIntOrNull() ?: 18446

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
        if (request.productId != "liliya-pro") {
            EntitlementSourceResult.Ineligible(
                pro.liliya.licensing.protocol.LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE
            )
        } else {
            val issuedAt = Instant.parse("2026-09-07T00:00:00Z")
            EntitlementSourceResult.Eligible(
                EntitlementSourceRecord(
                    licenseId = "s7-6-live-license-001",
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

    val authentication = RequestAuthenticationPort { credential ->
        if (credential == null) {
            RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.MISSING)
        } else {
            val candidate = credential.copyBytes()
            try {
                if (MessageDigest.isEqual(authSecret, candidate)) {
                    RequestAuthenticationResult.Authenticated
                } else {
                    RequestAuthenticationResult.Rejected(RequestAuthenticationFailure.INVALID)
                }
            } finally {
                candidate.fill(0)
            }
        }
    }

    val endpoint = AuthenticatedLicenseHttpEndpoint(
        delegate = LicenseHttpEndpoint(
            LicensingIssuerCoordinator(
                validator = LicenseRequestValidator(
                    supportedVersion = LicenseProtocolVersion(1)
                ),
                source = source,
                transactions = InMemoryDecisionTransactionPort(),
                signing = LicenseSigningComposition(signer)
            )
        ),
        authentication = authentication
    )

    val config = ProductionHttpsConfig(
        host = "127.0.0.1",
        port = port,
        keyStorePath = keyStorePath,
        keyStorePassword = TlsPassword.of(keyStorePassword.toCharArray())
    )
    val listener = ProductionHttpsListener(config, endpoint)

    when (listener.start()) {
        LicensingRuntimeListenerResult.Started -> {
            println(
                "LICENSING_S7_6B_HOST_READY={" +
                    "\"https\":true,\"authenticatedEndpoint\":true," +
                    "\"realIssuerCoordinator\":true,\"externalOpenBaoSigner\":true," +
                    "\"port\":" + port + "}"
            )
            System.out.flush()
        }

        LicensingRuntimeListenerResult.Failed -> {
            authSecret.fill(0)
            config.close()
            error("failed to start S7.6b authenticated HTTPS host")
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { listener.close() }
            runCatching { config.close() }
            authSecret.fill(0)
        }
    )

    CountDownLatch(1).await()
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required S7.6b acceptance environment: " + name)
