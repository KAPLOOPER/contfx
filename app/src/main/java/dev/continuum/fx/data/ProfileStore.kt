package dev.continuum.fx.data

import android.content.Context
import dev.continuum.fx.dsp.EqBand
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.FilterType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Everything the user has set up: saved EQ profiles, which one is live, and the EQ switch. */
data class Library(
    val profiles: List<EqProfile>,
    val activeId: String,
    val eqEnabled: Boolean = true,
) {
    val active: EqProfile
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
}

/**
 * Keeps the [Library] in memory as a StateFlow (so the UI and the audio engine
 * both react instantly) and persists it as JSON in SharedPreferences.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Library> = _state.asStateFlow()

    val library: Library get() = _state.value

    fun update(transform: (Library) -> Library) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    fun updateActive(transform: (EqProfile) -> EqProfile) = update { lib ->
        lib.copy(profiles = lib.profiles.map { if (it.id == lib.activeId) transform(it) else it })
    }

    fun select(id: String) = update { it.copy(activeId = id) }

    fun add(profile: EqProfile) = update { it.copy(profiles = it.profiles + profile, activeId = profile.id) }

    fun delete(id: String) = update { lib ->
        val rest = lib.profiles.filterNot { it.id == id }.ifEmpty { listOf(EqProfile.flat()) }
        lib.copy(profiles = rest, activeId = if (lib.activeId == id) rest.first().id else lib.activeId)
    }

    fun setEqEnabled(enabled: Boolean) = update { it.copy(eqEnabled = enabled) }

    private fun load(): Library {
        val raw = prefs.getString(KEY, null) ?: return defaultLibrary()
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("profiles")
            val profiles = (0 until arr.length()).map { profileFromJson(arr.getJSONObject(it)) }
            if (profiles.isEmpty()) defaultLibrary() else Library(
                profiles = profiles,
                activeId = o.optString("activeId", profiles.first().id),
                eqEnabled = o.optBoolean("eqEnabled", true),
            )
        } catch (e: Exception) {
            defaultLibrary()
        }
    }

    private fun save(lib: Library) {
        val o = JSONObject()
        o.put("activeId", lib.activeId)
        o.put("eqEnabled", lib.eqEnabled)
        val arr = JSONArray()
        lib.profiles.forEach { arr.put(profileToJson(it)) }
        o.put("profiles", arr)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun profileToJson(p: EqProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("preamp", p.preampDb)
        val bands = JSONArray()
        p.bands.forEach { b ->
            bands.put(JSONObject().apply {
                put("type", b.type.name)
                put("freq", b.frequency)
                put("gain", b.gainDb)
                put("q", b.q)
                put("on", b.enabled)
            })
        }
        put("bands", bands)
    }

    private fun profileFromJson(o: JSONObject): EqProfile {
        val arr = o.optJSONArray("bands") ?: JSONArray()
        val bands = (0 until arr.length()).mapNotNull { i ->
            val b = arr.getJSONObject(i)
            val type = runCatching { FilterType.valueOf(b.getString("type")) }.getOrNull() ?: return@mapNotNull null
            EqBand(
                type = type,
                frequency = b.optDouble("freq", 1000.0),
                gainDb = b.optDouble("gain", 0.0),
                q = b.optDouble("q", 0.707),
                enabled = b.optBoolean("on", true),
            )
        }
        return EqProfile(
            id = o.getString("id"),
            name = o.optString("name", "EQ"),
            preampDb = o.optDouble("preamp", 0.0),
            bands = bands,
        )
    }

    private fun defaultLibrary(): Library {
        val flat = EqProfile.flat()
        val warm = EqProfile(
            name = "Warm",
            preampDb = -3.5,
            bands = listOf(
                EqBand(FilterType.LOW_SHELF, 110.0, 3.5, 0.7),
                EqBand(FilterType.PEAK, 3200.0, -1.5, 1.2),
                EqBand(FilterType.HIGH_SHELF, 9000.0, -1.0, 0.7),
            ),
        )
        return Library(listOf(flat, warm), flat.id, true)
    }

    private companion object {
        const val KEY = "library_v1"
    }
}
