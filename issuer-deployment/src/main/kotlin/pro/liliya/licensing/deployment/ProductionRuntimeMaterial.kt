package pro.liliya.licensing.deployment

import java.nio.file.Path

data class ProductionRuntimeMaterial(
    val tlsKeyStorePath: Path,
    val tlsKeyStorePassword: DeploymentSecret,
    val openBaoKeyName: String,
    val openBaoKeyVersion: Int
) : AutoCloseable {
    init {
        require(openBaoKeyName.isNotBlank()) { "OpenBao key name must not be blank" }
        require(openBaoKeyVersion > 0) { "OpenBao key version must be positive" }
    }

    override fun close() {
        tlsKeyStorePassword.close()
    }

    override fun toString(): String =
        "ProductionRuntimeMaterial(tlsKeyStorePath=<redacted>," +
            "tlsKeyStorePassword=<redacted>,openBaoKeyName=" + openBaoKeyName +
            ",openBaoKeyVersion=" + openBaoKeyVersion + ")"
}

enum class ProductionRuntimeMaterialKey(
    val environmentName: String,
    val secret: Boolean
) {
    TLS_KEYSTORE_PATH("LILIYA_TLS_KEYSTORE_PATH", false),
    TLS_KEYSTORE_PASSWORD("LILIYA_TLS_KEYSTORE_PASSWORD", true),
    OPENBAO_KEY_NAME("LILIYA_OPENBAO_KEY_NAME", false),
    OPENBAO_KEY_VERSION("LILIYA_OPENBAO_KEY_VERSION", false)
}

enum class ProductionRuntimeMaterialFailureReason {
    MISSING_REQUIRED_VALUE,
    INVALID_VALUE
}

data class ProductionRuntimeMaterialFailure(
    val key: ProductionRuntimeMaterialKey,
    val reason: ProductionRuntimeMaterialFailureReason
) {
    override fun toString(): String =
        "ProductionRuntimeMaterialFailure(key=" + key +
            ",reason=" + reason + ",value=<redacted>)"
}

sealed interface ProductionRuntimeMaterialLoadResult {
    data class Loaded(val material: ProductionRuntimeMaterial) :
        ProductionRuntimeMaterialLoadResult

    data class Rejected(val failure: ProductionRuntimeMaterialFailure) :
        ProductionRuntimeMaterialLoadResult
}

class ProductionRuntimeMaterialLoader(
    private val source: DeploymentEnvironmentSource
) {
    fun load(): ProductionRuntimeMaterialLoadResult {
        val values = LinkedHashMap<ProductionRuntimeMaterialKey, String>()
        for (key in ProductionRuntimeMaterialKey.entries) {
            val value = source.read(key.environmentName)
            if (value.isNullOrBlank()) {
                return rejected(
                    key,
                    ProductionRuntimeMaterialFailureReason.MISSING_REQUIRED_VALUE
                )
            }
            values[key] = value
        }

        val path = runCatching {
            Path.of(values.getValue(ProductionRuntimeMaterialKey.TLS_KEYSTORE_PATH))
        }.getOrNull()
            ?: return rejected(
                ProductionRuntimeMaterialKey.TLS_KEYSTORE_PATH,
                ProductionRuntimeMaterialFailureReason.INVALID_VALUE
            )

        val keyName = values.getValue(ProductionRuntimeMaterialKey.OPENBAO_KEY_NAME)
        if (keyName.isBlank()) {
            return rejected(
                ProductionRuntimeMaterialKey.OPENBAO_KEY_NAME,
                ProductionRuntimeMaterialFailureReason.INVALID_VALUE
            )
        }

        val keyVersion = values.getValue(
            ProductionRuntimeMaterialKey.OPENBAO_KEY_VERSION
        ).toIntOrNull()
        if (keyVersion == null || keyVersion <= 0) {
            return rejected(
                ProductionRuntimeMaterialKey.OPENBAO_KEY_VERSION,
                ProductionRuntimeMaterialFailureReason.INVALID_VALUE
            )
        }

        return ProductionRuntimeMaterialLoadResult.Loaded(
            ProductionRuntimeMaterial(
                tlsKeyStorePath = path,
                tlsKeyStorePassword = DeploymentSecret.of(
                    values.getValue(
                        ProductionRuntimeMaterialKey.TLS_KEYSTORE_PASSWORD
                    ).toCharArray()
                ),
                openBaoKeyName = keyName,
                openBaoKeyVersion = keyVersion
            )
        )
    }

    private fun rejected(
        key: ProductionRuntimeMaterialKey,
        reason: ProductionRuntimeMaterialFailureReason
    ): ProductionRuntimeMaterialLoadResult.Rejected =
        ProductionRuntimeMaterialLoadResult.Rejected(
            ProductionRuntimeMaterialFailure(key, reason)
        )
}
