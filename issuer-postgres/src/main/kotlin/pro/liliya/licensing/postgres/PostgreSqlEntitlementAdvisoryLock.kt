package pro.liliya.licensing.postgres

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection

internal object PostgreSqlEntitlementAdvisoryLock {
    fun acquire(
        connection: Connection,
        subject: String,
        productId: String
    ) {
        require(subject.isNotBlank())
        require(productId.isNotBlank())

        val subjectBytes = subject.toByteArray(StandardCharsets.UTF_8)
        val productBytes = productId.toByteArray(StandardCharsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").run {
                update(subjectBytes)
                update(0.toByte())
                update(productBytes)
                digest()
            }
        } finally {
            subjectBytes.fill(0)
            productBytes.fill(0)
        }
        val lockKey = try {
            ByteBuffer.wrap(digest).getLong()
        } finally {
            digest.fill(0)
        }

        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(?)"
        ).use { statement ->
            statement.setLong(1, lockKey)
            statement.executeQuery().use { result ->
                check(result.next()) {
                    "PostgreSQL entitlement advisory lock was not acquired"
                }
            }
        }
    }
}
