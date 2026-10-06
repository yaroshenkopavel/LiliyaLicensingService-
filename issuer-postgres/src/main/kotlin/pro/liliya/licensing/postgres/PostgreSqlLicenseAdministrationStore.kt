package pro.liliya.licensing.postgres

import java.time.Instant
import javax.sql.DataSource

sealed interface LicenseAdministrationResult {
    data class Updated(
        val deviceBindingEpoch: Long? = null,
        val revocationEpoch: Long? = null
    ) : LicenseAdministrationResult
    data object NotFound : LicenseAdministrationResult
    data object Failed : LicenseAdministrationResult
}

class PostgreSqlLicenseAdministrationStore(
    private val dataSource: DataSource
) {
    fun revokeDevice(
        subject: String,
        productId: String,
        now: Instant
    ): LicenseAdministrationResult {
        require(subject.isNotBlank())
        require(productId.isNotBlank())

        return try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    PostgreSqlEntitlementAdvisoryLock.acquire(
                        connection,
                        subject,
                        productId
                    )
                    val currentEpoch = connection.prepareStatement(
                        """
                        SELECT device_binding_epoch
                        FROM licensing_entitlement
                        WHERE subject = ?
                          AND product_id = ?
                          AND revoked_at IS NULL
                          AND device_binding_required = TRUE
                        FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, subject)
                        statement.setString(2, productId)
                        statement.executeQuery().use { result ->
                            if (result.next()) {
                                result.getLong("device_binding_epoch")
                            } else {
                                null
                            }
                        }
                    }

                    if (currentEpoch == null) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.NotFound
                    }

                    val bindingUpdated = connection.prepareStatement(
                        """
                        UPDATE licensing_device_binding
                        SET status = 'REVOKED',
                            revoked_at = ?
                        WHERE subject = ?
                          AND status = 'ACTIVE'
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(now))
                        statement.setString(2, subject)
                        statement.executeUpdate()
                    }

                    if (bindingUpdated != 1) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.NotFound
                    }

                    val nextEpoch = Math.addExact(currentEpoch, 1L)
                    val entitlementUpdated = connection.prepareStatement(
                        """
                        UPDATE licensing_entitlement
                        SET device_binding_epoch = ?
                        WHERE subject = ?
                          AND product_id = ?
                          AND device_binding_epoch = ?
                          AND revoked_at IS NULL
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, nextEpoch)
                        statement.setString(2, subject)
                        statement.setString(3, productId)
                        statement.setLong(4, currentEpoch)
                        statement.executeUpdate()
                    }

                    if (entitlementUpdated != 1) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.Failed
                    }

                    connection.commit()
                    LicenseAdministrationResult.Updated(nextEpoch)
                } catch (_: Throwable) {
                    runCatching { connection.rollback() }
                    LicenseAdministrationResult.Failed
                }
            }
        } catch (_: Throwable) {
            LicenseAdministrationResult.Failed
        }
    }

    fun revokeLicense(
        subject: String,
        productId: String,
        now: Instant
    ): LicenseAdministrationResult {
        require(subject.isNotBlank())
        require(productId.isNotBlank())

        return try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    PostgreSqlEntitlementAdvisoryLock.acquire(
                        connection,
                        subject,
                        productId
                    )
                    val currentRevocationEpoch = connection.prepareStatement(
                        """
                        SELECT revocation_epoch
                        FROM licensing_entitlement
                        WHERE subject = ?
                          AND product_id = ?
                          AND revoked_at IS NULL
                        FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, subject)
                        statement.setString(2, productId)
                        statement.executeQuery().use { result ->
                            if (result.next()) {
                                result.getLong("revocation_epoch")
                            } else {
                                null
                            }
                        }
                    }

                    if (currentRevocationEpoch == null) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.NotFound
                    }

                    val decisionRevocationEpoch = connection.prepareStatement(
                        """
                        SELECT revocation_epoch
                        FROM licensing_decision_state
                        WHERE subject = ?
                          AND product_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, subject)
                        statement.setString(2, productId)
                        statement.executeQuery().use { result ->
                            if (result.next()) {
                                result.getLong("revocation_epoch")
                            } else {
                                null
                            }
                        }
                    }

                    if (
                        decisionRevocationEpoch != null &&
                        decisionRevocationEpoch != currentRevocationEpoch
                    ) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.Failed
                    }

                    val nextRevocationEpoch = Math.addExact(
                        currentRevocationEpoch,
                        1L
                    )
                    val updated = connection.prepareStatement(
                        """
                        UPDATE licensing_entitlement
                        SET revoked_at = ?,
                            revocation_epoch = ?,
                            device_binding_epoch = device_binding_epoch + 1
                        WHERE subject = ?
                          AND product_id = ?
                          AND revoked_at IS NULL
                          AND revocation_epoch = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(now))
                        statement.setLong(2, nextRevocationEpoch)
                        statement.setString(3, subject)
                        statement.setString(4, productId)
                        statement.setLong(5, currentRevocationEpoch)
                        statement.executeUpdate()
                    }

                    if (updated != 1) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.Failed
                    }

                    if (decisionRevocationEpoch != null) {
                        val decisionUpdated = connection.prepareStatement(
                            """
                            UPDATE licensing_decision_state
                            SET revocation_epoch = ?
                            WHERE subject = ?
                              AND product_id = ?
                              AND revocation_epoch = ?
                            """.trimIndent()
                        ).use { statement ->
                            statement.setLong(1, nextRevocationEpoch)
                            statement.setString(2, subject)
                            statement.setString(3, productId)
                            statement.setLong(4, currentRevocationEpoch)
                            statement.executeUpdate()
                        }
                        if (decisionUpdated != 1) {
                            connection.rollback()
                            return@use LicenseAdministrationResult.Failed
                        }
                    }

                    connection.prepareStatement(
                        """
                        UPDATE licensing_device_binding
                        SET status = 'REVOKED',
                            revoked_at = COALESCE(revoked_at, ?)
                        WHERE subject = ?
                          AND status = 'ACTIVE'
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(now))
                        statement.setString(2, subject)
                        statement.executeUpdate()
                    }

                    connection.commit()
                    LicenseAdministrationResult.Updated(
                        revocationEpoch = nextRevocationEpoch
                    )
                } catch (_: Throwable) {
                    runCatching { connection.rollback() }
                    LicenseAdministrationResult.Failed
                }
            }
        } catch (_: Throwable) {
            LicenseAdministrationResult.Failed
        }
    }
}
