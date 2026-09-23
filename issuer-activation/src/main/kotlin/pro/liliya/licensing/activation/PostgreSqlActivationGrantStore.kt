package pro.liliya.licensing.activation

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * PostgreSQL activation + installation-credential store.
 *
 * Plaintext activation codes and installation secrets are never persisted. A code is claimed by
 * request id + installation binding. Completion atomically stores the signed activation response,
 * marks the code redeemed, and installs the hashed per-install credential.
 */
class PostgreSqlActivationGrantStore(
    private val dataSource: DataSource
) : ActivationGrantStore, InstallCredentialVerifier {

    fun verifySchema() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT code_hash, subject, product_id, created_at, expires_at, redeemed_at,
                       claim_request_id, claim_install_id, claim_secret_hash, completed_response
                FROM licensing_activation_grant
                WHERE FALSE
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { }
            }
            connection.prepareStatement(
                """
                SELECT install_id, subject, product_id, secret_hash, created_at, disabled_at
                FROM licensing_install_credential
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
                        claim_install_id TEXT NULL,
                        claim_secret_hash BYTEA NULL,
                        completed_response BYTEA NULL,
                        CHECK (expires_at > created_at),
                        CHECK (claim_request_id IS NULL OR length(claim_request_id) > 0),
                        CHECK (claim_install_id IS NULL OR length(claim_install_id) > 0),
                        CHECK (claim_secret_hash IS NULL OR octet_length(claim_secret_hash) = 32),
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
                    "ALTER TABLE licensing_activation_grant ADD COLUMN IF NOT EXISTS claim_install_id TEXT NULL"
                )
                statement.execute(
                    "ALTER TABLE licensing_activation_grant ADD COLUMN IF NOT EXISTS claim_secret_hash BYTEA NULL"
                )
                statement.execute(
                    "ALTER TABLE licensing_activation_grant ADD COLUMN IF NOT EXISTS completed_response BYTEA NULL"
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_install_credential (
                        install_id TEXT PRIMARY KEY CHECK (length(install_id) > 0),
                        subject TEXT NOT NULL CHECK (length(subject) > 0),
                        product_id TEXT NOT NULL CHECK (length(product_id) > 0),
                        secret_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(secret_hash) = 32),
                        created_at TIMESTAMPTZ NOT NULL,
                        disabled_at TIMESTAMPTZ NULL,
                        FOREIGN KEY (subject, product_id)
                            REFERENCES licensing_entitlement(subject, product_id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
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
                        code_hash, subject, product_id, created_at, expires_at,
                        redeemed_at, claim_request_id, claim_install_id, claim_secret_hash,
                        completed_response
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)
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
        installCredential: InstallCredentialBinding,
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
                            current.claimInstallId == installCredential.installId &&
                            current.claimSecretHash == installCredential.secretHash &&
                            current.completedResponse != null
                        ) {
                            val body = current.completedResponse.copyOf()
                            connection.rollback()
                            ActivationPreparationResult.Completed(body)
                        } else {
                            rollback(connection, ActivationPreparationResult.AlreadyRedeemed)
                        }
                    }

                    if (
                        current.claimRequestId != null &&
                        (
                            current.claimRequestId != requestId ||
                            current.claimInstallId != installCredential.installId ||
                            current.claimSecretHash != installCredential.secretHash
                        )
                    ) {
                        return rollback(connection, ActivationPreparationResult.InProgress)
                    }

                    if (current.claimRequestId == null) {
                        val updated = connection.prepareStatement(
                            """
                            UPDATE licensing_activation_grant
                            SET claim_request_id = ?,
                                claim_install_id = ?,
                                claim_secret_hash = ?
                            WHERE code_hash = ?
                              AND claim_request_id IS NULL
                              AND redeemed_at IS NULL
                            """.trimIndent()
                        ).use { statement ->
                            statement.setString(1, requestId)
                            statement.setString(2, installCredential.installId)
                            statement.setBytes(3, installCredential.secretHash.copyBytes())
                            statement.setBytes(4, codeHash.copyBytes())
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
                            requestId = requestId,
                            installCredential = installCredential
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
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                try {
                    val installed = connection.prepareStatement(
                        """
                        INSERT INTO licensing_install_credential(
                            install_id, subject, product_id, secret_hash, created_at, disabled_at
                        ) VALUES (?, ?, ?, ?, ?, NULL)
                        ON CONFLICT (install_id) DO NOTHING
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, prepared.installCredential.installId)
                        statement.setString(2, prepared.subject)
                        statement.setString(3, prepared.productId)
                        statement.setBytes(4, prepared.installCredential.secretHash.copyBytes())
                        statement.setObject(5, now.atOffset(ZoneOffset.UTC))
                        statement.executeUpdate()
                    }

                    if (installed != 1 && !existingCredentialMatches(connection, prepared)) {
                        return rollback(connection, false)
                    }

                    val redeemed = connection.prepareStatement(
                        """
                        UPDATE licensing_activation_grant
                        SET redeemed_at = ?, completed_response = ?
                        WHERE code_hash = ?
                          AND claim_request_id = ?
                          AND claim_install_id = ?
                          AND claim_secret_hash = ?
                          AND redeemed_at IS NULL
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                        statement.setBytes(2, responseBody.copyOf())
                        statement.setBytes(3, prepared.codeHash.copyBytes())
                        statement.setString(4, prepared.requestId)
                        statement.setString(5, prepared.installCredential.installId)
                        statement.setBytes(6, prepared.installCredential.secretHash.copyBytes())
                        statement.executeUpdate()
                    }

                    if (redeemed != 1) {
                        return rollback(connection, false)
                    }

                    connection.commit()
                    true
                } catch (_: SQLException) {
                    safeRollback(connection)
                    false
                } catch (_: RuntimeException) {
                    safeRollback(connection)
                    false
                }
            }
        } catch (_: SQLException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    override fun verify(
        subject: String,
        productId: String,
        secret: ByteArray,
        now: Instant
    ): InstallCredentialVerificationResult {
        if (subject.isBlank() || productId.isBlank()) {
            return InstallCredentialVerificationResult.INVALID
        }

        val hash = try {
            InstallCredentialHasher.sha256(secret)
        } catch (_: IllegalArgumentException) {
            return InstallCredentialVerificationResult.INVALID
        }

        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT 1
                    FROM licensing_install_credential
                    WHERE subject = ?
                      AND product_id = ?
                      AND secret_hash = ?
                      AND disabled_at IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, subject)
                    statement.setString(2, productId)
                    statement.setBytes(3, hash.copyBytes())
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            InstallCredentialVerificationResult.VALID
                        } else {
                            InstallCredentialVerificationResult.INVALID
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            InstallCredentialVerificationResult.UNAVAILABLE
        } catch (_: RuntimeException) {
            InstallCredentialVerificationResult.UNAVAILABLE
        }
    }

    private fun existingCredentialMatches(
        connection: Connection,
        prepared: ActivationPreparedGrant
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT subject, product_id, secret_hash, disabled_at
            FROM licensing_install_credential
            WHERE install_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, prepared.installCredential.installId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use false
                result.getString("subject") == prepared.subject &&
                    result.getString("product_id") == prepared.productId &&
                    InstallCredentialHash.of(result.getBytes("secret_hash")) ==
                        prepared.installCredential.secretHash &&
                    result.getObject("disabled_at") == null
            }
        }

    private fun loadLocked(
        connection: Connection,
        codeHash: ActivationCodeHash
    ): StoredGrant? =
        connection.prepareStatement(
            """
            SELECT subject, product_id, expires_at, redeemed_at,
                   claim_request_id, claim_install_id, claim_secret_hash, completed_response
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
                    claimInstallId = result.getString("claim_install_id"),
                    claimSecretHash = result.getBytes("claim_secret_hash")
                        ?.let(InstallCredentialHash::of),
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
        val claimInstallId: String?,
        val claimSecretHash: InstallCredentialHash?,
        val completedResponse: ByteArray?
    )
}
