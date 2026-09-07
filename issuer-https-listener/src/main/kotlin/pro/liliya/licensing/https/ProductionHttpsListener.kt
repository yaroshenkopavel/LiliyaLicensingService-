package pro.liliya.licensing.https

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.http.AuthenticatedLicenseHttpEndpoint
import pro.liliya.licensing.http.LicenseHttpMethod
import pro.liliya.licensing.http.LicenseHttpRequest
import pro.liliya.licensing.http.LicenseHttpResponse
import pro.liliya.licensing.runtime.LicensingRuntimeListener
import pro.liliya.licensing.runtime.LicensingRuntimeListenerResult

class TlsPassword private constructor(value: CharArray) : AutoCloseable {
    private val chars = value.copyOf()
    private var closed = false

    init {
        require(chars.isNotEmpty()) { "TLS password must not be empty" }
    }

    fun useChars(block: (CharArray) -> Unit) {
        check(!closed) { "TLS password is closed" }
        val copy = chars.copyOf()
        try {
            block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    override fun close() {
        if (!closed) {
            chars.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "TlsPassword(<redacted>)"

    companion object {
        fun of(value: CharArray): TlsPassword = TlsPassword(value)
    }
}

data class ProductionHttpsConfig(
    val host: String,
    val port: Int,
    val keyStorePath: Path,
    val keyStorePassword: TlsPassword,
    val maxRequestBytes: Int = 64 * 1024,
    val workerThreads: Int = 4
) : AutoCloseable {
    init {
        require(host.isNotBlank()) { "HTTPS host must not be blank" }
        require(port in 1..65535) { "HTTPS port must be valid" }
        require(maxRequestBytes in 1..1024 * 1024) { "HTTPS request bound must be valid" }
        require(workerThreads in 1..64) { "HTTPS worker count must be valid" }
    }

    override fun close() = keyStorePassword.close()

    override fun toString(): String =
        "ProductionHttpsConfig(host=" + host +
            ",port=" + port +
            ",keyStorePath=<redacted>,keyStorePassword=<redacted>," +
            "maxRequestBytes=" + maxRequestBytes +
            ",workerThreads=" + workerThreads + ")"
}

fun interface LicenseHttpsHandler {
    fun handle(request: LicenseHttpRequest): LicenseHttpResponse
}

object ProductionTlsContextLoader {
    fun load(config: ProductionHttpsConfig): SSLContext {
        require(Files.isRegularFile(config.keyStorePath)) {
            "TLS keystore must be a regular file"
        }

        var context: SSLContext? = null
        config.keyStorePassword.useChars { password ->
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(config.keyStorePath).use { input ->
                keyStore.load(input, password)
            }

            val keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            )
            keyManagers.init(keyStore, password)

            context = SSLContext.getInstance("TLS").apply {
                init(keyManagers.keyManagers, null, null)
            }
        }
        return requireNotNull(context)
    }
}

/**
 * Production HTTPS transport listener.
 *
 * Owns only HTTPS socket/TLS/request-adaptation lifecycle. It does not own entitlement,
 * LicensePolicy, replay/revocation, signing, Capability Authority, or Execution Authority.
 */
class ProductionHttpsListener(
    private val config: ProductionHttpsConfig,
    private val handler: LicenseHttpsHandler,
    private val sslContextProvider: () -> SSLContext = {
        ProductionTlsContextLoader.load(config)
    }
) : LicensingRuntimeListener {
    constructor(
        config: ProductionHttpsConfig,
        endpoint: AuthenticatedLicenseHttpEndpoint
    ) : this(
        config = config,
        handler = LicenseHttpsHandler(endpoint::handle)
    )

    private val lock = Any()
    private var server: HttpsServer? = null
    private var executor: ExecutorService? = null

    override fun start(): LicensingRuntimeListenerResult = synchronized(lock) {
        if (server != null) {
            return@synchronized LicensingRuntimeListenerResult.Failed
        }

        val createdExecutor = Executors.newFixedThreadPool(config.workerThreads)
        try {
            val createdServer = HttpsServer.create(
                InetSocketAddress(config.host, config.port),
                0
            )
            createdServer.httpsConfigurator = HttpsConfigurator(sslContextProvider())
            createdServer.executor = createdExecutor

            createdServer.createContext("/v1/license") { exchange ->
                try {
                    val bytes = exchange.requestBody.use { input ->
                        input.readNBytes(config.maxRequestBytes + 1)
                    }
                    if (bytes.size > config.maxRequestBytes) {
                        exchange.sendResponseHeaders(413, -1)
                        return@createContext
                    }

                    val authentication = bearerCredential(
                        exchange.requestHeaders.getFirst("Authorization")
                    )
                    val response = handler.handle(
                        LicenseHttpRequest(
                            method = if (exchange.requestMethod == "POST") {
                                LicenseHttpMethod.POST
                            } else {
                                LicenseHttpMethod.GET
                            },
                            path = exchange.requestURI.path,
                            body = bytes,
                            authentication = authentication
                        )
                    )

                    response.contentType?.let {
                        exchange.responseHeaders.set("Content-Type", it)
                    }
                    if (response.body.isEmpty()) {
                        exchange.sendResponseHeaders(response.status, -1)
                    } else {
                        exchange.sendResponseHeaders(
                            response.status,
                            response.body.size.toLong()
                        )
                        exchange.responseBody.use { output ->
                            output.write(response.body)
                        }
                    }
                } catch (_: Exception) {
                    runCatching {
                        if (exchange.responseCode == -1) {
                            exchange.sendResponseHeaders(500, -1)
                        }
                    }
                } finally {
                    exchange.close()
                }
            }

            createdServer.createContext("/health/ready") { exchange ->
                try {
                    val body = "{\"status\":\"ready\"}".encodeToByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } finally {
                    exchange.close()
                }
            }

            createdServer.start()
            server = createdServer
            executor = createdExecutor
            LicensingRuntimeListenerResult.Started
        } catch (_: Exception) {
            createdExecutor.shutdownNow()
            LicensingRuntimeListenerResult.Failed
        }
    }

    override fun close() = synchronized(lock) {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    override fun toString(): String =
        "ProductionHttpsListener(config=" + config +
            ",handler=<redacted>,tls=<redacted>,started=" + (server != null) + ")"

    private fun bearerCredential(header: String?): RequestAuthenticationCredential? {
        if (header == null) return null
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return null
        val token = header.removePrefix(prefix)
        if (token.isBlank()) return null
        return RequestAuthenticationCredential.of(token.encodeToByteArray())
    }
}
