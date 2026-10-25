package dev.continuum.fx.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.continuum.fx.dsp.DynamicsMode
import dev.continuum.fx.dsp.FxContext
import dev.continuum.fx.dsp.FxSettings
import dev.continuum.fx.dsp.Listener
import dev.continuum.fx.dsp.OutputKind
import dev.continuum.fx.dsp.PunchMode
import dev.continuum.fx.dsp.StageMode
import dev.continuum.fx.volume.Level
import dev.continuum.fx.volume.VolStream
import dev.continuum.fx.volume.VolumeModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The effects' settings, kept in memory for the UI and the engines, and stored as JSON. */
object FxStore {
    private const val FILE = "effects"
    private const val KEY = "fx_v1"
    private val _state = MutableStateFlow(FxSettings())
    val state: StateFlow<FxSettings> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        _state.value = raw?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } ?: FxSettings()
    }

    @Synchronized
    fun update(transform: (FxSettings) -> FxSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, encode(next).toString())?.apply()
    }

    fun encode(s: FxSettings): JSONObject = JSONObject().apply {
        put("enabled", s.enabled)
        put("tubeOn", s.tubeOn); put("tube", s.tube)
        put("bassOn", s.bassOn); put("bassDrive", s.bassDrive); put("bassWarmth", s.bassWarmth); put("bassDrift", s.bassDrift)
        put("pultecBoost", s.pultecBoost); put("pultecCut", s.pultecCut); put("pultecFreq", s.pultecFreq)
        put("punch", s.punch.name)
        put("speakerBassOn", s.speakerBassOn); put("speakerBass", s.speakerBass)
        put("airOn", s.airOn); put("air", s.air)
        put("exciterOn", s.exciterOn); put("exciter", s.exciter)
        put("dynamics", s.dynamics.name)
        put("loudnessOn", s.loudnessOn); put("loudness", s.loudness); put("loudnessRef", s.loudnessRef)
        put("stage", s.stage.name)
        put("crossfeedOn", s.crossfeedOn); put("crossfeed", s.crossfeed)
        put("bluetoothCare", s.bluetoothCare)
        put("listeners", JSONObject().also { o -> s.listeners.forEach { (k, v) -> o.put(k, v.name) } })
    }

    fun decode(o: JSONObject): FxSettings {
        val d = FxSettings()
        val listeners = HashMap<String, Listener>()
        o.optJSONObject("listeners")?.let { l ->
            l.keys().forEach { k -> Listener.entries.firstOrNull { it.name == l.optString(k) }?.let { listeners[k] = it } }
        }
        return FxSettings(
            enabled = o.optBoolean("enabled", d.enabled),
            tubeOn = o.optBoolean("tubeOn", d.tubeOn), tube = o.optDouble("tube", d.tube),
            bassOn = o.optBoolean("bassOn", d.bassOn), bassDrive = o.optDouble("bassDrive", d.bassDrive),
            bassWarmth = o.optDouble("bassWarmth", d.bassWarmth), bassDrift = o.optDouble("bassDrift", d.bassDrift),
            pultecBoost = o.optDouble("pultecBoost", d.pultecBoost), pultecCut = o.optDouble("pultecCut", d.pultecCut),
            pultecFreq = o.optInt("pultecFreq", d.pultecFreq),
            punch = PunchMode.entries.firstOrNull { it.name == o.optString("punch") } ?: d.punch,
            speakerBassOn = o.optBoolean("speakerBassOn", d.speakerBassOn), speakerBass = o.optDouble("speakerBass", d.speakerBass),
            airOn = o.optBoolean("airOn", d.airOn), air = o.optDouble("air", d.air),
            exciterOn = o.optBoolean("exciterOn", d.exciterOn), exciter = o.optDouble("exciter", d.exciter),
            dynamics = DynamicsMode.entries.firstOrNull { it.name == o.optString("dynamics") } ?: d.dynamics,
            loudnessOn = o.optBoolean("loudnessOn", d.loudnessOn), loudness = o.optDouble("loudness", d.loudness),
            loudnessRef = o.optDouble("loudnessRef", d.loudnessRef),
            stage = StageMode.entries.firstOrNull { it.name == o.optString("stage") } ?: d.stage,
            crossfeedOn = o.optBoolean("crossfeedOn", d.crossfeedOn), crossfeed = o.optDouble("crossfeed", d.crossfeed),
            bluetoothCare = o.optBoolean("bluetoothCare", d.bluetoothCare),
            listeners = listeners,
        )
    }
}

/**
 * Where the effects are playing, kept current: the output (and what it is) and how far down the
 * media volume is, which Loudness follows.
 */
object FxEnvironment {
    private val _context = MutableStateFlow(FxContext())
    val context: StateFlow<FxContext> = _context.asStateFlow()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val am = context.applicationContext.getSystemService(AudioManager::class.java)
        MainScope().launch {
            combine(
                OutputWatcher.current,
                VolumeModel.state.map { it[VolStream.Media] }.distinctUntilChanged(),
                EngineSettings.mode,
            ) { out, level, mode ->
                val kind = out?.let { OutputKind.valueOf(it.kind.name) } ?: OutputKind.Other
                FxContext(
                    output = kind,
                    outputKey = out?.key.orEmpty(),
                    volumeDb = attenuationDb(am, level, kind),
                    direct = mode == EngineMode.Direct,
                )
            }.distinctUntilChanged().collect { _context.value = it }
        }
    }

    /**
     * How far below full the media volume is, in dB. Android knows each step's level for each kind
     * of output; on Bluetooth with absolute volume it says 0 dB at every step (the headphones set
     * the level), so then Android's own default media curve stands in.
     */
    private fun attenuationDb(am: AudioManager?, level: Level, kind: OutputKind): Double {
        if (level.max <= level.min) return 0.0
        val type = when (kind) {
            OutputKind.Speaker -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            OutputKind.Wired -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            OutputKind.Bluetooth -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            OutputKind.Usb -> AudioDeviceInfo.TYPE_USB_HEADSET
            OutputKind.Other -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        if (am != null) {
            try {
                val top = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, level.max, type)
                val low = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, (level.min + 1).coerceAtMost(level.max), type)
                if (top - low >= 3f) {
                    if (level.index <= level.min) return -96.0
                    return (am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, level.index, type) - top).toDouble()
                }
            } catch (_: Exception) {
            }
        }
        return defaultCurveDb(level.fraction.toDouble())
    }

    /** AOSP's default media volume curve: -58 dB at the first step, -40 at 20%, -17 at 60%, 0 at the top. */
    fun defaultCurveDb(fraction: Double): Double {
        val x = fraction * 100.0
        val pts = doubleArrayOf(1.0, -58.0, 20.0, -40.0, 60.0, -17.0, 100.0, 0.0)
        if (x <= 0.0) return -96.0
        if (x <= pts[0]) return pts[1]
        for (i in 0 until 3) {
            val x0 = pts[2 * i]
            val x1 = pts[2 * i + 2]
            if (x <= x1) return pts[2 * i + 1] + (pts[2 * i + 3] - pts[2 * i + 1]) * (x - x0) / (x1 - x0)
        }
        return 0.0
    }
}

/** Short names for what's on, for status lines. */
fun FxSettings.onNames(): List<String> {
    if (!enabled) return emptyList()
    val out = ArrayList<String>()
    if (bassOn) out += "Analog bass"
    if (punch != PunchMode.Off) out += "Punch"
    if (speakerBassOn) out += "Speaker bass"
    if (tubeOn) out += "Tube"
    if (exciterOn) out += "Exciter"
    if (airOn) out += "Hi-res"
    if (stage != StageMode.Off) out += "Stage"
    if (crossfeedOn) out += "Crossfeed"
    if (dynamics != DynamicsMode.Off) out += "Dynamics"
    if (loudnessOn) out += "Loudness"
    if (bluetoothCare) out += "Bluetooth care"
    return out
}
