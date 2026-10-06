package pro.liliya.licensing.activationcli

import java.time.Instant
import pro.liliya.licensing.activation.ActivationCodeGenerator
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

    val code = ActivationCodeGenerator(keyId, signer).generate(
        productId = parsed.productId,
        features = parsed.features,
        expiresAt = parsed.activationExpiresAt,
        entitlementLifetimeSeconds = parsed.entitlementLifetimeSeconds,
        offlineLeaseSeconds = parsed.offlineLeaseSeconds,
        maxRedemptions = 1
    )

    println("ACTIVATION_CODE=$code")
    println("DEVICE_POLICY=ONE_ACTIVE_DEVICE")
    println(
        "OFFLINE_POLICY=" +
            (parsed.offlineLeaseSeconds?.let { "FINITE_SECONDS:$it" } ?: "UNLIMITED")
    )
    println("ACTIVATION_CODE_WARNING=Treat this unused code as a credential until redeemed.")
}

internal data class CliArgs(
    val productId: String,
    val features: Set<String>,
    val activationExpiresAt: Instant?,
    val entitlementLifetimeSeconds: Long?,
    val offlineLeaseSeconds: Long?
) {
    companion object {
        fun parse(args: Array<String>): CliArgs? {
            var product: String? = null
            var features: Set<String>? = null
            var activationExpiresAt: Instant? = null
            var activationExpirySeen = false
            var entitlementLifetimeSeconds: Long? = null
            var entitlementLifetimeSeen = false
            var offlineLeaseSeconds: Long? = null
            var offlineLeaseSeen = false
            var allowFiniteOfflineLease = false
            var index = 0

            while (index < args.size) {
                when (args[index]) {
                    "--product" ->
                        product = args.getOrNull(++index)?.takeIf { it.isNotBlank() }

                    "--features" ->
                        features = args.getOrNull(++index)
                            ?.split(',')
                            ?.map(String::trim)
                            ?.filter(String::isNotBlank)
                            ?.toSet()
                            ?.takeIf(Set<String>::isNotEmpty)

                    "--activation-expires-at" -> {
                        val raw = args.getOrNull(++index) ?: return null
                        activationExpirySeen = true
                        activationExpiresAt = if (raw == "never") null
                        else runCatching { Instant.parse(raw) }.getOrNull() ?: return null
                    }

                    "--license-seconds" -> {
                        val raw = args.getOrNull(++index) ?: return null
                        entitlementLifetimeSeen = true
                        entitlementLifetimeSeconds =
                            if (raw == "never") null
                            else raw.toLongOrNull()?.takeIf { it > 0 } ?: return null
                    }

                    "--offline-seconds" -> {
                        val raw = args.getOrNull(++index) ?: return null
                        offlineLeaseSeen = true
                        offlineLeaseSeconds =
                            if (raw == "none") null
                            else raw.toLongOrNull()?.takeIf { it > 0 } ?: return null
                    }

                    "--allow-finite-offline-lease" ->
                        allowFiniteOfflineLease = true

                    else -> return null
                }
                index += 1
            }

            if (
                product == null ||
                features == null ||
                !activationExpirySeen ||
                !entitlementLifetimeSeen
            ) return null

            if (!offlineLeaseSeen) {
                offlineLeaseSeconds = null
            }

            if (offlineLeaseSeconds != null && !allowFiniteOfflineLease) {
                return null
            }
            if (allowFiniteOfflineLease && offlineLeaseSeconds == null) {
                return null
            }
            if (
                entitlementLifetimeSeconds != null &&
                offlineLeaseSeconds != null &&
                offlineLeaseSeconds > entitlementLifetimeSeconds
            ) return null

            return CliArgs(
                productId = product,
                features = features,
                activationExpiresAt = activationExpiresAt,
                entitlementLifetimeSeconds = entitlementLifetimeSeconds,
                offlineLeaseSeconds = offlineLeaseSeconds
            )
        }
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required activation generator environment")

private fun failUsage(): Nothing =
    error(
        "usage: --product <id> --features <a,b> " +
            "--activation-expires-at <ISO-8601|never> " +
            "--license-seconds <seconds|never> " +
            "[--offline-seconds <none|seconds> [--allow-finite-offline-lease]]"
    )
