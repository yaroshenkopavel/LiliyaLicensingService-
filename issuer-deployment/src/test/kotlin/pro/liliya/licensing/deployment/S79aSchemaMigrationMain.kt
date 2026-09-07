package pro.liliya.licensing.deployment

import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.postgres.PostgreSqlDecisionTransactionPort

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
    println("LICENSING_S7_9A_SCHEMA_EVIDENCE={\"schemaInitializedBySeparateAdminHelper\":true}")
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing S7.9A acceptance environment")
