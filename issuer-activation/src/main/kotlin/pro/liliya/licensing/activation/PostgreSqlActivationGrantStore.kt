package pro.liliya.licensing.activation

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * PostgreSQL one-time activation store.
 *
 * Only the SHA-256 hash of the high-entropy activation code is persisted. The plaintext activation
 * code is returned once to the owner by ActivationProvisioningService and is never stored here.
 *
 * This store is an administrative/provisioning boundary. It must use a database identity with only
 * the permissions required for this table; it is not the normal read-only entitlement runtime role.
 */
class PostgreSqlActivationGrantStore(
    private val dataSource: DataSource
) : ActivationGrantStore {

    fun initializeSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_activation_grant (
                        code_hash BYTEA PRIMARY KEY
                            CHECK (octet_length(code_hash) = 32),
                        subject TEXT NOT NULL
                            CHECK (length(subject) > 0),
                        product_id TEXT NOT NULL
                            CHECK (length(product_id) > 0),
                        created_at TIMESTAMPTZ NOT NULL,
                        expires_at TIMESTAMPTZ NOT NULL,
                        redeemed_at TIMESTAMPTZ NULL,
                        CHECK (expires_at > created_at),
                        FOREIGN KEY (subject, product_id)
                            REFERENCES licensing_entitlement(subject, product_id)
                            ON UPDATE RESTRICT
                            ON DELETE RESTRICT
                    )
                    """.trimIndent()
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
                        code_hash,
                        subject,
                        product_id,
                        created_at,
                        expires_at,
                        redeemed_at
                    ) VALUES (?, ?, ?, ?, ?, NULL)
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

    override fun redeem(
        codeHash: ActivationCodeHash,
        now: Instant
    ): ActivationRedemptionResult =
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED

                try {
                    val current = connection.prepareStatement(
                        """
                        SELECT subject, product_id, expires_at, redeemed_at
                        FROM licensing_activation_grant
                        WHERE code_hash = ?
                        FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setBytes(1, codeHash.copyBytes())
                        statement.executeQuery().use { result ->
                            if (!result.next()) {
                                connection.rollback()
                                return ActivationRedemptionResult.Invalid
                            }

                            StoredGrant(
                                subject = result.getString("subject"),
                                productId = result.getString("product_id"),
                                expiresAt = result.getObject("expires_at", java.time.OffsetDateTime::class.java)
                                    .toInstant(),
                                redeemedAt = result
                                    .getObject("redeemed_at", java.time.OffsetDateTime::class.java)
                                    ?.toInstant()
                            )
                        }
                    }

                    if (current.redeemedAt != null) {
                        connection.rollback()
                        return ActivationRedemptionResult.AlreadyRedeemed
                    }

                    if (!now.isBefore(current.expiresAt)) {
                        connection.rollback()
                        return ActivationRedemptionResult.Expired
                    }

                    val updated = connection.prepareStatement(
                        """
                        UPDATE licensing_activation_grant
                        SET redeemed_at = ?
                        WHERE code_hash = ?
                          AND redeemed_at IS NULL
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                        statement.setBytes(2, codeHash.copyBytes())
                        statement.executeUpdate()
                    }

                    if (updated != 1) {
                        connection.rollback()
                        return ActivationRedemptionResult.Unavailable
                    }

                    connection.commit()
                    ActivationRedemptionResult.Accepted(
                        subject = current.subject,
                        productId = current.productId
                    )
                } catch (_: SQLException) {
                    safeRollback(connection)
                    ActivationRedemptionResult.Unavailable
                } catch (_: RuntimeException) {
                    safeRollback(connection)
                    ActivationRedemptionResult.Unavailable
                }
            }
        } catch (_: SQLException) {
            ActivationRedemptionResult.Unavailable
        } catch (_: RuntimeException) {
            ActivationRedemptionResult.Unavailable
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
        val redeemedAt: Instant?
    )
}
