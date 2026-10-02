package pro.liliya.licensing.rebindcli

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceRebindCodeCliContractTest {
    @Test
    fun finite_iso_expiry_and_binding_epoch_are_required_and_parsed() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--subject", "subject-1",
                "--product", "liliya-pro",
                "--device-binding-epoch", "7",
                "--expires-at", "2026-10-03T00:00:00Z"
            )
        )

        requireNotNull(parsed)
        assertEquals("subject-1", parsed.subject)
        assertEquals("liliya-pro", parsed.productId)
        assertEquals(7L, parsed.deviceBindingEpoch)
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), parsed.expiresAt)
    }

    @Test
    fun never_expiry_is_rejected() {
        assertNull(
            CliArgs.parse(
                arrayOf(
                    "--subject", "subject-1",
                    "--product", "liliya-pro",
                    "--device-binding-epoch", "1",
                    "--expires-at", "never"
                )
            )
        )
    }

    @Test
    fun missing_or_negative_binding_epoch_is_rejected() {
        assertNull(
            CliArgs.parse(
                arrayOf(
                    "--subject", "subject-1",
                    "--product", "liliya-pro",
                    "--expires-at", "2026-10-03T00:00:00Z"
                )
            )
        )
        assertNull(
            CliArgs.parse(
                arrayOf(
                    "--subject", "subject-1",
                    "--product", "liliya-pro",
                    "--device-binding-epoch", "-1",
                    "--expires-at", "2026-10-03T00:00:00Z"
                )
            )
        )
    }
}
