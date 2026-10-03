package pro.liliya.licensing.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import pro.liliya.licensing.runtime.LicensingRuntimeDependency
import pro.liliya.licensing.runtime.LicensingRuntimeDependencyKind
import pro.liliya.licensing.runtime.LicensingRuntimeDependencyResult
import pro.liliya.licensing.runtime.LicensingRuntimeFailure

class CompositePostgreSqlRuntimeReadinessDependencyContractTest {
    @Test
    fun multiple_postgres_probes_are_one_runtime_dependency_kind() {
        val first = FakeDependency(LicensingRuntimeDependencyResult.Ready)
        val second = FakeDependency(LicensingRuntimeDependencyResult.Ready)
        val third = FakeDependency(LicensingRuntimeDependencyResult.Ready)

        val composite = CompositePostgreSqlRuntimeReadinessDependency(
            listOf(first, second, third)
        )

        assertEquals(LicensingRuntimeDependencyKind.POSTGRESQL, composite.kind)
        assertEquals(LicensingRuntimeDependencyResult.Ready, composite.prepare())
        assertEquals(1, first.prepareCalls)
        assertEquals(1, second.prepareCalls)
        assertEquals(1, third.prepareCalls)
    }
    @Test
    fun child_failure_is_fail_closed_as_postgres_unavailable() {
        val first = FakeDependency(LicensingRuntimeDependencyResult.Ready)
        val second = FakeDependency(
            LicensingRuntimeDependencyResult.Failed(
                LicensingRuntimeFailure.POSTGRESQL_UNAVAILABLE
            )
        )
        val third = FakeDependency(LicensingRuntimeDependencyResult.Ready)

        val result = CompositePostgreSqlRuntimeReadinessDependency(
            listOf(first, second, third)
        ).prepare()

        assertEquals(
            LicensingRuntimeDependencyResult.Failed(
                LicensingRuntimeFailure.POSTGRESQL_UNAVAILABLE
            ),
            result
        )
        assertEquals(1, first.prepareCalls)
        assertEquals(1, second.prepareCalls)
        assertEquals(0, third.prepareCalls)
    }

    @Test
    fun non_postgres_child_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            CompositePostgreSqlRuntimeReadinessDependency(
                listOf(
                    FakeDependency(
                        LicensingRuntimeDependencyResult.Ready,
                        LicensingRuntimeDependencyKind.OPENBAO_TRANSIT
                    )
                )
            )
        }
    }

    private class FakeDependency(
        private val result: LicensingRuntimeDependencyResult,
        override val kind: LicensingRuntimeDependencyKind =
            LicensingRuntimeDependencyKind.POSTGRESQL
    ) : LicensingRuntimeDependency {
        var prepareCalls: Int = 0

        override fun prepare(): LicensingRuntimeDependencyResult {
            prepareCalls += 1
            return result
        }

        override fun close() = Unit
    }
}
