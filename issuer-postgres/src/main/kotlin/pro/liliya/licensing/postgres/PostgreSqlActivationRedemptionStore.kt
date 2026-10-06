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
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ): ActivationRedemptionStoreResult = try {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = reserveCode(
                    connection = connection,
                    claims = claims,
                    attemptId = attemptId,
                    subject = proposedSubject,
                    installationId = installationId,
                    deviceKeyFingerprint = deviceKeyFingerprint,
                    now = now
                )
                val result = if (inserted) {
                    insertEntitlement(connection, claims, proposedSubject, now)
                    insertDeviceBinding(
                        connection = connection,
                        subject = proposedSubject,
                        installationId = installationId,
                        deviceKeyFingerprint = deviceKeyFingerprint,
                        now = now
                    )
                    ActivationRedemptionStoreResult.Created(
                        record(
                            claims = claims,
                            attemptId = attemptId,
                            subject = proposedSubject,
                            installationId = installationId,
                            deviceKeyFingerprint = deviceKeyFingerprint,
                            redeemedAt = now
                        )
                    )
                } else {
                    existingResult(
                        connection = connection,
                        claims = claims,
                        attemptId = attemptId,
                        installationId = installationId,
                        deviceKeyFingerprint = deviceKeyFingerprint
                    )
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
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ): Boolean {
        connection.prepareStatement(
            """
            INSERT INTO licensing_activation_redemption (
                code_id, attempt_id, subject, product_id,
                installation_id, device_key_fingerprint, redeemed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (code_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.setString(2, attemptId)
            statement.setString(3, subject)
            statement.setString(4, claims.productId)
            statement.setString(5, installationId)
            statement.setString(6, deviceKeyFingerprint)
            statement.setTimestamp(7, java.sql.Timestamp.from(now))
            return statement.executeUpdate() == 1
        }
    }

    private fun existingResult(
        connection: Connection,
        claims: ActivationCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String
    ): ActivationRedemptionStoreResult {
        connection.prepareStatement(
            """
            SELECT
                attempt_id,
                subject,
                product_id,
                installation_id,
                device_key_fingerprint,
                redeemed_at
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
                if (
                    result.getString("installation_id") != installationId ||
                    result.getString("device_key_fingerprint") != deviceKeyFingerprint
                ) {
                    return ActivationRedemptionStoreResult.DeviceLimitReached
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
                    record(
                        claims = claims,
                        attemptId = attemptId,
                        subject = subject,
                        installationId = installationId,
                        deviceKeyFingerprint = deviceKeyFingerprint,
                        redeemedAt = redeemedAt
                    )
                )
            }
        }
    }

    private fun insertDeviceBinding(
        connection: Connection,
        subject: String,
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ) {
        connection.prepareStatement(
            """
            INSERT INTO licensing_device_binding (
                binding_id,
                subject,
                installation_id,
                device_key_fingerprint,
                status,
                bound_at,
                revoked_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, "bind-${UUID.randomUUID()}")
            statement.setString(2, subject)
            statement.setString(3, installationId)
            statement.setString(4, deviceKeyFingerprint)
            statement.setTimestamp(5, java.sql.Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun insertEntitlement(
        connection: Connection,
        claims: ActivationCodeClaims,
        subject: String,
        now: Instant
    ) {
        val requestedLifetime = claims.entitlementLifetimeSeconds
            ?.let(Duration::ofSeconds)
        val requestedOffline = claims.offlineLeaseSeconds
            ?.let(Duration::ofSeconds)

        if (
            policy.entitlementLifetime != null &&
            (
                requestedLifetime == null ||
                    requestedLifetime > policy.entitlementLifetime
                )
        ) {
            error("activation entitlement lifetime exceeds server policy")
        }
        if (
            policy.offlineLeaseDuration != null &&
            requestedOffline != null &&
            requestedOffline > policy.offlineLeaseDuration
        ) {
            error("activation offline lease exceeds server policy")
        }

        val expiresAt = requestedLifetime?.let(now::plus)
        val offlineLeaseUntil = requestedOffline
            ?.let(now::plus)
            ?.let { lease ->
                if (expiresAt != null && lease.isAfter(expiresAt)) expiresAt else lease
            }

        connection.prepareStatement(
            """
            INSERT INTO licensing_entitlement (
                license_id, subject, product_id, features, version,
                signing_key_id, issued_at, not_before, expires_at,
                offline_lease_until, revocation_epoch, device_binding_required
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, TRUE)
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
        installationId: String,
        deviceKeyFingerprint: String,
        redeemedAt: Instant
    ) = ActivationRedemptionRecord(
        codeId = claims.codeId,
        attemptId = attemptId,
        subject = subject,
        productId = claims.productId,
        features = claims.features,
        installationId = installationId,
        deviceKeyFingerprint = deviceKeyFingerprint,
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
                        installation_id TEXT NOT NULL
                            CHECK (length(btrim(installation_id)) > 0),
                        device_key_fingerprint TEXT NOT NULL
                            CHECK (length(btrim(device_key_fingerprint)) > 0),
                        redeemed_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_device_binding (
                        binding_id TEXT PRIMARY KEY
                            CHECK (length(btrim(binding_id)) > 0),
                        subject TEXT NOT NULL
                            CHECK (length(btrim(subject)) > 0),
                        installation_id TEXT NOT NULL
                            CHECK (length(btrim(installation_id)) > 0),
                        device_key_fingerprint TEXT NOT NULL
                            CHECK (length(btrim(device_key_fingerprint)) > 0),
                        status TEXT NOT NULL
                            CHECK (status IN ('ACTIVE', 'REVOKED')),
                        bound_at TIMESTAMPTZ NOT NULL,
                        revoked_at TIMESTAMPTZ,
                        CHECK (
                            (status = 'ACTIVE' AND revoked_at IS NULL) OR
                            (status = 'REVOKED' AND revoked_at IS NOT NULL)
                        )
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS licensing_device_binding_one_active_subject
                    ON licensing_device_binding(subject)
                    WHERE status = 'ACTIVE'
                    """.trimIndent()
                )
            }
        }
    }
}
