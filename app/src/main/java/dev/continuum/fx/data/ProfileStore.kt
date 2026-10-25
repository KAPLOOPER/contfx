package dev.continuum.fx.data

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    /** Which profile each output device uses, by [dev.continuum.fx.audio.OutputDevice.key]. */
    val devices: Map<String, String> = emptyMap(),
    /** Which profile each app uses, by package name. */
    val apps: Map<String, String> = emptyMap(),
) {
    val active: EqProfile
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    /** The profile linked to the output with [key], if it still exists. */
    fun profileFor(key: String): EqProfile? = devices[key]?.let { id -> profiles.firstOrNull { it.id == id } }

    /** The profile linked to the app [pkg], if it still exists. */
    fun appProfile(pkg: String): EqProfile? = apps[pkg]?.let { id -> profiles.firstOrNull { it.id == id } }

    /** Apps linked to [profileId]. */
    fun appsFor(profileId: String): List<String> = apps.filterValues { it == profileId }.keys.sorted()

    /**
     * The EQ for everything playing together, when it's all mixed into one (Capture mode): an
     * app's own EQ when everything playing is linked to that one, otherwise the active EQ.
     */
    fun profileForPlaying(pkgs: Collection<String>): EqProfile {
        if (pkgs.isEmpty()) return active
        val linked = pkgs.map { appProfile(it) }
        val first = linked.first() ?: return active
        return if (linked.all { it?.id == first.id }) first else active
    }
}

/**
 * Keeps the [Library] in memory as a StateFlow (so the UI and the audio engine
 * both react instantly) and persists it as JSON in SharedPreferences.
 *
 * Dragging a band changes the library many times a second; it's written to disk once
 * you pause, not on every move.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Library> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val saveLater = Runnable { save(_state.value) }

    val library: Library get() = _state.value

    @Synchronized
    fun update(transform: (Library) -> Library) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, SAVE_DELAY_MS)
    }

    /** Writes any pending change now (when the app goes to the background). */
    fun flush() {
        main.removeCallbacks(saveLater)
        save(_state.value)
    }

    fun updateActive(transform: (EqProfile) -> EqProfile) = update { lib ->
        lib.copy(profiles = lib.profiles.map { if (it.id == lib.activeId) transform(it) else it })
    }

    fun select(id: String) = update { it.copy(activeId = id) }

    fun add(profile: EqProfile) = update { it.copy(profiles = it.profiles + profile, activeId = profile.id) }

    fun delete(id: String) = update { lib ->
        val rest = lib.profiles.filterNot { it.id == id }.ifEmpty { listOf(EqProfile.flat()) }
        lib.copy(
            profiles = rest,
            activeId = if (lib.activeId == id) rest.first().id else lib.activeId,
            devices = lib.devices.filterValues { it != id },
            apps = lib.apps.filterValues { it != id },
        )
    }

    /** Links the app [pkg] to [profileId], or unlinks it when null. */
    fun linkApp(pkg: String, profileId: String?) = update { lib ->
        lib.copy(apps = if (profileId == null) lib.apps - pkg else lib.apps + (pkg to profileId))
    }

    /** Links the output with [deviceKey] to [profileId], or unlinks it when null. */
    fun link(deviceKey: String, profileId: String?) = update { lib ->
        lib.copy(devices = if (profileId == null) lib.devices - deviceKey else lib.devices + (deviceKey to profileId))
    }

    fun setEqEnabled(enabled: Boolean) = update { it.copy(eqEnabled = enabled) }

    private fun load(): Library {
        val raw = prefs.getString(KEY, null) ?: return defaultLibrary()
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("profiles")
            val profiles = (0 until arr.length()).map { profileFromJson(arr.getJSONObject(it)) }
            val links = HashMap<String, String>()
            o.optJSONObject("devices")?.let { d -> d.keys().forEach { k -> links[k] = d.getString(k) } }
            val apps = HashMap<String, String>()
            o.optJSONObject("apps")?.let { d -> d.keys().forEach { k -> apps[k] = d.getString(k) } }
            if (profiles.isEmpty()) defaultLibrary() else Library(
                profiles = profiles,
                activeId = o.optString("activeId", profiles.first().id),
                eqEnabled = o.optBoolean("eqEnabled", true),
                devices = links,
                apps = apps,
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
        val links = JSONObject()
        lib.devices.forEach { (k, v) -> links.put(k, v) }
        o.put("devices", links)
        val apps = JSONObject()
        lib.apps.forEach { (k, v) -> apps.put(k, v) }
        o.put("apps", apps)
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
        const val SAVE_DELAY_MS = 400L
    }
}
