package pro.liliya.licensing.https

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import pro.liliya.licensing.http.LicenseHttpResponse
import pro.liliya.licensing.runtime.LicensingRuntimeListenerResult

fun main() {
    val path = requiredEnv("S7_6_TLS_KEYSTORE_PATH")
    val password = requiredEnv("S7_6_TLS_KEYSTORE_PASSWORD")
    val host = System.getenv("S7_6_TLS_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val port = System.getenv("S7_6_TLS_PORT")?.toIntOrNull() ?: 18443

    val config = ProductionHttpsConfig(
        host = host,
        port = port,
        keyStorePath = Path.of(path),
        keyStorePassword = TlsPassword.of(password.toCharArray())
    )

    val listener = ProductionHttpsListener(
        config = config,
        handler = LicenseHttpsHandler {
            LicenseHttpResponse(
                status = 503,
                contentType = null,
                body = byteArrayOf()
            )
        },
        readiness = { true }
    )

    when (listener.start()) {
        LicensingRuntimeListenerResult.Started -> {
            println(
                "LICENSING_S7_6_HTTPS_HOST_READY={" +
                    "\"https\":true,\"host\":\"$host\",\"port\":$port," +
                    "\"certificateExternal\":true}"
            )
            System.out.flush()
        }
        LicensingRuntimeListenerResult.Failed -> {
            config.close()
            error("failed to start S7.6 HTTPS acceptance host")
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { listener.close() }
            runCatching { config.close() }
        }
    )

    CountDownLatch(1).await()
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required S7.6 acceptance environment: " + name)
