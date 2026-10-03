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
                        EntitlementState.STALE_BINDING_EPOCH ->
                            DeviceRebindStoreResult.StaleBindingEpoch
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
                    DeviceRebindStoreResult.ActiveDeviceExists,
                    DeviceRebindStoreResult.StaleBindingEpoch -> connection.rollback()
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
                device_binding_epoch,
                installation_id,
                device_key_fingerprint,
                redeemed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (code_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.codeId)
            statement.setString(2, attemptId)
            statement.setString(3, claims.subject)
            statement.setString(4, claims.productId)
            statement.setLong(5, claims.deviceBindingEpoch)
            statement.setString(6, installationId)
            statement.setString(7, deviceKeyFingerprint)
            statement.setTimestamp(8, java.sql.Timestamp.from(now))
            return statement.executeUpdate() == 1
        }
    }

    private fun validateEntitlementForRebind(
        connection: Connection,
        claims: DeviceRebindCodeClaims
    ): EntitlementState {
        PostgreSqlEntitlementAdvisoryLock.acquire(
            connection,
            claims.subject,
            claims.productId
        )
        connection.prepareStatement(
            """
            SELECT device_binding_required, revoked_at, device_binding_epoch
            FROM licensing_entitlement
            WHERE subject = ?
              AND product_id = ?
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
                if (
                    result.getLong("device_binding_epoch") !=
                    claims.deviceBindingEpoch
                ) {
                    return EntitlementState.STALE_BINDING_EPOCH
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
                device_binding_epoch,
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

                val storedBindingEpoch =
                    (result.getObject("device_binding_epoch") as? Number)?.toLong()
                val same =
                    result.getString("attempt_id") == attemptId &&
                    result.getString("subject") == claims.subject &&
                    result.getString("product_id") == claims.productId &&
                    storedBindingEpoch == claims.deviceBindingEpoch &&
                    result.getString("installation_id") == installationId &&
                    result.getString("device_key_fingerprint") == deviceKeyFingerprint

                if (!same) return DeviceRebindStoreResult.Exhausted

                when (
                    validateReplayStillCurrent(
                        connection = connection,
                        claims = claims,
                        installationId = installationId,
                        deviceKeyFingerprint = deviceKeyFingerprint
                    )
                ) {
                    ReplayState.CURRENT -> Unit
                    ReplayState.ENTITLEMENT_UNAVAILABLE ->
                        return DeviceRebindStoreResult.EntitlementUnavailable
                    ReplayState.STALE_BINDING_STATE ->
                        return DeviceRebindStoreResult.StaleBindingEpoch
                }

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

    private fun validateReplayStillCurrent(
        connection: Connection,
        claims: DeviceRebindCodeClaims,
        installationId: String,
        deviceKeyFingerprint: String
    ): ReplayState {
        PostgreSqlEntitlementAdvisoryLock.acquire(
            connection,
            claims.subject,
            claims.productId
        )
        connection.prepareStatement(
            """
            SELECT revoked_at, device_binding_required, device_binding_epoch
            FROM licensing_entitlement
            WHERE subject = ?
              AND product_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.subject)
            statement.setString(2, claims.productId)
            statement.executeQuery().use { result ->
                if (!result.next()) return ReplayState.ENTITLEMENT_UNAVAILABLE
                if (result.getTimestamp("revoked_at") != null) {
                    return ReplayState.ENTITLEMENT_UNAVAILABLE
                }
                if (!result.getBoolean("device_binding_required")) {
                    return ReplayState.ENTITLEMENT_UNAVAILABLE
                }
                if (
                    result.getLong("device_binding_epoch") !=
                    claims.deviceBindingEpoch
                ) {
                    return ReplayState.STALE_BINDING_STATE
                }
            }
        }

        connection.prepareStatement(
            """
            SELECT 1
            FROM licensing_device_binding
            WHERE subject = ?
              AND installation_id = ?
              AND device_key_fingerprint = ?
              AND status = 'ACTIVE'
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, claims.subject)
            statement.setString(2, installationId)
            statement.setString(3, deviceKeyFingerprint)
            statement.executeQuery().use { result ->
                if (!result.next()) return ReplayState.STALE_BINDING_STATE
            }
        }

        return ReplayState.CURRENT
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
        deviceBindingEpoch = claims.deviceBindingEpoch,
        installationId = installationId,
        deviceKeyFingerprint = deviceKeyFingerprint,
        reboundAt = reboundAt
    )

    private enum class EntitlementState {
        READY,
        UNAVAILABLE,
        ACTIVE_BINDING_EXISTS,
        STALE_BINDING_EPOCH
    }

    private enum class ReplayState {
        CURRENT,
        ENTITLEMENT_UNAVAILABLE,
        STALE_BINDING_STATE
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
                        device_binding_epoch BIGINT NOT NULL
                            CHECK (device_binding_epoch >= 0),
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
                    ALTER TABLE licensing_device_rebind_redemption
                    ADD COLUMN IF NOT EXISTS device_binding_epoch BIGINT
                    """.trimIndent()
                )
                statement.execute(
                    """
                    DO ${'$'}${'$'}
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM pg_constraint
                            WHERE conname = 'licensing_device_rebind_redemption_epoch_v2_ck'
                        ) THEN
                            ALTER TABLE licensing_device_rebind_redemption
                            ADD CONSTRAINT licensing_device_rebind_redemption_epoch_v2_ck
                            CHECK (
                                (
                                    code_id LIKE 'device-rebind-v2:%'
                                    AND device_binding_epoch IS NOT NULL
                                    AND device_binding_epoch >= 0
                                )
                                OR
                                (
                                    code_id NOT LIKE 'device-rebind-v2:%'
                                    AND device_binding_epoch IS NULL
                                )
                            );
                        END IF;
                    END
                    ${'$'}${'$'}
                    """.trimIndent()
                )
            }
        }
    }
}
