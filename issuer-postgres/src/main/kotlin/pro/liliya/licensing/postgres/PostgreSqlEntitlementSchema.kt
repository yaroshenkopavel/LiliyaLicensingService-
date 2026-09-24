package pro.liliya.licensing.postgres

import java.sql.SQLException
import javax.sql.DataSource

/**
 * Admin-only schema owner for the production entitlement authority table.
 *
 * This creates structure only. It never creates, imports, or promotes entitlement rows.
 * Runtime callers remain read-only through database grants owned by deployment.
 */
object PostgreSqlEntitlementSchema {
    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS licensing_entitlement (
                            license_id TEXT NOT NULL
                                CHECK (length(btrim(license_id)) > 0),
                            subject TEXT NOT NULL
                                CHECK (length(btrim(subject)) > 0),
                            product_id TEXT NOT NULL
                                CHECK (length(btrim(product_id)) > 0),
                            features TEXT[] NOT NULL
                                CHECK (cardinality(features) > 0),
                            version BIGINT NOT NULL
                                CHECK (version > 0),
                            signing_key_id TEXT NOT NULL
                                CHECK (length(btrim(signing_key_id)) > 0),
                            issued_at TIMESTAMPTZ NOT NULL,
                            not_before TIMESTAMPTZ NOT NULL,
                            expires_at TIMESTAMPTZ,
                            offline_lease_until TIMESTAMPTZ,
                            revocation_epoch BIGINT NOT NULL
                                CHECK (revocation_epoch >= 0),
                            PRIMARY KEY (subject, product_id),
                            UNIQUE (license_id),
                            CHECK (expires_at IS NULL OR expires_at > not_before),
                            CHECK (
                                offline_lease_until IS NULL OR
                                offline_lease_until >= not_before
                            ),
                            CHECK (
                                expires_at IS NULL OR
                                offline_lease_until IS NULL OR
                                offline_lease_until <= expires_at
                            )
                        )
                        """.trimIndent()
                    )
                }
                connection.commit()
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                throw IllegalStateException(
                    "failed to initialize licensing entitlement schema",
                    failure
                )
            } catch (failure: RuntimeException) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }
}
