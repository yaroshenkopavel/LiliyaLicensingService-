package pro.liliya.licensing.deployment

import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.postgres.PostgreSqlDecisionTransactionPort
import pro.liliya.licensing.postgres.PostgreSqlEntitlementSchema

fun main() {
    val url = requiredEnv("S7_9A_POSTGRES_URL")
    val user = requiredEnv("S7_9A_POSTGRES_ADMIN_USER")
    val password = requiredEnv("S7_9A_POSTGRES_ADMIN_PASSWORD")

    val dataSource = PGSimpleDataSource().apply {
        setURL(url)
        this.user = user
        this.password = password
    }

    PostgreSqlDecisionTransactionPort(dataSource).initializeSchema()
    PostgreSqlEntitlementSchema.initialize(dataSource)
    println(
        "LICENSING_S7_9A_SCHEMA_EVIDENCE=" +
            "{\"schemaInitializedBySeparateAdminHelper\":true," +
            "\"entitlementSchemaInitialized\":true," +
            "\"entitlementRowsCreated\":false}"
    )
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing S7.9A acceptance environment")
