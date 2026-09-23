package pro.liliya.licensing.activation

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PostgreSqlActivationStore(
    private val dataSource: DataSource
) : ActivationStorePort {
    override fun consume(
        activationCodeDigest: ByteArray,
        clientCredentialDigest: ByteArray,
        now: Instant
    ): ActivationStoreConsumeResult =
        try {
            dataSource.connection.use { connection ->
                consumeTransaction(
                    connection = connection,
                    activationCodeDigest = activationCodeDigest,
                    clientCredentialDigest = clientCredentialDigest,
                    now = now
                )
            }
        } catch (_: Exception) {
            ActivationStoreConsumeResult.Failed
        }

    override fun lookupCredential(
        clientCredentialDigest: ByteArray
    ): ActivationCredentialLookupResult =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT subject, product_id
                    FROM licensing_client_credential
                    WHERE credential_hash = ?
                      AND revoked_at IS NULL
                    """.trimIndent()
                ).use { statement ->
                    statement.setBytes(1, clientCredentialDigest)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            return ActivationCredentialLookupResult.Rejected
                        }

                        val subject = result.getString("subject")
                        val productId = result.getString("product_id")

                        if (
                            subject.isNullOrBlank() ||
                            productId.isNullOrBlank() ||
                            result.next()
                        ) {
                            return ActivationCredentialLookupResult.Failed
                        }

                        ActivationCredentialLookupResult.Active(
                            ActivationCredentialIdentity(
                                subject = subject,
                                productId = productId
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            ActivationCredentialLookupResult.Failed
        }

    override fun revokeCredential(
        clientCredentialDigest: ByteArray,
        now: Instant
    ): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE licensing_client_credential
                    SET revoked_at = ?
                    WHERE credential_hash = ?
                      AND revoked_at IS NULL
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setBytes(2, clientCredentialDigest)
                    statement.executeUpdate() == 1
                }
            }
        } catch (_: Exception) {
            false
        }

    private fun consumeTransaction(
        connection: Connection,
        activationCodeDigest: ByteArray,
        clientCredentialDigest: ByteArray,
        now: Instant
    ): ActivationStoreConsumeResult {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false

        return try {
            val grant = connection.prepareStatement(
                """
                UPDATE licensing_activation_code
                SET consumed_at = ?
                WHERE code_hash = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                RETURNING subject, product_id
                """.trimIndent()
            ).use { statement ->
                val timestamp = Timestamp.from(now)
                statement.setTimestamp(1, timestamp)
                statement.setBytes(2, activationCodeDigest)
                statement.setTimestamp(3, timestamp)

                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        val subject = result.getString("subject")
                        val productId = result.getString("product_id")
                        if (
                            subject.isNullOrBlank() ||
                            productId.isNullOrBlank() ||
                            result.next()
                        ) {
                            throw IllegalStateException("invalid activation grant")
                        }
                        ActivationGrant(subject, productId)
                    }
                }
            }

            if (grant == null) {
                connection.rollback()
                ActivationStoreConsumeResult.Rejected
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO licensing_client_credential(
                        credential_hash,
                        subject,
                        product_id,
                        issued_at,
                        revoked_at
                    )
                    VALUES (?, ?, ?, ?, NULL)
                    """.trimIndent()
                ).use { statement ->
                    statement.setBytes(1, clientCredentialDigest)
                    statement.setString(2, grant.subject)
                    statement.setString(3, grant.productId)
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.executeUpdate()
                }
                connection.commit()
                ActivationStoreConsumeResult.Consumed(grant)
            }
        } catch (_: Exception) {
            runCatching { connection.rollback() }
            ActivationStoreConsumeResult.Failed
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    override fun toString(): String =
        "PostgreSqlActivationStore(dataSource=<redacted>)"
}

object PostgreSqlActivationSchema {
    fun initialize(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS licensing_activation_code (
                    code_hash BYTEA PRIMARY KEY,
                    subject TEXT NOT NULL,
                    product_id TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    consumed_at TIMESTAMPTZ NULL,
                    CONSTRAINT licensing_activation_code_hash_length
                        CHECK (octet_length(code_hash) = 32),
                    CONSTRAINT licensing_activation_code_subject_nonblank
                        CHECK (btrim(subject) <> ''),
                    CONSTRAINT licensing_activation_code_product_nonblank
                        CHECK (btrim(product_id) <> ''),
                    CONSTRAINT licensing_activation_code_expiry_after_creation
                        CHECK (expires_at > created_at)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS licensing_client_credential (
                    credential_hash BYTEA PRIMARY KEY,
                    subject TEXT NOT NULL,
                    product_id TEXT NOT NULL,
                    issued_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ NULL,
                    CONSTRAINT licensing_client_credential_hash_length
                        CHECK (octet_length(credential_hash) = 32),
                    CONSTRAINT licensing_client_credential_subject_nonblank
                        CHECK (btrim(subject) <> ''),
                    CONSTRAINT licensing_client_credential_product_nonblank
                        CHECK (btrim(product_id) <> ''),
                    CONSTRAINT licensing_client_credential_revocation_order
                        CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS licensing_activation_code_unconsumed_idx
                ON licensing_activation_code(expires_at)
                WHERE consumed_at IS NULL
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS licensing_client_credential_active_idx
                ON licensing_client_credential(subject, product_id)
                WHERE revoked_at IS NULL
                """.trimIndent()
            )
        }
    }
}
