package dev.continuum.fx.volume

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the volume panel behaves. */
data class VolumePrefs(
    /** The volume keys open the Continuum panel instead of Android's. */
    val enabled: Boolean = true,
    /** Which screen edge the panel grows out of: where the volume buttons are. */
    val side: String = SIDE_RIGHT,
    /** Where along that edge, 0 at the top to 1 at the bottom. */
    val height: Float = HEIGHT_MIDDLE,
    /** A light tick on every step. */
    val haptics: Boolean = true,
    /** Volume up past 100%, two deliberate presses at the top. */
    val boost: Boolean = false,
    /** The most boost allowed, in steps of 1.5 dB ([BoostPolicy]). */
    val boostSteps: Int = BoostPolicy.DEFAULT_MAX_STEPS,
    /** Keep each app's volume between sessions and restarts. */
    val rememberApps: Boolean = true,
    /** The first press opens every stream at once, not just the one the keys move. */
    val openExpanded: Boolean = false,
    /** Leave the volume keys to the camera app while it's open, for its shutter. */
    val cameraKeys: Boolean = true,
) {
    val left: Boolean get() = side == SIDE_LEFT

    companion object {
        const val SIDE_RIGHT = "right"
        const val SIDE_LEFT = "left"
        const val HEIGHT_HIGHER = 0.28f
        const val HEIGHT_MIDDLE = 0.4f
        const val HEIGHT_LOWER = 0.55f
    }
}

object VolumeSettings {
    private const val FILE = "volume"
    private val _state = MutableStateFlow(VolumePrefs())
    val state: StateFlow<VolumePrefs> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = VolumePrefs(
            enabled = p.getBoolean("enabled", true),
            side = p.getString("side", null) ?: VolumePrefs.SIDE_RIGHT,
            height = p.getFloat("height", VolumePrefs.HEIGHT_MIDDLE),
            haptics = p.getBoolean("haptics", true),
            boost = p.getBoolean("boost", false),
            boostSteps = BoostPolicy.normalizeMaxSteps(p.getInt("boostSteps", BoostPolicy.DEFAULT_MAX_STEPS)),
            rememberApps = p.getBoolean("rememberApps", true),
            openExpanded = p.getBoolean("openExpanded", false),
            cameraKeys = p.getBoolean("cameraKeys", true),
        )
    }

    fun update(transform: (VolumePrefs) -> VolumePrefs) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("enabled", next.enabled)
            ?.putString("side", next.side)
            ?.putFloat("height", next.height)
            ?.putBoolean("haptics", next.haptics)
            ?.putBoolean("boost", next.boost)
            ?.putInt("boostSteps", next.boostSteps)
            ?.putBoolean("rememberApps", next.rememberApps)
            ?.putBoolean("openExpanded", next.openExpanded)
            ?.putBoolean("cameraKeys", next.cameraKeys)
            ?.apply()
    }
}

/**
 * The boost scale, after Volume++ (MIT): each 5 points past 100% asks for another 1.5 dB,
 * so 120% is +6 dB and 150% is +15 dB. "120%" is a loudness label, not 1.2 times the signal.
 */
object BoostPolicy {
    const val DEFAULT_MAX_STEPS = 4
    const val MIN_MAX_STEPS = 2
    const val HARD_MAX_STEPS = 10
    const val PERCENT_PER_STEP = 5
    const val DB_PER_STEP = 1.5f

    fun normalizeMaxSteps(value: Int): Int = value.coerceIn(MIN_MAX_STEPS, HARD_MAX_STEPS)
    fun percent(steps: Int): Int = 100 + steps.coerceIn(0, HARD_MAX_STEPS) * PERCENT_PER_STEP
    fun db(steps: Int): Float = steps.coerceIn(0, HARD_MAX_STEPS) * DB_PER_STEP
    fun millibels(steps: Int): Int = (db(steps) * 100f).toInt()
}

/** Whether the volume service is running, for the app's screens. */
object VolumeStatus {
    val serviceOn = MutableStateFlow(false)
}
