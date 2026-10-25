package dev.continuum.fx.volume

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.OutputWatcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Volume past 100%. At the top of media volume, two deliberate presses of volume up start a
 * boost and each further press adds a step; volume down takes it back first. A held key never
 * climbs into it.
 *
 * With Continuum EQ processing, the boost is extra gain inside its own chain, in front of the
 * limiter that keeps peaks clean. Otherwise it's Android's loudness effect on the whole output,
 * attached by the Shizuku helper. It clears itself when the sound moves to another device (a
 * level picked for the speaker is too much for earbuds) and whenever the volume drops below
 * the top.
 */
object Booster {
    private val _steps = MutableStateFlow(0)
    val steps: StateFlow<Int> = _steps.asStateFlow()

    /** One press at the top has been made; the next starts the boost. */
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    /** Whether the last key the booster took changed anything (for the haptic tick). */
    var lastKeyMoved = false
        private set

    private var armedAt = 0L
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    enum class Route { Engine, Helper, None }

    fun route(): Route = when {
        EngineStatus.running.value -> Route.Engine
        VolumeHelper.ready.value -> Route.Helper
        else -> Route.None
    }

    fun init(context: Context) {
        if (started) return
        started = true
        val scope = MainScope()
        // The route changed (processing started or stopped, the helper came or went): move the gain.
        scope.launch { EngineStatus.running.drop(1).collect { apply() } }
        scope.launch { VolumeHelper.ready.drop(1).collect { apply() } }
        // A different output: start again from 100%.
        scope.launch { OutputWatcher.current.map { it?.key }.distinctUntilChanged().drop(1).collect { clear() } }
        // Below the top, there's nothing to boost.
        scope.launch {
            VolumeModel.state.map { it[VolStream.Media].atMax }.distinctUntilChanged().collect { top -> if (!top) clear() }
        }
        // The switch going off, or a lower ceiling, takes effect at once.
        scope.launch {
            VolumeSettings.state.collect { p ->
                if (!p.boost) clear() else if (_steps.value > p.boostSteps) set(p.boostSteps)
            }
        }
    }

    /**
     * A volume key while media is what the keys move. True when the boost took it, so the
     * stream itself shouldn't move.
     */
    fun onKey(dir: Int, repeat: Boolean, media: Level, prefs: VolumePrefs): Boolean {
        lastKeyMoved = false
        val s = _steps.value
        if (dir < 0) {
            _armed.value = false
            if (s > 0) {
                set(s - 1)
                lastKeyMoved = true
                return true
            }
            return false
        }
        if (!prefs.boost || !media.atMax || route() == Route.None) return false
        if (s > 0) {
            if (!repeat && s < prefs.boostSteps) {
                set(s + 1)
                lastKeyMoved = true
            }
            return true
        }
        if (repeat) return true
        val now = SystemClock.uptimeMillis()
        if (_armed.value && now - armedAt < ARM_WINDOW_MS) {
            _armed.value = false
            set(1)
            lastKeyMoved = true
        } else {
            _armed.value = true
            armedAt = now
            // The offer lapses if the second press doesn't come.
            main.postDelayed({ if (SystemClock.uptimeMillis() - armedAt >= ARM_WINDOW_MS) _armed.value = false }, ARM_WINDOW_MS)
        }
        return true
    }

    fun clear() {
        _armed.value = false
        if (_steps.value != 0) set(0)
    }

    private fun set(n: Int) {
        _steps.value = n
        apply()
    }

    private fun apply() {
        val n = _steps.value
        when (route()) {
            Route.Engine -> {
                EngineStatus.boostDb.value = BoostPolicy.db(n)
                VolumeHelper.setLoudnessGain(0)
            }
            Route.Helper -> {
                EngineStatus.boostDb.value = 0f
                VolumeHelper.setLoudnessGain(BoostPolicy.millibels(n))
            }
            Route.None -> {
                EngineStatus.boostDb.value = 0f
                if (n != 0) _steps.value = 0
            }
        }
    }

    private const val ARM_WINDOW_MS = 1_500L
}
