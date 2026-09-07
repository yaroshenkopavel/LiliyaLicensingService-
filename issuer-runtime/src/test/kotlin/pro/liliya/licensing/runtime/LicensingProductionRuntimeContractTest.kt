package pro.liliya.licensing.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicensingProductionRuntimeContractTest {
    @Test
    fun start_requires_all_dependencies_before_listener_and_becomes_ready() {
        val events = mutableListOf<String>()
        val runtime = runtime(events)

        assertEquals(LicensingRuntimeStartResult.Ready, runtime.start())
        assertEquals(LicensingRuntimeState.READY, runtime.state())
        assertTrue(runtime.isReady())
        assertEquals(
            listOf(
                "prepare:POSTGRESQL",
                "prepare:OPENBAO_TRANSIT",
                "prepare:REQUEST_AUTHENTICATION",
                "listener:start"
            ),
            events
        )
    }

    @Test
    fun dependency_failure_is_fail_closed_single_attempt_and_listener_is_not_started() {
        val events = mutableListOf<String>()
        val runtime = runtime(
            events = events,
            failingKind = LicensingRuntimeDependencyKind.OPENBAO_TRANSIT
        )

        val result = runtime.start()

        assertIs<LicensingRuntimeStartResult.Failed>(result)
        assertEquals(LicensingRuntimeFailure.OPENBAO_UNAVAILABLE, result.reason)
        assertEquals(LicensingRuntimeState.FAILED, runtime.state())
        assertFalse(runtime.isReady())
        assertEquals(
            listOf(
                "prepare:POSTGRESQL",
                "prepare:OPENBAO_TRANSIT",
                "close:POSTGRESQL"
            ),
            events
        )
    }

    @Test
    fun listener_failure_closes_prepared_dependencies_in_reverse_order() {
        val events = mutableListOf<String>()
        val runtime = runtime(events = events, listenerFails = true)

        val result = runtime.start()

        assertIs<LicensingRuntimeStartResult.Failed>(result)
        assertEquals(LicensingRuntimeFailure.LISTENER_UNAVAILABLE, result.reason)
        assertEquals(
            listOf(
                "prepare:POSTGRESQL",
                "prepare:OPENBAO_TRANSIT",
                "prepare:REQUEST_AUTHENTICATION",
                "listener:start",
                "close:REQUEST_AUTHENTICATION",
                "close:OPENBAO_TRANSIT",
                "close:POSTGRESQL"
            ),
            events
        )
    }

    @Test
    fun stop_closes_listener_then_dependencies_in_reverse_order() {
        val events = mutableListOf<String>()
        val runtime = runtime(events)

        assertEquals(LicensingRuntimeStartResult.Ready, runtime.start())
        assertEquals(LicensingRuntimeStopResult.Stopped, runtime.stop())
        assertEquals(LicensingRuntimeState.STOPPED, runtime.state())
        assertFalse(runtime.isReady())

        assertEquals(
            listOf(
                "prepare:POSTGRESQL",
                "prepare:OPENBAO_TRANSIT",
                "prepare:REQUEST_AUTHENTICATION",
                "listener:start",
                "listener:close",
                "close:REQUEST_AUTHENTICATION",
                "close:OPENBAO_TRANSIT",
                "close:POSTGRESQL"
            ),
            events
        )
    }

    @Test
    fun duplicate_start_is_rejected_without_second_dependency_attempt() {
        val events = mutableListOf<String>()
        val runtime = runtime(events)

        assertEquals(LicensingRuntimeStartResult.Ready, runtime.start())
        val second = runtime.start()

        assertIs<LicensingRuntimeStartResult.Failed>(second)
        assertEquals(LicensingRuntimeFailure.INVALID_STATE, second.reason)
        assertEquals(3, events.count { it.startsWith("prepare:") })
        assertEquals(1, events.count { it == "listener:start" })
    }

    @Test
    fun rendering_is_structural_and_does_not_render_listener() {
        val events = mutableListOf<String>()
        val runtime = runtime(events)

        val rendered = runtime.toString()

        assertTrue(rendered.contains("POSTGRESQL"))
        assertTrue(rendered.contains("OPENBAO_TRANSIT"))
        assertTrue(rendered.contains("REQUEST_AUTHENTICATION"))
        assertTrue(rendered.contains("listener=<redacted>"))
        assertFalse(rendered.contains("secret-listener"))
    }

    private fun runtime(
        events: MutableList<String>,
        failingKind: LicensingRuntimeDependencyKind? = null,
        listenerFails: Boolean = false
    ): LicensingProductionRuntime {
        val dependencies = LicensingRuntimeDependencyKind.entries.map { kind ->
            object : LicensingRuntimeDependency {
                override val kind: LicensingRuntimeDependencyKind = kind

                override fun prepare(): LicensingRuntimeDependencyResult {
                    events += "prepare:$kind"
                    return if (kind == failingKind) {
                        LicensingRuntimeDependencyResult.Failed(
                            LicensingRuntimeFailure.INVALID_STATE
                        )
                    } else {
                        LicensingRuntimeDependencyResult.Ready
                    }
                }

                override fun close() {
                    events += "close:$kind"
                }
            }
        }

        val listener = object : LicensingRuntimeListener {
            override fun start(): LicensingRuntimeListenerResult {
                events += "listener:start"
                return if (listenerFails) {
                    LicensingRuntimeListenerResult.Failed
                } else {
                    LicensingRuntimeListenerResult.Started
                }
            }

            override fun close() {
                events += "listener:close"
            }

            override fun toString(): String = "secret-listener"
        }

        return LicensingProductionRuntime(dependencies, listener)
    }
}
