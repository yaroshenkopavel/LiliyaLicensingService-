package pro.liliya.licensing.rebindcli

import java.time.Instant
import pro.liliya.licensing.activation.DeviceRebindCodeGenerator
import pro.liliya.licensing.openbao.OpenBaoTransitActivationCodeSigner
import pro.liliya.licensing.openbao.OpenBaoTransitEndpoint
import pro.liliya.licensing.openbao.OpenBaoTransitHttpClient

fun main(args: Array<String>) {
    val parsed = CliArgs.parse(args) ?: failUsage()

    val address = requiredEnv("LILIYA_OPENBAO_ADDRESS")
    val token = requiredEnv("LILIYA_OPENBAO_TOKEN")
    val keyId = requiredEnv("LILIYA_ACTIVATION_KEY_ID")
    val keyName = requiredEnv("LILIYA_ACTIVATION_OPENBAO_KEY_NAME")
    val keyVersion = requiredEnv("LILIYA_ACTIVATION_OPENBAO_KEY_VERSION")
        .toIntOrNull()?.takeIf { it > 0 }
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

    val code = DeviceRebindCodeGenerator(
        keyId = keyId,
        signer = signer
    ).generate(
        subject = parsed.subject,
        productId = parsed.productId,
        expiresAt = parsed.expiresAt
    )

    println("DEVICE_REBIND_CODE=$code")
    println("DEVICE_REBIND_POLICY=ONE_TIME_AFTER_OLD_BINDING_REVOKED")
    println("DEVICE_REBIND_WARNING=Treat this unused code as a credential until redeemed.")
}

private data class CliArgs(
    val subject: String,
    val productId: String,
    val expiresAt: Instant?
) {
    companion object {
        fun parse(args: Array<String>): CliArgs? {
            var subject: String? = null
            var product: String? = null
            var expiresAt: Instant? = null
            var expirySeen = false
            var index = 0

            while (index < args.size) {
                when (args[index]) {
                    "--subject" ->
                        subject = args.getOrNull(++index)?.takeIf { it.isNotBlank() }

                    "--product" ->
                        product = args.getOrNull(++index)?.takeIf { it.isNotBlank() }

                    "--expires-at" -> {
                        val raw = args.getOrNull(++index) ?: return null
                        expirySeen = true
                        expiresAt =
                            if (raw == "never") null
                            else runCatching { Instant.parse(raw) }.getOrNull()
                                ?: return null
                    }

                    else -> return null
                }
                index += 1
            }

            if (subject == null || product == null || !expirySeen) return null
            return CliArgs(subject, product, expiresAt)
        }
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required device rebind generator environment")

private fun failUsage(): Nothing =
    error(
        "usage: --subject <subject> --product <product-id> " +
            "--expires-at <ISO-8601|never>"
    )
