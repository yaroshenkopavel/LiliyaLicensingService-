package pro.liliya.licensing.activation

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * PostgreSQL one-time activation store.
 *
 * Only hashes / opaque signed responses are persisted. Plaintext activation codes are never stored.
 * A request id claims a code before issuer work begins. Once issuance succeeds, the exact wire
 * response is persisted with redemption so a lost HTTP response can be replayed safely.
 */
class PostgreSqlActivationGrantStore(
    private val dataSource: DataSource
) : ActivationGrantStore {

    fun verifySchema() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT code_hash, subject, product_id, created_at, expires_at, redeemed_at,
                       claim_request_id, completed_response
                FROM licensing_activation_grant
                WHERE FALSE
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { }
            }
        }
    }

    fun initializeSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_activation_grant (
                        code_hash BYTEA PRIMARY KEY CHECK (octet_length(code_hash) = 32),
                        subject TEXT NOT NULL CHECK (length(subject) > 0),
                        product_id TEXT NOT NULL CHECK (length(product_id) > 0),
                        created_at TIMESTAMPTZ NOT NULL,
                        expires_at TIMESTAMPTZ NOT NULL,
                        redeemed_at TIMESTAMPTZ NULL,
                        claim_request_id TEXT NULL,
                        completed_response BYTEA NULL,
                        CHECK (expires_at > created_at),
                        CHECK (claim_request_id IS NULL OR length(claim_request_id) > 0),
                        FOREIGN KEY (subject, product_id)
                            REFERENCES licensing_entitlement(subject, product_id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "ALTER TABLE licensing_activation_grant ADD COLUMN IF NOT EXISTS claim_request_id TEXT NULL"
                )
                statement.execute(
                    "ALTER TABLE licensing_activation_grant ADD COLUMN IF NOT EXISTS completed_response BYTEA NULL"
                )
            }
        }
    }

    override fun create(grant: ActivationGrant): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO licensing_activation_grant(
                        code_hash, subject, product_id, created_at, expires_at,
                        redeemed_at, claim_request_id, completed_response
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL)
                    ON CONFLICT (code_hash) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setBytes(1, grant.codeHash.copyBytes())
                    statement.setString(2, grant.subject)
                    statement.setString(3, grant.productId)
                    statement.setObject(4, grant.createdAt.atOffset(ZoneOffset.UTC))
                    statement.setObject(5, grant.expiresAt.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate() == 1
                }
            }
        } catch (_: SQLException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    override fun prepare(
        codeHash: ActivationCodeHash,
        requestId: String,
        now: Instant
    ): ActivationPreparationResult =
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                try {
                    val current = loadLocked(connection, codeHash)
                        ?: return rollback(connection, ActivationPreparationResult.Invalid)

                    if (!now.isBefore(current.expiresAt)) {
                        return rollback(connection, ActivationPreparationResult.Expired)
                    }

                    if (current.redeemedAt != null) {
                        return if (
                            current.claimRequestId == requestId &&
                            current.completedResponse != null
                        ) {
                            val body = current.completedResponse.copyOf()
                            connection.rollback()
                            ActivationPreparationResult.Completed(body)
                        } else {
                            rollback(connection, ActivationPreparationResult.AlreadyRedeemed)
                        }
                    }

                    if (current.claimRequestId != null && current.claimRequestId != requestId) {
                        return rollback(connection, ActivationPreparationResult.InProgress)
                    }

                    if (current.claimRequestId == null) {
                        val updated = connection.prepareStatement(
                            """
                            UPDATE licensing_activation_grant
                            SET claim_request_id = ?
                            WHERE code_hash = ? AND claim_request_id IS NULL AND redeemed_at IS NULL
                            """.trimIndent()
                        ).use { statement ->
                            statement.setString(1, requestId)
                            statement.setBytes(2, codeHash.copyBytes())
                            statement.executeUpdate()
                        }
                        if (updated != 1) {
                            return rollback(connection, ActivationPreparationResult.Unavailable)
                        }
                    }

                    connection.commit()
                    ActivationPreparationResult.Accepted(
                        ActivationPreparedGrant(
                            subject = current.subject,
                            productId = current.productId,
                            codeHash = codeHash,
                            requestId = requestId
                        )
                    )
                } catch (_: SQLException) {
                    safeRollback(connection)
                    ActivationPreparationResult.Unavailable
                } catch (_: RuntimeException) {
                    safeRollback(connection)
                    ActivationPreparationResult.Unavailable
                }
            }
        } catch (_: SQLException) {
            ActivationPreparationResult.Unavailable
        } catch (_: RuntimeException) {
            ActivationPreparationResult.Unavailable
        }

    override fun complete(
        prepared: ActivationPreparedGrant,
        responseBody: ByteArray,
        now: Instant
    ): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE licensing_activation_grant
                    SET redeemed_at = ?, completed_response = ?
                    WHERE code_hash = ?
                      AND claim_request_id = ?
                      AND redeemed_at IS NULL
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                    statement.setBytes(2, responseBody.copyOf())
                    statement.setBytes(3, prepared.codeHash.copyBytes())
                    statement.setString(4, prepared.requestId)
                    statement.executeUpdate() == 1
                }
            }
        } catch (_: SQLException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun loadLocked(
        connection: Connection,
        codeHash: ActivationCodeHash
    ): StoredGrant? =
        connection.prepareStatement(
            """
            SELECT subject, product_id, expires_at, redeemed_at, claim_request_id, completed_response
            FROM licensing_activation_grant
            WHERE code_hash = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setBytes(1, codeHash.copyBytes())
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                StoredGrant(
                    subject = result.getString("subject"),
                    productId = result.getString("product_id"),
                    expiresAt = result.getObject("expires_at", java.time.OffsetDateTime::class.java)
                        .toInstant(),
                    redeemedAt = result.getObject(
                        "redeemed_at",
                        java.time.OffsetDateTime::class.java
                    )?.toInstant(),
                    claimRequestId = result.getString("claim_request_id"),
                    completedResponse = result.getBytes("completed_response")
                )
            }
        }

    private fun <T> rollback(connection: Connection, value: T): T {
        connection.rollback()
        return value
    }

    private fun safeRollback(connection: Connection) {
        try {
            connection.rollback()
        } catch (_: SQLException) {
            // Fail closed.
        }
    }

    private data class StoredGrant(
        val subject: String,
        val productId: String,
        val expiresAt: Instant,
        val redeemedAt: Instant?,
        val claimRequestId: String?,
        val completedResponse: ByteArray?
    )
}
