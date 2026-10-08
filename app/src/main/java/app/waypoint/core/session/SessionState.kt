package app.waypoint.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class TargetPlace(
    val lat: Double,
    val lon: Double,
    val name: String,
    val sub: String = ""
)

enum class MockPhase { Idle, Acquiring, Mocking, Releasing }

/**
 * Single source of truth for the mock session, shared by every screen,
 * the foreground service and the automation receivers.
 */
class SessionState {
    val phase = MutableStateFlow(MockPhase.Idle)
    val target = MutableStateFlow(TargetPlace(31.5204, 74.3587, "Lahore, Punjab", "Pakistan"))
    val since = MutableStateFlow<Long?>(null)

    /** Acquire sequence: step 0..2 and satellite count 0..12 for the panel readout. */
    val acquireStep = MutableStateFlow(0)
    val acquireSats = MutableStateFlow(0)

    /** Route playback piggybacks on the mock session. */
    val routeActive = MutableStateFlow(false)
    val routeProgress = MutableStateFlow(0f)

    /** Bearing/speed of the latest injected fix (route playback), for the readout. */
    val liveBearing = MutableStateFlow(0f)
    val liveSpeedMs = MutableStateFlow(0f)

    val lastError = MutableStateFlow<String?>(null)

    /** Gold is shown while mocking or releasing (design: active = mocking || release). */
    val live = combine(phase, routeActive) { p, r ->
        p == MockPhase.Mocking || p == MockPhase.Releasing || r
    }

    val isMockingNow: Boolean
        get() = phase.value == MockPhase.Mocking || phase.value == MockPhase.Releasing

    val isLiveNow: Boolean
        get() = isMockingNow || routeActive.value
}
