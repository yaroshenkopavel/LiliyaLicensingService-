package pro.liliya.licensing.https

import java.nio.file.Path
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.licensing.http.LicenseHttpResponse
import pro.liliya.licensing.runtime.LicensingRuntimeListenerResult

class ProductionHttpsListenerContractTest {
    @Test
    fun config_rendering_redacts_tls_material() {
        val secretText = "private-password".toCharArray()
        val config = ProductionHttpsConfig(
            host = "127.0.0.1",
            port = 18443,
            keyStorePath = Path.of("/private/server.p12"),
            keyStorePassword = TlsPassword.of(secretText)
        )
        secretText.fill('X')

        config.use {
            val rendered = it.toString()
            assertTrue(rendered.contains("keyStorePath=<redacted>"))
            assertTrue(rendered.contains("keyStorePassword=<redacted>"))
            assertFalse(rendered.contains("private-password"))
            assertFalse(rendered.contains("/private/server.p12"))
        }
    }

    @Test
    fun start_failure_is_typed_and_does_not_throw() {
        val config = ProductionHttpsConfig(
            host = "127.0.0.1",
            port = 18444,
            keyStorePath = Path.of("/does/not/exist.p12"),
            keyStorePassword = TlsPassword.of("secret".toCharArray())
        )
        val listener = ProductionHttpsListener(
            config = config,
            handler = LicenseHttpsHandler {
                LicenseHttpResponse(503, null, byteArrayOf())
            },
            sslContextProvider = { throw IllegalStateException("TLS unavailable") }
        )

        config.use {
            assertEquals(LicensingRuntimeListenerResult.Failed, listener.start())
            assertTrue(listener.toString().contains("tls=<redacted>"))
            assertFalse(listener.toString().contains("TLS unavailable"))
        }
    }

    @Test
    fun duplicate_start_is_rejected() {
        val config = ProductionHttpsConfig(
            host = "127.0.0.1",
            port = 18445,
            keyStorePath = Path.of("/unused.p12"),
            keyStorePassword = TlsPassword.of("secret".toCharArray())
        )
        val listener = ProductionHttpsListener(
            config = config,
            handler = LicenseHttpsHandler {
                LicenseHttpResponse(503, null, byteArrayOf())
            },
            sslContextProvider = { SSLContext.getDefault() }
        )

        config.use {
            assertEquals(LicensingRuntimeListenerResult.Started, listener.start())
            try {
                assertEquals(LicensingRuntimeListenerResult.Failed, listener.start())
            } finally {
                listener.close()
            }
        }
    }
}
