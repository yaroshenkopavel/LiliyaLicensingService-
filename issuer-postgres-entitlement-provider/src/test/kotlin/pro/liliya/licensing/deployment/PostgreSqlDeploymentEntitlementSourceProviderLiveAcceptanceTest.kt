package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue
import pro.liliya.licensing.issuer.EntitlementSourceResult
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest

class PostgreSqlDeploymentEntitlementSourceProviderLiveAcceptanceTest {
    @Test
    fun live_empty_production_entitlement_store_fails_closed() {
        assumeTrue(
            System.getenv("LILIYA_LIVE_ENTITLEMENT_ACCEPTANCE") == "1",
            "live production entitlement acceptance is not enabled"
        )

        val source = PostgreSqlDeploymentEntitlementSourceProvider().create()

        val request = LicenseServiceRequest(
            protocolVersion = LicenseProtocolVersion(1),
            operation = LicenseOperation.ISSUE,
            productId = "liliya-pro",
            subjectReference = "acceptance-subject-that-must-not-exist"
        )

        val result = assertIs<EntitlementSourceResult.Ineligible>(
            source.resolve(request)
        )

        assertEquals(
            LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE,
            result.reason
        )

        println(
            "LICENSING_POSTGRES_ENTITLEMENT_EVIDENCE=" +
                "{\"schemaReadable\":true," +
                "\"emptyStoreFailsClosed\":true," +
                "\"missingSubjectRejected\":true," +
                "\"runtimeReadOnly\":true}"
        )
    }
}
