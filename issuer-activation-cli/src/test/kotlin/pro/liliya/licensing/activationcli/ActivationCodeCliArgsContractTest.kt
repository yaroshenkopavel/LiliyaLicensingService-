package pro.liliya.licensing.activationcli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ActivationCodeCliArgsContractTest {
    @Test
    fun omissionDefaultsToUnlimitedOfflinePolicy() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--product", "liliya-core",
                "--features", "assistant",
                "--activation-expires-at", "never",
                "--license-seconds", "never"
            )
        )

        assertNotNull(parsed)
        assertNull(parsed.offlineLeaseSeconds)
    }

    @Test
    fun explicitNoneKeepsUnlimitedOfflinePolicy() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--product", "liliya-core",
                "--features", "assistant",
                "--activation-expires-at", "never",
                "--license-seconds", "never",
                "--offline-seconds", "none"
            )
        )

        assertNotNull(parsed)
        assertNull(parsed.offlineLeaseSeconds)
    }

    @Test
    fun finiteOfflineLeaseWithoutExplicitOverrideIsRejected() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--product", "liliya-core",
                "--features", "assistant",
                "--activation-expires-at", "never",
                "--license-seconds", "never",
                "--offline-seconds", "86400"
            )
        )

        assertNull(parsed)
    }

    @Test
    fun finiteOfflineLeaseRequiresExplicitOverride() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--product", "liliya-core",
                "--features", "assistant",
                "--activation-expires-at", "never",
                "--license-seconds", "never",
                "--offline-seconds", "86400",
                "--allow-finite-offline-lease"
            )
        )

        assertNotNull(parsed)
        assertEquals(86400L, parsed.offlineLeaseSeconds)
    }

    @Test
    fun overrideWithoutFiniteLeaseIsRejected() {
        val parsed = CliArgs.parse(
            arrayOf(
                "--product", "liliya-core",
                "--features", "assistant",
                "--activation-expires-at", "never",
                "--license-seconds", "never",
                "--allow-finite-offline-lease"
            )
        )

        assertNull(parsed)
    }
}
