package pro.liliya.licensing.deployment

import javax.sql.DataSource

/**
 * Administrative schema owner for the production entitlement source.
 *
 * Runtime code only reads this table. Schema creation must be executed with a separate
 * administrative database identity.
 */
object PostgreSqlEntitlementSchemaAdmin {
    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_entitlement (
                        license_id TEXT NOT NULL
                            CHECK (length(license_id) > 0),
                        subject TEXT NOT NULL
                            CHECK (length(subject) > 0),
                        product_id TEXT NOT NULL
                            CHECK (length(product_id) > 0),
                        features TEXT[] NOT NULL,
                        version BIGINT NOT NULL
                            CHECK (version > 0),
                        signing_key_id TEXT NOT NULL
                            CHECK (length(signing_key_id) > 0),
                        issued_at TIMESTAMPTZ NOT NULL,
                        not_before TIMESTAMPTZ NOT NULL,
                        expires_at TIMESTAMPTZ NULL,
                        offline_lease_until TIMESTAMPTZ NULL,
                        revocation_epoch BIGINT NOT NULL
                            CHECK (revocation_epoch >= 0),
                        PRIMARY KEY (subject, product_id),
                        UNIQUE (license_id),
                        CHECK (cardinality(features) > 0),
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
        }
    }
}
