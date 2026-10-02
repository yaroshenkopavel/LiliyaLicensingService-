package pro.liliya.licensing.postgres

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import pro.liliya.licensing.activation.ActivationCodeClaims
import pro.liliya.licensing.activation.ActivationRedemptionRecord
import pro.liliya.licensing.activation.ActivationRedemptionStore
import pro.liliya.licensing.activation.ActivationRedemptionStoreResult

data class ActivationEntitlementPolicy(
    val signingKeyId: String,
    val version: Long = 1,
    val entitlementLifetime: Duration? = null,
    val offlineLeaseDuration: Duration? = null
) {
    init {
        require(signingKeyId.isNotBlank())
        require(version > 0)
        require(entitlementLifetime == null || !entitlementLifetime.isNegative)
        require(offlineLeaseDuration == null || !offlineLeaseDuration.isNegative)
    }
}

class PostgreSqlActivationRedemptionStore(
    private val dataSource: DataSource,
    private val policy: ActivationEntitlementPolicy
) : ActivationRedemptionStore {
    override fun redeem(
        claims: ActivationCodeClaims,
        attemptId: String,
        proposedSubject: String,
        now: Instant
    ): ActivationRedemptionStoreResult = try {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = reserveCode(
                    connection, claims, attemptId, proposedSubject, now
                )
                val result = if (inserted) {
                    insertEntitlement(connection, claims, proposedSubject, now)
                    ActivationRedemptionStoreResult.Created(
                        record(claims, attemptId, proposedSubject, now)
                    )
                } else {
                    existingResult(connection, claims, attemptId)
                }
                connection.commit()
                result
            } catch (_: Throwable) {
                runCatching { connection.rollback() }
                ActivationRedemptionStoreResult.Failed
            }
        }
    } catch (_: Throwable) {
        ActivationRedemptionStoreResult.Failed
    }

    private fun reserveCode(
        connection: Connection,
        claims: ActivationCodeClaims,
        attemptId: String,
        subject: String,
        now: Instant
    ): Boolean {
        connection.prepareStatement(
            """
            INSERT INTO licensing_activation_redemption (
                code_id, attempt_id, subject, product_id, redeemed_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (code_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.setString(2, attemptId)
            statement.setString(3, subject)
            statement.setString(4, claims.productId)
            statement.setTimestamp(5, java.sql.Timestamp.from(now))
            return statement.executeUpdate() == 1
        }
    }

    private fun existingResult(
        connection: Connection,
        claims: ActivationCodeClaims,
        attemptId: String
    ): ActivationRedemptionStoreResult {
        connection.prepareStatement(
            """
            SELECT attempt_id, subject, product_id, redeemed_at
            FROM licensing_activation_redemption
            WHERE code_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.executeQuery().use { result ->
                if (!result.next()) return ActivationRedemptionStoreResult.Failed
                if (result.getString("attempt_id") != attemptId) {
                    return ActivationRedemptionStoreResult.Exhausted
                }
                val subject = result.getString("subject")
                    ?: return ActivationRedemptionStoreResult.Failed
                val product = result.getString("product_id")
                    ?: return ActivationRedemptionStoreResult.Failed
                if (product != claims.productId) {
                    return ActivationRedemptionStoreResult.Failed
                }
                val redeemedAt = result.getTimestamp("redeemed_at")?.toInstant()
                    ?: return ActivationRedemptionStoreResult.Failed
                return ActivationRedemptionStoreResult.Replay(
                    record(claims, attemptId, subject, redeemedAt)
                )
            }
        }
    }

    private fun insertEntitlement(
        connection: Connection,
        claims: ActivationCodeClaims,
        subject: String,
        now: Instant
    ) {
        val expiresAt = policy.entitlementLifetime?.let(now::plus)
        val offlineLeaseUntil = policy.offlineLeaseDuration
            ?.let(now::plus)
            ?.let { lease ->
                if (expiresAt != null && lease.isAfter(expiresAt)) expiresAt else lease
            }

        connection.prepareStatement(
            """
            INSERT INTO licensing_entitlement (
                license_id, subject, product_id, features, version,
                signing_key_id, issued_at, not_before, expires_at,
                offline_lease_until, revocation_epoch
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, "lic-${UUID.randomUUID()}")
            statement.setString(2, subject)
            statement.setString(3, claims.productId)
            val features = connection.createArrayOf("text", claims.features.toTypedArray())
            try {
                statement.setArray(4, features)
                statement.setLong(5, policy.version)
                statement.setString(6, policy.signingKeyId)
                statement.setTimestamp(7, java.sql.Timestamp.from(now))
                statement.setTimestamp(8, java.sql.Timestamp.from(now))
                statement.setTimestamp(
                    9, expiresAt?.let(java.sql.Timestamp::from)
                )
                statement.setTimestamp(
                    10, offlineLeaseUntil?.let(java.sql.Timestamp::from)
                )
                statement.executeUpdate()
            } finally {
                runCatching { features.free() }
            }
        }
    }

    private fun record(
        claims: ActivationCodeClaims,
        attemptId: String,
        subject: String,
        redeemedAt: Instant
    ) = ActivationRedemptionRecord(
        codeId = claims.codeId,
        attemptId = attemptId,
        subject = subject,
        productId = claims.productId,
        features = claims.features,
        redeemedAt = redeemedAt
    )
}

object PostgreSqlActivationRedemptionSchema {
    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_activation_redemption (
                        code_id TEXT PRIMARY KEY
                            CHECK (length(btrim(code_id)) > 0),
                        attempt_id TEXT NOT NULL
                            CHECK (length(btrim(attempt_id)) > 0),
                        subject TEXT NOT NULL
                            CHECK (length(btrim(subject)) > 0),
                        product_id TEXT NOT NULL
                            CHECK (length(btrim(product_id)) > 0),
                        redeemed_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
