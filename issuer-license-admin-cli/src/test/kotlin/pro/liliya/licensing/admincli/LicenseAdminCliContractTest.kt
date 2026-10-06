package pro.liliya.licensing.admincli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LicenseAdminCliContractTest {
    @Test
    fun revoke_device_requires_exact_subject_and_product() {
        val parsed = AdminCommand.parse(
            arrayOf(
                "revoke-device",
                "--subject", "subject-1",
                "--product", "liliya-pro"
            )
        )

        val command = assertIs<AdminCommand.RevokeDevice>(parsed)
        assertEquals("subject-1", command.subject)
        assertEquals("liliya-pro", command.productId)
        assertEquals("REVOKE_DEVICE", command.operationName)
    }

    @Test
    fun revoke_license_requires_subject_and_product() {
        val parsed = AdminCommand.parse(
            arrayOf(
                "revoke-license",
                "--subject", "subject-1",
                "--product", "liliya-pro"
            )
        )

        val command = assertIs<AdminCommand.RevokeLicense>(parsed)
        assertEquals("subject-1", command.subject)
        assertEquals("liliya-pro", command.productId)
        assertEquals("REVOKE_LICENSE", command.operationName)
    }

    @Test
    fun missing_product_unknown_or_duplicate_arguments_fail_closed() {
        assertNull(AdminCommand.parse(arrayOf("revoke-device", "--subject", "s")))
        assertNull(AdminCommand.parse(arrayOf("revoke-license", "--subject", "s")))
        assertNull(
            AdminCommand.parse(
                arrayOf(
                    "revoke-device",
                    "--subject", "s",
                    "--product", "p",
                    "--subject", "other"
                )
            )
        )
        assertNull(AdminCommand.parse(arrayOf("unknown", "--subject", "s")))
    }
}
