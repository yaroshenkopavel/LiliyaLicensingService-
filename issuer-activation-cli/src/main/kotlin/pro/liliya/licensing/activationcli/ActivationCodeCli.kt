package pro.liliya.licensing.activationcli

import java.time.Instant
import pro.liliya.licensing.activation.ActivationCodeGenerator
import pro.liliya.licensing.openbao.OpenBaoTransitActivationCodeSigner
import pro.liliya.licensing.openbao.OpenBaoTransitEndpoint
import pro.liliya.licensing.openbao.OpenBaoTransitHttpClient

fun main(args: Array<String>) {
    val parsed = CliArgs.parse(args)
        ?: failUsage()

    val address = requiredEnv("LILIYA_OPENBAO_ADDRESS")
    val token = requiredEnv("LILIYA_OPENBAO_TOKEN")
    val keyId = requiredEnv("LILIYA_ACTIVATION_KEY_ID")
    val keyName = requiredEnv("LILIYA_ACTIVATION_OPENBAO_KEY_NAME")
    val keyVersion = requiredEnv("LILIYA_ACTIVATION_OPENBAO_KEY_VERSION")
        .toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error("invalid activation key version")

    val client = OpenBaoTransitHttpClient(
        endpoint = OpenBaoTransitEndpoint(address),
        tokenProvider = { token }
    )
    val signer = OpenBaoTransitActivationCodeSigner(
        client = client,
        keyName = keyName,
        keyVersion = keyVersion
    )
    val generator = ActivationCodeGenerator(
        keyId = keyId,
        signer = signer
    )

    val code = generator.generate(
        productId = parsed.productId,
        features = parsed.features,
        expiresAt = parsed.expiresAt,
        maxRedemptions = 1
    )

    println("ACTIVATION_CODE=$code")
    println("ACTIVATION_CODE_WARNING=Treat this unused code as a credential until redeemed.")
}

private data class CliArgs(
    val productId: String,
    val features: Set<String>,
    val expiresAt: Instant?
) {
    companion object {
        fun parse(args: Array<String>): CliArgs? {
            var product: String? = null
            var features: Set<String>? = null
            var expiresAt: Instant? = null
            var expirySeen = false
            var index = 0

            while (index < args.size) {
                when (args[index]) {
                    "--product" -> {
                        product = args.getOrNull(++index)?.takeIf { it.isNotBlank() }
                    }
                    "--features" -> {
                        features = args.getOrNull(++index)
                            ?.split(',')
                            ?.map(String::trim)
                            ?.filter(String::isNotBlank)
                            ?.toSet()
                            ?.takeIf(Set<String>::isNotEmpty)
                    }
                    "--expires-at" -> {
                        val raw = args.getOrNull(++index) ?: return null
                        expirySeen = true
                        expiresAt = if (raw == "never") {
                            null
                        } else {
                            runCatching { Instant.parse(raw) }.getOrNull()
                                ?: return null
                        }
                    }
                    else -> return null
                }
                index += 1
            }

            if (product == null || features == null || !expirySeen) return null
            return CliArgs(product, features, expiresAt)
        }
    }
}
private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required activation generator environment")

private fun failUsage(): Nothing {
    error(
        "usage: --product <id> --features <a,b> " +
            "--expires-at <ISO-8601|never>"
    )
}
