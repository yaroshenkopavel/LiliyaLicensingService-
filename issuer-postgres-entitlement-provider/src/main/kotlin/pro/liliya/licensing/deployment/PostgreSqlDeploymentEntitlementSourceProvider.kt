package pro.liliya.licensing.deployment

import java.sql.ResultSet
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.issuer.EntitlementSourcePort
import pro.liliya.licensing.issuer.EntitlementSourceRecord
import pro.liliya.licensing.issuer.EntitlementSourceResult
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest

/**
 * Production entitlement source backed by PostgreSQL.
 *
 * Request fields are lookup keys only. Entitlement authority is read exclusively
 * from licensing_entitlement. Missing rows fail closed and never mint entitlement.
 */
class PostgreSqlDeploymentEntitlementSourceProvider :
    DeploymentEntitlementSourceProvider {

    override fun create(): EntitlementSourcePort {
        val jdbcUrl = requiredEnvironment("LILIYA_POSTGRES_JDBC_URL")
        val username = requiredEnvironment("LILIYA_POSTGRES_USERNAME")
        val password = requiredEnvironment("LILIYA_POSTGRES_PASSWORD")

        val dataSource = PGSimpleDataSource().apply {
            setURL(jdbcUrl)
            user = username
            this.password = password
        }

        verifySchema(dataSource)

        return PostgreSqlEntitlementSourcePort(dataSource)
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing required entitlement source configuration")

    private fun verifySchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    license_id,
                    subject,
                    product_id,
                    features,
                    version,
                    signing_key_id,
                    issued_at,
                    not_before,
                    expires_at,
                    offline_lease_until,
                    revocation_epoch
                FROM licensing_entitlement
                WHERE FALSE
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { }
            }
        }
    }
}

internal class PostgreSqlEntitlementSourcePort(
    private val dataSource: DataSource
) : EntitlementSourcePort {

    override fun resolve(request: LicenseServiceRequest): EntitlementSourceResult =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                        license_id,
                        subject,
                        product_id,
                        features,
                        version,
                        signing_key_id,
                        issued_at,
                        not_before,
                        expires_at,
                        offline_lease_until,
                        revocation_epoch
                    FROM licensing_entitlement
                    WHERE subject = ?
                      AND product_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, request.subjectReference)
                    statement.setString(2, request.productId)

                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            return EntitlementSourceResult.Ineligible(
                                LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE
                            )
                        }

                        val record = result.toEntitlementRecord()
                            ?: return EntitlementSourceResult.Failed(
                                LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE
                            )

                        if (result.next()) {
                            return EntitlementSourceResult.Failed(
                                LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE
                            )
                        }

                        EntitlementSourceResult.Eligible(record)
                    }
                }
            }
        } catch (_: Exception) {
            EntitlementSourceResult.Failed(
                LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE
            )
        }

    private fun ResultSet.toEntitlementRecord(): EntitlementSourceRecord? {
        val sqlFeatures = getArray("features") ?: return null

        val features = try {
            val raw = sqlFeatures.array as? Array<*> ?: return null
            raw.mapNotNull { it as? String }.toSet()
        } finally {
            runCatching { sqlFeatures.free() }
        }

        val licenseId = getString("license_id") ?: return null
        val subject = getString("subject") ?: return null
        val productId = getString("product_id") ?: return null
        val version = getLong("version")
        val signingKeyId = getString("signing_key_id") ?: return null
        val issuedAt = getTimestamp("issued_at")?.toInstant() ?: return null
        val notBefore = getTimestamp("not_before")?.toInstant() ?: return null
        val expiresAt = getTimestamp("expires_at")?.toInstant()
        val offlineLeaseUntil = getTimestamp("offline_lease_until")?.toInstant()
        val revocationEpoch = getLong("revocation_epoch")

        if (
            licenseId.isBlank() ||
            subject.isBlank() ||
            productId.isBlank() ||
            features.isEmpty() ||
            features.any { it.isBlank() } ||
            version <= 0L ||
            signingKeyId.isBlank() ||
            revocationEpoch < 0L ||
            (expiresAt != null && !expiresAt.isAfter(notBefore)) ||
            (offlineLeaseUntil != null && offlineLeaseUntil.isBefore(notBefore)) ||
            (
                expiresAt != null &&
                    offlineLeaseUntil != null &&
                    offlineLeaseUntil.isAfter(expiresAt)
                )
        ) {
            return null
        }

        return EntitlementSourceRecord(
            licenseId = licenseId,
            subject = subject,
            productId = productId,
            features = features,
            version = version,
            signingKeyId = signingKeyId,
            issuedAt = issuedAt,
            notBefore = notBefore,
            expiresAt = expiresAt,
            offlineLeaseUntil = offlineLeaseUntil,
            revocationEpoch = revocationEpoch
        )
    }
}
