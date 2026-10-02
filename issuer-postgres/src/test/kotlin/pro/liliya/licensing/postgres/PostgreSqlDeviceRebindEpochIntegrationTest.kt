package pro.liliya.licensing.postgres

import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.activation.DeviceRebindCodeClaims
import pro.liliya.licensing.activation.DeviceRebindStoreResult

class PostgreSqlDeviceRebindEpochIntegrationTest {
    private val subject = "subject-rebind-epoch-it"
    private val product = "liliya-pro"
    private lateinit var dataSource: DataSource

    @BeforeTest
    fun setUp() {
        dataSource = dataSource()
        resetTables()
    }
    @AfterTest
    fun tearDown() {
        resetTables()
    }

    @Test
    fun legacy_ldr1_redemptions_keep_unknown_epoch_and_cannot_become_ldr2() {
        createLegacyRebindTableWithRow()

        PostgreSqlDeviceRebindSchema.initialize(dataSource)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT device_binding_epoch
                FROM licensing_device_rebind_redemption
                WHERE code_id = 'device-rebind-v1:legacy'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    require(result.next())
                    assertNull(result.getObject("device_binding_epoch"))
                }
            }
        }
        assertFailsWith<SQLException> {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO licensing_device_rebind_redemption (
                            code_id, attempt_id, subject, product_id,
                            installation_id, device_key_fingerprint, redeemed_at
                        ) VALUES (
                            'device-rebind-v2:missing-epoch', 'attempt-x',
                            'subject-x', 'liliya-pro',
                            'installation-x', 'sha256:device-x', NOW()
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    @Test
    fun exact_replay_fails_after_revoke_device_advances_binding_epoch() {
        initializeCurrentSchemas()
        seedEntitlement(deviceBindingEpoch = 1L)
        val claims = DeviceRebindCodeClaims(
            version = 2,
            codeId = "device-rebind-v2:integration",
            subject = subject,
            productId = product,
            deviceBindingEpoch = 1L,
            expiresAt = Instant.parse("2026-10-03T00:00:00Z")
        )
        val store = PostgreSqlDeviceRebindStore(dataSource)
        val first = store.rebind(
            claims = claims,
            attemptId = "attempt-1",
            installationId = "installation-B",
            deviceKeyFingerprint = "sha256:device-B",
            now = Instant.parse("2026-10-02T18:00:00Z")
        )
        assertIs<DeviceRebindStoreResult.Created>(first)

        val revoked = PostgreSqlLicenseAdministrationStore(dataSource).revokeDevice(
            subject = subject,
            productId = product,
            now = Instant.parse("2026-10-02T18:10:00Z")
        )
        val updated = assertIs<LicenseAdministrationResult.Updated>(revoked)
        assertEquals(2L, updated.deviceBindingEpoch)
        val replay = store.rebind(
            claims = claims,
            attemptId = "attempt-1",
            installationId = "installation-B",
            deviceKeyFingerprint = "sha256:device-B",
            now = Instant.parse("2026-10-02T18:11:00Z")
        )

        assertIs<DeviceRebindStoreResult.StaleBindingEpoch>(replay)
    }

    private fun initializeCurrentSchemas() {
        PostgreSqlEntitlementSchema.initialize(dataSource)
        PostgreSqlActivationRedemptionSchema.initialize(dataSource)
        PostgreSqlDeviceRebindSchema.initialize(dataSource)
    }

    private fun seedEntitlement(deviceBindingEpoch: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO licensing_entitlement (
                    license_id, subject, product_id, features, version,
                    signing_key_id, issued_at, not_before, expires_at,
                    offline_lease_until, revocation_epoch, revoked_at,
                    device_binding_required, device_binding_epoch
                ) VALUES (
                    ?, ?, ?, ARRAY['core'], 1,
                    'test-signing-key', ?, ?, NULL,
                    NULL, 0, NULL, TRUE, ?
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "license-rebind-epoch-it")
                statement.setString(2, subject)
                statement.setString(3, product)
                val now = java.sql.Timestamp.from(
                    Instant.parse("2026-10-02T17:00:00Z")
                )
                statement.setTimestamp(4, now)
                statement.setTimestamp(5, now)
                statement.setLong(6, deviceBindingEpoch)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun createLegacyRebindTableWithRow() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE licensing_device_rebind_redemption (
                        code_id TEXT PRIMARY KEY,
                        attempt_id TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        installation_id TEXT NOT NULL,
                        device_key_fingerprint TEXT NOT NULL,
                        redeemed_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO licensing_device_rebind_redemption (
                        code_id, attempt_id, subject, product_id,
                        installation_id, device_key_fingerprint, redeemed_at
                    ) VALUES (
                        'device-rebind-v1:legacy', 'attempt-legacy',
                        'subject-legacy', 'liliya-pro',
                        'installation-legacy', 'sha256:legacy', NOW()
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun resetTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS licensing_device_rebind_redemption")
                statement.execute("DROP TABLE IF EXISTS licensing_device_binding")
                statement.execute("DROP TABLE IF EXISTS licensing_activation_redemption")
                statement.execute("DROP TABLE IF EXISTS licensing_entitlement")
            }
        }
    }

    private fun dataSource(): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(
                System.getenv("TEST_POSTGRES_URL")
                    ?: "jdbc:postgresql://localhost:5432/liliya_licensing_test"
            )
            user = System.getenv("TEST_POSTGRES_USER") ?: "liliya"
            password = System.getenv("TEST_POSTGRES_PASSWORD") ?: "liliya-test"
        }
}
