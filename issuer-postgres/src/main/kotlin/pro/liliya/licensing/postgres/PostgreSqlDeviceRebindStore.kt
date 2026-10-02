package pro.liliya.licensing.postgres

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import pro.liliya.licensing.activation.DeviceRebindCodeClaims
import pro.liliya.licensing.activation.DeviceRebindRecord
import pro.liliya.licensing.activation.DeviceRebindStore
import pro.liliya.licensing.activation.DeviceRebindStoreResult

class PostgreSqlDeviceRebindStore(
    private val dataSource: DataSource
) : DeviceRebindStore {
    override fun rebind(
        claims: DeviceRebindCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ): DeviceRebindStoreResult = try {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = reserveCode(
                    connection,
                    claims,
                    attemptId,
                    installationId,
                    deviceKeyFingerprint,
                    now
                )

                val result = if (!inserted) {
                    existingResult(
                        connection,
                        claims,
                        attemptId,
                        installationId,
                        deviceKeyFingerprint
                    )
                } else {
                    when (validateEntitlementForRebind(connection, claims)) {
                        EntitlementState.UNAVAILABLE ->
                            DeviceRebindStoreResult.EntitlementUnavailable
                        EntitlementState.ACTIVE_BINDING_EXISTS ->
                            DeviceRebindStoreResult.ActiveDeviceExists
                        EntitlementState.READY -> {
                            insertBinding(
                                connection,
                                claims.subject,
                                installationId,
                                deviceKeyFingerprint,
                                now
                            )
                            DeviceRebindStoreResult.Created(
                                record(
                                    claims,
                                    attemptId,
                                    installationId,
                                    deviceKeyFingerprint,
                                    now
                                )
                            )
                        }
                    }
                }

                when (result) {
                    DeviceRebindStoreResult.EntitlementUnavailable,
                    DeviceRebindStoreResult.ActiveDeviceExists -> connection.rollback()
                    else -> connection.commit()
                }
                result
            } catch (_: Throwable) {
                runCatching { connection.rollback() }
                DeviceRebindStoreResult.Failed
            }
        }
    } catch (_: Throwable) {
        DeviceRebindStoreResult.Failed
    }

    private fun reserveCode(
        connection: Connection,
        claims: DeviceRebindCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ): Boolean {
        connection.prepareStatement(
            """
            INSERT INTO licensing_device_rebind_redemption (
                code_id,
                attempt_id,
                subject,
                product_id,
                installation_id,
                device_key_fingerprint,
                redeemed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (code_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.setString(2, attemptId)
            statement.setString(3, claims.subject)
            statement.setString(4, claims.productId)
            statement.setString(5, installationId)
            statement.setString(6, deviceKeyFingerprint)
            statement.setTimestamp(7, java.sql.Timestamp.from(now))
            return statement.executeUpdate() == 1
        }
    }

    private fun validateEntitlementForRebind(
        connection: Connection,
        claims: DeviceRebindCodeClaims
    ): EntitlementState {
        connection.prepareStatement(
            """
            SELECT device_binding_required, revoked_at
            FROM licensing_entitlement
            WHERE subject = ?
              AND product_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.subject)
            statement.setString(2, claims.productId)
            statement.executeQuery().use { result ->
                if (!result.next()) return EntitlementState.UNAVAILABLE
                if (result.getTimestamp("revoked_at") != null) {
                    return EntitlementState.UNAVAILABLE
                }
                if (!result.getBoolean("device_binding_required")) {
                    return EntitlementState.UNAVAILABLE
                }
            }
        }

        connection.prepareStatement(
            """
            SELECT 1
            FROM licensing_device_binding
            WHERE subject = ?
              AND status = 'ACTIVE'
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.subject)
            statement.executeQuery().use { result ->
                if (result.next()) return EntitlementState.ACTIVE_BINDING_EXISTS
            }
        }

        return EntitlementState.READY
    }

    private fun insertBinding(
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

    private fun existingResult(
        connection: Connection,
        claims: DeviceRebindCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String
    ): DeviceRebindStoreResult {
        connection.prepareStatement(
            """
            SELECT
                attempt_id,
                subject,
                product_id,
                installation_id,
                device_key_fingerprint,
                redeemed_at
            FROM licensing_device_rebind_redemption
            WHERE code_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.executeQuery().use { result ->
                if (!result.next()) return DeviceRebindStoreResult.Failed

                val same =
                    result.getString("attempt_id") == attemptId &&
                    result.getString("subject") == claims.subject &&
                    result.getString("product_id") == claims.productId &&
                    result.getString("installation_id") == installationId &&
                    result.getString("device_key_fingerprint") == deviceKeyFingerprint

                if (!same) return DeviceRebindStoreResult.Exhausted

                val reboundAt = result.getTimestamp("redeemed_at")?.toInstant()
                    ?: return DeviceRebindStoreResult.Failed

                return DeviceRebindStoreResult.Replay(
                    record(
                        claims,
                        attemptId,
                        installationId,
                        deviceKeyFingerprint,
                        reboundAt
                    )
                )
            }
        }
    }

    private fun record(
        claims: DeviceRebindCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String,
        reboundAt: Instant
    ) = DeviceRebindRecord(
        codeId = claims.codeId,
        attemptId = attemptId,
        subject = claims.subject,
        productId = claims.productId,
        installationId = installationId,
        deviceKeyFingerprint = deviceKeyFingerprint,
        reboundAt = reboundAt
    )

    private enum class EntitlementState {
        READY,
        UNAVAILABLE,
        ACTIVE_BINDING_EXISTS
    }
}

object PostgreSqlDeviceRebindSchema {
    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_device_rebind_redemption (
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
            }
        }
    }
}
