package dev.continuum.fx.audio

import android.content.Context
import dev.continuum.fx.dsp.Head
import dev.continuum.fx.dsp.HeadModel
import dev.continuum.fx.dsp.HeadSize
import dev.continuum.fx.dsp.RoomKind
import dev.continuum.fx.dsp.SpatialLayout
import dev.continuum.fx.dsp.SpatialLibrary
import dev.continuum.fx.dsp.SpatialSetup
import dev.continuum.fx.dsp.Speaker
import dev.continuum.fx.dsp.SpeakerRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Spatial audio's setups and which apps use which, in memory for the UI and engine, stored as JSON. */
object SpatialStore {
    private const val FILE = "spatial"
    private const val KEY = "spatial_v1"
    private val _state = MutableStateFlow(SpatialLibrary())
    val state: StateFlow<SpatialLibrary> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        _state.value = raw?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } ?: SpatialLibrary()
    }

    @Synchronized
    fun update(transform: (SpatialLibrary) -> SpatialLibrary) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, encode(next).toString())?.apply()
    }

    fun updateSetup(id: String, transform: (SpatialSetup) -> SpatialSetup) = update { lib ->
        lib.copy(setups = lib.setups.map { if (it.id == id) transform(it) else it })
    }

    /** A new setup copied from [from], made the default for nothing yet. Returns its id. */
    fun duplicate(from: SpatialSetup): String {
        val id = UUID.randomUUID().toString().take(8)
        update { lib ->
            var n = 2
            var name = "${from.name} $n"
            while (lib.setups.any { it.name == name }) name = "${from.name} ${++n}"
            lib.copy(setups = lib.setups + from.copy(id = id, name = name))
        }
        return id
    }

    /** Removes a setup; apps that used it go back to the default. The last one stays. */
    fun delete(id: String) = update { lib ->
        if (lib.setups.size <= 1) return@update lib
        val left = lib.setups.filter { it.id != id }
        lib.copy(
            setups = left,
            defaultId = if (lib.defaultId == id) left.first().id else lib.defaultId,
            apps = lib.apps.filterValues { it != id },
        )
    }

    fun encode(l: SpatialLibrary): JSONObject = JSONObject().apply {
        put("enabled", l.enabled)
        put("default", l.defaultId)
        put("apps", JSONObject().also { o -> l.apps.forEach { (k, v) -> o.put(k, v) } })
        put("setups", JSONArray().also { a -> l.setups.forEach { a.put(encode(it)) } })
    }

    private fun encode(s: SpatialSetup): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("layout", s.layout.name)
        put("speakers", JSONArray().also { a ->
            s.speakers.forEach { sp ->
                a.put(JSONObject().put("role", sp.role.name).put("angle", sp.angle.toDouble()).put("height", sp.height.toDouble())
                    .put("distance", sp.distance.toDouble()).put("level", sp.levelDb.toDouble()))
            }
        })
        put("room", s.room.name)
        put("reflections", s.reflections.toDouble())
        put("tail", s.tail.toDouble())
        put("decay", s.decay.toDouble())
        put("width", s.width.toDouble())
        put("centre", s.centreDb.toDouble())
        put("surround", s.surroundDb.toDouble())
        put("bass", s.bassHz)
        put("head", s.head.name)
        put("headSize", s.headSize.name)
    }

    fun decode(o: JSONObject): SpatialLibrary {
        val setups = ArrayList<SpatialSetup>()
        o.optJSONArray("setups")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { decodeSetup(it) }?.let { setups += it } }
        val d = SpatialLibrary()
        val apps = HashMap<String, String>()
        o.optJSONObject("apps")?.let { m -> m.keys().forEach { k -> apps[k] = m.optString(k) } }
        val list = setups.ifEmpty { d.setups }
        return SpatialLibrary(
            enabled = o.optBoolean("enabled", d.enabled),
            setups = list,
            defaultId = o.optString("default").takeIf { id -> list.any { it.id == id } } ?: list.first().id,
            apps = apps.filterValues { v -> v == SpatialLibrary.OFF || list.any { it.id == v } },
        )
    }

    private fun decodeSetup(o: JSONObject): SpatialSetup? {
        val id = o.optString("id").ifEmpty { return null }
        val layout = SpatialLayout.entries.firstOrNull { it.name == o.optString("layout") } ?: SpatialLayout.Stereo
        val speakers = ArrayList<Speaker>()
        o.optJSONArray("speakers")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val role = SpeakerRole.entries.firstOrNull { it.name == s.optString("role") } ?: continue
                speakers += Speaker(
                    role = role,
                    angle = s.optDouble("angle", 0.0).toFloat().coerceIn(-180f, 180f),
                    height = s.optDouble("height", 0.0).toFloat().coerceIn(SpatialSetup.MIN_HEIGHT, SpatialSetup.MAX_HEIGHT),
                    distance = s.optDouble("distance", 2.0).toFloat().coerceIn(SpatialSetup.MIN_DISTANCE, SpatialSetup.MAX_DISTANCE),
                    levelDb = s.optDouble("level", 0.0).toFloat().coerceIn(-12f, 6f),
                )
            }
        }
        // A layout's speakers, exactly: the stored ones where they match, defaults for the rest.
        val defaults = SpatialSetup.defaultSpeakers(layout)
        val fitted = defaults.map { def -> speakers.firstOrNull { it.role == def.role } ?: def }
        val d = SpatialSetup(id = id, name = "")
        return SpatialSetup(
            id = id,
            name = o.optString("name").ifEmpty { "Setup" },
            layout = layout,
            speakers = fitted,
            room = RoomKind.entries.firstOrNull { it.name == o.optString("room") } ?: d.room,
            reflections = o.optDouble("reflections", d.reflections.toDouble()).toFloat().coerceIn(0f, 1f),
            tail = o.optDouble("tail", d.tail.toDouble()).toFloat().coerceIn(0f, 1f),
            decay = o.optDouble("decay", d.decay.toDouble()).toFloat().coerceIn(0.1f, 2f),
            width = o.optDouble("width", 1.0).toFloat().coerceIn(0.5f, 1.5f),
            centreDb = o.optDouble("centre", 0.0).toFloat().coerceIn(-12f, 9f),
            surroundDb = o.optDouble("surround", 0.0).toFloat().coerceIn(-12f, 9f),
            bassHz = o.optInt("bass", d.bassHz).coerceIn(0, 300),
            head = HeadModel.entries.firstOrNull { it.name == o.optString("head") } ?: d.head,
            headSize = HeadSize.entries.firstOrNull { it.name == o.optString("headSize") } ?: d.headSize,
        )
    }
}

/** The measured heads, read from the app's assets the first time each is needed. */
internal object Heads {
    private val cache = HashMap<HeadModel, Head>()

    @Synchronized
    fun get(context: Context, model: HeadModel): Head? = cache[model] ?: try {
        context.assets.open(model.asset).use { Head.parse(it.readBytes()) }.also { cache[model] = it }
    } catch (_: Exception) {
        null
    }
}
