package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import pro.liliya.licensing.openbao.OpenBaoTransitClient
import pro.liliya.licensing.openbao.OpenBaoTransitDescribeResult
import pro.liliya.licensing.openbao.OpenBaoTransitKeyDescription
import pro.liliya.licensing.openbao.OpenBaoTransitPublicKeyResult
import pro.liliya.licensing.openbao.OpenBaoTransitSignResult
import pro.liliya.licensing.runtime.LicensingRuntimeDependencyResult
import pro.liliya.licensing.runtime.LicensingRuntimeFailure

class OpenBaoRuntimeReadinessMultipleKeysContractTest {
    @Test
    fun both_exact_keys_are_required_before_ready() {
        val client = client(
            mapOf(
                "entitlement-key" to setOf(1),
                "service-state-key" to setOf(1)
            )
        )
        val dependency = OpenBaoRuntimeReadinessDependency(
            client = client,
            requiredKeys = listOf(
                OpenBaoRuntimeKeyRequirement("entitlement-key", 1),
                OpenBaoRuntimeKeyRequirement("service-state-key", 1)
            )
        )

        assertEquals(
            LicensingRuntimeDependencyResult.Ready,
            dependency.prepare()
        )
    }

    @Test
    fun missing_service_state_exact_version_fails_runtime_readiness() {
        val client = client(
            mapOf(
                "entitlement-key" to setOf(1),
                "service-state-key" to setOf(1)
            )
        )
        val dependency = OpenBaoRuntimeReadinessDependency(
            client = client,
            requiredKeys = listOf(
                OpenBaoRuntimeKeyRequirement("entitlement-key", 1),
                OpenBaoRuntimeKeyRequirement("service-state-key", 2)
            )
        )

        assertEquals(
            LicensingRuntimeDependencyResult.Failed(
                LicensingRuntimeFailure.OPENBAO_UNAVAILABLE
            ),
            dependency.prepare()
        )
    }

    private fun client(
        versions: Map<String, Set<Int>>
    ): OpenBaoTransitClient =
        object : OpenBaoTransitClient {
            override fun describeKey(keyName: String): OpenBaoTransitDescribeResult {
                val available = versions[keyName]
                    ?: return OpenBaoTransitDescribeResult.Unavailable
                return OpenBaoTransitDescribeResult.Available(
                    OpenBaoTransitKeyDescription(
                        type = "ecdsa-p256",
                        supportsSigning = true,
                        availableVersions = available
                    )
                )
            }

            override fun sign(
                keyName: String,
                keyVersion: Int,
                input: ByteArray
            ): OpenBaoTransitSignResult = OpenBaoTransitSignResult.Failed

            override fun readPublicKeyPem(
                keyName: String,
                keyVersion: Int
            ): OpenBaoTransitPublicKeyResult = OpenBaoTransitPublicKeyResult.Unavailable
        }
}
