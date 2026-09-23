package pro.liliya.licensing.activation

import java.time.Duration
import java.time.Instant
import org.postgresql.ds.PGSimpleDataSource

/**
 * Small owner-side administration entrypoint.
 *
 * Secrets come only from environment variables. The activation code is printed exactly once after
 * a successful database insert; the plaintext code is never persisted by the service.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "usage: migrate <runtime-role> | provision <subject> <product-id> [ttl-minutes]"
    }

    val dataSource = PGSimpleDataSource().apply {
        setURL(requiredEnv("LILIYA_ACTIVATION_ADMIN_JDBC_URL"))
        user = requiredEnv("LILIYA_ACTIVATION_ADMIN_USERNAME")
        password = requiredEnv("LILIYA_ACTIVATION_ADMIN_PASSWORD")
    }
    val store = PostgreSqlActivationGrantStore(dataSource)

    when (args[0]) {
        "migrate" -> {
            require(args.size == 2) { "usage: migrate <runtime-role>" }
            val runtimeRole = validatedSqlIdentifier(args[1])
            store.initializeSchema()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("REVOKE ALL ON TABLE licensing_activation_grant FROM PUBLIC")
                    statement.execute(
                        "GRANT SELECT, UPDATE ON TABLE licensing_activation_grant TO " +
                            quoteIdentifier(runtimeRole)
                    )
                }
            }
            println("LILIYA_ACTIVATION_SCHEMA_READY")
        }

        "provision" -> {
            require(args.size in 3..4) {
                "usage: provision <subject> <product-id> [ttl-minutes]"
            }
            val subject = args[1]
            val productId = args[2]
            val ttlMinutes = args.getOrNull(3)?.toLongOrNull() ?: DEFAULT_TTL_MINUTES
            require(ttlMinutes in 1..MAX_TTL_MINUTES) {
                "ttl-minutes must be between 1 and $MAX_TTL_MINUTES"
            }

            val now = Instant.now()
            val result = ActivationProvisioningService(store).create(
                subject = subject,
                productId = productId,
                expiresAt = now.plus(Duration.ofMinutes(ttlMinutes)),
                now = now
            )

            when (result) {
                is ActivationProvisioningResult.Created -> {
                    result.code.use { code ->
                        code.useText { value ->
                            println("ACTIVATION_CODE=$value")
                        }
                    }
                }
                ActivationProvisioningResult.Failed ->
                    error("activation code was not created")
            }
        }

        else -> error("unknown activation admin command")
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing activation admin configuration")

private fun validatedSqlIdentifier(value: String): String {
    require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
        "invalid PostgreSQL role identifier"
    }
    return value
}

private fun quoteIdentifier(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

private const val DEFAULT_TTL_MINUTES = 60L
private const val MAX_TTL_MINUTES = 24L * 60L
