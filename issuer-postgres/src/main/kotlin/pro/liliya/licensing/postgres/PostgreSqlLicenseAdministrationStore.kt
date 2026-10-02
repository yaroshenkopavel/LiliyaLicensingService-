package pro.liliya.licensing.postgres

import java.time.Instant
import javax.sql.DataSource

sealed interface LicenseAdministrationResult {
    data object Updated : LicenseAdministrationResult
    data object NotFound : LicenseAdministrationResult
    data object Failed : LicenseAdministrationResult
}

class PostgreSqlLicenseAdministrationStore(
    private val dataSource: DataSource
) {
    fun revokeDevice(
        subject: String,
        now: Instant
    ): LicenseAdministrationResult {
        require(subject.isNotBlank())
        return try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
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
                    if (statement.executeUpdate() == 1) {
                        LicenseAdministrationResult.Updated
                    } else {
                        LicenseAdministrationResult.NotFound
                    }
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
                    val updated = connection.prepareStatement(
                        """
                        UPDATE licensing_entitlement
                        SET revoked_at = ?,
                            revocation_epoch = revocation_epoch + 1
                        WHERE subject = ?
                          AND product_id = ?
                          AND revoked_at IS NULL
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, java.sql.Timestamp.from(now))
                        statement.setString(2, subject)
                        statement.setString(3, productId)
                        statement.executeUpdate()
                    }

                    if (updated != 1) {
                        connection.rollback()
                        return@use LicenseAdministrationResult.NotFound
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
                    LicenseAdministrationResult.Updated
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
