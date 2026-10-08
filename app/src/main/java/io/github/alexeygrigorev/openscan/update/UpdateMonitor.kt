package io.github.alexeygrigorev.openscan.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the update banner is (or is not) on screen. */
enum class UpdateStatus { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, FAILED }

/**
 * The update banner's model, and nothing more. One check per launch plus an
 * explicit re-check from the settings screen; the checker never throws, so
 * the monitor's only job is to hold the answer and keep `failed` from being
 * a silent state — the last failure reason stays readable where the check
 * was asked for.
 */
data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String = "",
    val available: UpdateCheckResult.Available? = null,
    val reason: String? = null,
    /** Banner-only dismissal; the status stays `available` so Settings keeps its Download handoff. */
    val dismissed: Boolean = false,
)

/**
 * App-scoped holder of the update check's answer. Lives in
 * [io.github.alexeygrigorev.openscan.data.AppContainer], so the home screen's
 * banner and the settings screen's status line observe the same check.
 */
class UpdateMonitor(
    private val checker: ReleaseChecker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow(UpdateState(currentVersion = checker.currentVersion))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var launchCheckStarted = false

    /**
     * The once-per-launch check, fired from the activity's onCreate. Idempotent
     * across activity recreation (rotation) — the monitor outlives activities.
     */
    fun checkAtLaunch() {
        if (launchCheckStarted) return
        launchCheckStarted = true
        check()
    }

    /** Hide the home-screen banner; a fresh check answers again from scratch. */
    fun dismiss() {
        _state.value = _state.value.copy(dismissed = true)
    }

    /** Explicit re-check ("Check now"); ignored while a check is running. */
    fun check() {
        if (_state.value.status == UpdateStatus.CHECKING) return
        _state.value = _state.value.copy(status = UpdateStatus.CHECKING)
        scope.launch {
            val result = checker.check()
            _state.value = when (result) {
                is UpdateCheckResult.Available -> UpdateState(
                    status = UpdateStatus.AVAILABLE,
                    currentVersion = result.currentVersion,
                    available = result,
                )
                is UpdateCheckResult.UpToDate -> UpdateState(
                    status = UpdateStatus.UP_TO_DATE,
                    currentVersion = result.currentVersion,
                )
                is UpdateCheckResult.Failed -> UpdateState(
                    status = UpdateStatus.FAILED,
                    currentVersion = result.currentVersion,
                    reason = result.reason,
                )
            }
        }
    }
}
