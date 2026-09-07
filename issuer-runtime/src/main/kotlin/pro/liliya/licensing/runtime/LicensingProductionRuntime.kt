package pro.liliya.licensing.runtime

enum class LicensingRuntimeState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    FAILED
}

enum class LicensingRuntimeDependencyKind {
    POSTGRESQL,
    OPENBAO_TRANSIT,
    REQUEST_AUTHENTICATION
}

enum class LicensingRuntimeFailure {
    INVALID_STATE,
    POSTGRESQL_UNAVAILABLE,
    OPENBAO_UNAVAILABLE,
    REQUEST_AUTHENTICATION_UNAVAILABLE,
    LISTENER_UNAVAILABLE,
    SHUTDOWN_FAILURE
}

sealed interface LicensingRuntimeDependencyResult {
    data object Ready : LicensingRuntimeDependencyResult
    data class Failed(val reason: LicensingRuntimeFailure) : LicensingRuntimeDependencyResult
}

interface LicensingRuntimeDependency : AutoCloseable {
    val kind: LicensingRuntimeDependencyKind

    /**
     * Performs one bounded readiness attempt.
     *
     * The runtime does not retry this operation implicitly.
     */
    fun prepare(): LicensingRuntimeDependencyResult

    override fun close()
}

sealed interface LicensingRuntimeListenerResult {
    data object Started : LicensingRuntimeListenerResult
    data object Failed : LicensingRuntimeListenerResult
}

interface LicensingRuntimeListener : AutoCloseable {
    /**
     * Starts the production listener boundary exactly once for one runtime start attempt.
     *
     * HTTPS/TLS mechanics are supplied by later Slice 7 sub-slices.
     */
    fun start(): LicensingRuntimeListenerResult

    override fun close()
}

sealed interface LicensingRuntimeStartResult {
    data object Ready : LicensingRuntimeStartResult
    data class Failed(val reason: LicensingRuntimeFailure) : LicensingRuntimeStartResult
}

sealed interface LicensingRuntimeStopResult {
    data object Stopped : LicensingRuntimeStopResult
    data class Failed(val reason: LicensingRuntimeFailure) : LicensingRuntimeStopResult
}

/**
 * Slice 7 production runtime lifecycle owner.
 *
 * This composition owns only startup/readiness/shutdown orchestration. It does not own entitlement,
 * signing, replay/revocation policy, LicensePolicy, Authority or Execution semantics.
 */
class LicensingProductionRuntime(
    dependencies: List<LicensingRuntimeDependency>,
    private val listener: LicensingRuntimeListener
) : AutoCloseable {
    private val lock = Any()
    private val dependencies = dependencies.toList()
    private val preparedDependencies = ArrayList<LicensingRuntimeDependency>()
    private var listenerStarted = false

    @Volatile
    private var currentState = LicensingRuntimeState.STOPPED

    init {
        val kinds = this.dependencies.map { it.kind }
        require(kinds.toSet().size == kinds.size) {
            "runtime dependency kinds must be unique"
        }
        require(kinds.containsAll(LicensingRuntimeDependencyKind.entries)) {
            "runtime requires PostgreSQL, OpenBao Transit and request-authentication dependencies"
        }
    }

    fun state(): LicensingRuntimeState = currentState

    fun isReady(): Boolean = currentState == LicensingRuntimeState.READY

    fun start(): LicensingRuntimeStartResult = synchronized(lock) {
        if (currentState != LicensingRuntimeState.STOPPED) {
            return@synchronized LicensingRuntimeStartResult.Failed(
                LicensingRuntimeFailure.INVALID_STATE
            )
        }

        currentState = LicensingRuntimeState.STARTING
        preparedDependencies.clear()
        listenerStarted = false

        for (dependency in dependencies) {
            when (val result = dependency.prepare()) {
                LicensingRuntimeDependencyResult.Ready -> {
                    preparedDependencies += dependency
                }
                is LicensingRuntimeDependencyResult.Failed -> {
                    closePreparedDependencies()
                    currentState = LicensingRuntimeState.FAILED
                    return@synchronized LicensingRuntimeStartResult.Failed(
                        normalizedDependencyFailure(dependency.kind, result.reason)
                    )
                }
            }
        }

        return@synchronized when (listener.start()) {
            LicensingRuntimeListenerResult.Started -> {
                listenerStarted = true
                currentState = LicensingRuntimeState.READY
                LicensingRuntimeStartResult.Ready
            }
            LicensingRuntimeListenerResult.Failed -> {
                closePreparedDependencies()
                currentState = LicensingRuntimeState.FAILED
                LicensingRuntimeStartResult.Failed(
                    LicensingRuntimeFailure.LISTENER_UNAVAILABLE
                )
            }
        }
    }

    fun stop(): LicensingRuntimeStopResult = synchronized(lock) {
        if (
            currentState != LicensingRuntimeState.READY &&
            currentState != LicensingRuntimeState.FAILED
        ) {
            return@synchronized LicensingRuntimeStopResult.Failed(
                LicensingRuntimeFailure.INVALID_STATE
            )
        }

        currentState = LicensingRuntimeState.STOPPING
        var failed = false

        if (listenerStarted) {
            try {
                listener.close()
            } catch (_: RuntimeException) {
                failed = true
            } finally {
                listenerStarted = false
            }
        }

        if (!closePreparedDependencies()) {
            failed = true
        }

        currentState = LicensingRuntimeState.STOPPED
        if (failed) {
            LicensingRuntimeStopResult.Failed(
                LicensingRuntimeFailure.SHUTDOWN_FAILURE
            )
        } else {
            LicensingRuntimeStopResult.Stopped
        }
    }

    override fun close() {
        synchronized(lock) {
            if (
                currentState == LicensingRuntimeState.READY ||
                currentState == LicensingRuntimeState.FAILED
            ) {
                stop()
            }
        }
    }

    override fun toString(): String =
        "LicensingProductionRuntime(state=" + currentState +
            ",dependencies=" + dependencies.map { it.kind } +
            ",listener=<redacted>)"

    private fun closePreparedDependencies(): Boolean {
        var succeeded = true
        preparedDependencies.asReversed().forEach { dependency ->
            try {
                dependency.close()
            } catch (_: RuntimeException) {
                succeeded = false
            }
        }
        preparedDependencies.clear()
        return succeeded
    }

    private fun normalizedDependencyFailure(
        kind: LicensingRuntimeDependencyKind,
        reported: LicensingRuntimeFailure
    ): LicensingRuntimeFailure =
        when (kind) {
            LicensingRuntimeDependencyKind.POSTGRESQL ->
                LicensingRuntimeFailure.POSTGRESQL_UNAVAILABLE
            LicensingRuntimeDependencyKind.OPENBAO_TRANSIT ->
                LicensingRuntimeFailure.OPENBAO_UNAVAILABLE
            LicensingRuntimeDependencyKind.REQUEST_AUTHENTICATION ->
                LicensingRuntimeFailure.REQUEST_AUTHENTICATION_UNAVAILABLE
        }
}
