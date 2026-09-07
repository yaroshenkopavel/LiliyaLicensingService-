package pro.liliya.licensing.observability

import java.io.PrintStream

enum class LicensingOperationalEnvironment {
    UNKNOWN,
    DEVELOPMENT,
    STAGING,
    PRODUCTION
}

enum class LicensingOperationalComponent {
    DEPLOYMENT,
    RUNTIME,
    HTTPS,
    POSTGRESQL,
    OPENBAO_TRANSIT,
    REQUEST_AUTHENTICATION
}

enum class LicensingOperationalEventCode {
    BOOTSTRAP_READY,
    BOOTSTRAP_REJECTED,
    RUNTIME_STARTING,
    RUNTIME_READY,
    RUNTIME_NOT_READY,
    RUNTIME_STOPPING,
    RUNTIME_STOPPED,
    DEPENDENCY_UNAVAILABLE
}

enum class LicensingOperationalDetailCode {
    NONE,
    ENVIRONMENT,
    LISTENER_HOST,
    LISTENER_PORT,
    POSTGRES_JDBC_URL,
    POSTGRES_USERNAME,
    POSTGRES_PASSWORD,
    OPENBAO_ADDRESS,
    OPENBAO_KEY_REFERENCE,
    OPENBAO_TOKEN,
    REQUEST_AUTH_IDENTITY_REFERENCE,
    REQUEST_AUTH_SECRET,
    TLS_IDENTITY_REFERENCE
}

enum class LicensingOperationalReasonCode {
    NONE,
    MISSING_REQUIRED_CONFIGURATION,
    INVALID_CONFIGURATION,
    POSTGRESQL_UNAVAILABLE,
    OPENBAO_UNAVAILABLE,
    REQUEST_AUTHENTICATION_UNAVAILABLE,
    LISTENER_UNAVAILABLE,
    INTERNAL_FAILURE
}

/**
 * Fixed-schema operational event.
 *
 * Deliberately has no arbitrary message, exception, path, request, subject, payload,
 * signature, credential, token or free-form metadata field.
 */
data class LicensingOperationalEvent(
    val environment: LicensingOperationalEnvironment,
    val component: LicensingOperationalComponent,
    val code: LicensingOperationalEventCode,
    val reason: LicensingOperationalReasonCode = LicensingOperationalReasonCode.NONE,
    val detail: LicensingOperationalDetailCode = LicensingOperationalDetailCode.NONE
) {
    fun structuralLine(): String =
        "LICENSING_OPERATIONAL_EVENT={" +
            "\"environment\":\"" + environment + "\"," +
            "\"component\":\"" + component + "\"," +
            "\"code\":\"" + code + "\"," +
            "\"reason\":\"" + reason + "\"," +
            "\"detail\":\"" + detail + "\"" +
            "}"

    override fun toString(): String = structuralLine()
}

fun interface LicensingOperationalEventSink {
    fun publish(event: LicensingOperationalEvent)
}

class ConsoleLicensingOperationalEventSink(
    private val output: PrintStream = System.out
) : LicensingOperationalEventSink {
    override fun publish(event: LicensingOperationalEvent) {
        output.println(event.structuralLine())
    }

    override fun toString(): String = "ConsoleLicensingOperationalEventSink(output=<redacted>)"
}
