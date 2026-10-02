package pro.liliya.licensing.admincli

import java.time.Instant
import kotlin.system.exitProcess
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.postgres.LicenseAdministrationResult
import pro.liliya.licensing.postgres.PostgreSqlLicenseAdministrationStore

fun main(args: Array<String>) {
    val command = AdminCommand.parse(args) ?: failUsage()

    val jdbcUrl = requiredEnv("LILIYA_LICENSE_ADMIN_POSTGRES_URL")
    val username = requiredEnv("LILIYA_LICENSE_ADMIN_POSTGRES_USERNAME")
    val credential = requiredEnv("LILIYA_LICENSE_ADMIN_POSTGRES_CREDENTIAL")

    val dataSource = PGSimpleDataSource().apply {
        setURL(jdbcUrl)
        user = username
        password = credential
    }

    val store = PostgreSqlLicenseAdministrationStore(dataSource)
    val result = when (command) {
        is AdminCommand.RevokeDevice ->
            store.revokeDevice(
                subject = command.subject,
                productId = command.productId,
                now = Instant.now()
            )
        is AdminCommand.RevokeLicense ->
            store.revokeLicense(command.subject, command.productId, Instant.now())
    }

    when (result) {
        is LicenseAdministrationResult.Updated -> {
            println("LICENSE_ADMIN_RESULT=UPDATED")
            println("LICENSE_ADMIN_OPERATION=${command.operationName}")
            result.deviceBindingEpoch?.let {
                println("DEVICE_BINDING_EPOCH=$it")
            }
        }
        LicenseAdministrationResult.NotFound -> {
            println("LICENSE_ADMIN_RESULT=NOT_FOUND")
            exitProcess(4)
        }
        LicenseAdministrationResult.Failed -> {
            println("LICENSE_ADMIN_RESULT=FAILED")
            exitProcess(5)
        }
    }
}

internal sealed interface AdminCommand {
    val operationName: String

    data class RevokeDevice(
        val subject: String,
        val productId: String
    ) : AdminCommand {
        override val operationName: String = "REVOKE_DEVICE"
    }

    data class RevokeLicense(
        val subject: String,
        val productId: String
    ) : AdminCommand {
        override val operationName: String = "REVOKE_LICENSE"
    }

    companion object {
        fun parse(args: Array<String>): AdminCommand? {
            if (args.isEmpty()) return null
            return when (args[0]) {
                "revoke-device" -> {
                    val values = parseNamed(args.drop(1))
                    if (values.keys != setOf("--subject", "--product")) return null
                    val subject = values["--subject"]?.takeIf(String::isNotBlank)
                        ?: return null
                    val product = values["--product"]?.takeIf(String::isNotBlank)
                        ?: return null
                    RevokeDevice(subject, product)
                }
                "revoke-license" -> {
                    val values = parseNamed(args.drop(1))
                    if (values.keys != setOf("--subject", "--product")) return null
                    val subject = values["--subject"]?.takeIf(String::isNotBlank) ?: return null
                    val product = values["--product"]?.takeIf(String::isNotBlank) ?: return null
                    RevokeLicense(subject, product)
                }
                else -> null
            }
        }

        private fun parseNamed(args: List<String>): Map<String, String> {
            if (args.size % 2 != 0) return emptyMap()
            val result = linkedMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val key = args[index]
                val value = args[index + 1]
                if (!key.startsWith("--") || key in result) return emptyMap()
                result[key] = value
                index += 2
            }
            return result
        }
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("missing required license administration environment")

private fun failUsage(): Nothing =
    error(
        "usage: revoke-device --subject <subject> --product <product-id> | " +
            "revoke-license --subject <subject> --product <product-id>"
    )
