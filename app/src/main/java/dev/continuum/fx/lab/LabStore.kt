package dev.continuum.fx.lab

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One channel of a measurement, on the Lab's grid. */
class LabChannel(val label: String, val v: DoubleArray)

/** Your earphones, as measured: one channel, or a left and a right. */
class LabMeasurement(
    val id: String,
    val name: String,
    val rig: String,
    val note: String,
    val createdAt: Long,
    val channels: List<LabChannel>,
) {
    val key: String get() = "m:$id"
    val device: Device by lazy { Device(name, rig, channels.map { it.v }, channels.map { it.label }) }
}

/** How a Target Studio target was made, so it can be opened again and changed. */
data class LabRecipe(val model: StudioModel, val taste: StudioTaste)

class LabTarget(
    val id: String,
    val name: String,
    val rig: String,
    val note: String,
    val createdAt: Long,
    val v: DoubleArray,
    val recipe: LabRecipe? = null,
) {
    val key: String get() = "t:$id"
}

/** A fit kept to come back to: its filters, its sides, and what it was fitted from. */
class LabSavedEq(
    val id: String,
    val name: String,
    val device: String,
    val target: String,
    val fs: Double,
    val filters: List<LabFilter>,
    val preamp: Double,
    val channels: List<ChannelFix>?,
    val settings: FitSettings,
    val errBefore: Double,
    val errAfter: Double,
    val createdAt: Long,
) {
    fun apoText(): String = Lab.apoText(filters, preamp, channels)
}

/** Files read and waiting to be named and added: grouped by name, so L and R make one measurement. */
data class PendingImport(
    val key: String,
    val name: String,
    val kind: String,
    val rig: String,
    val channels: List<LabChannel>,
    val files: List<String>,
    val span: String,
)

/** How the Lab is set up: what's on the graph, the studio's sliders and the fit's settings. */
data class LabView(
    val visible: List<String> = listOf("b:live21"),
    val slots: Map<String, Int> = mapOf("b:live21" to 0),
    val normHz: Double = 500.0,
    /** "raw", or the smoothing's fraction of an octave: "24", "12", "6", "3". */
    val smooth: String = "24",
    /** "abs", "lr", "bal", or an id to see everything relative to it. */
    val view: String = "abs",
    /** "auto", "30" or "50" dB. */
    val yRange: String = "auto",
    val model: StudioModel = StudioModel(),
    val taste: StudioTaste = StudioTaste(),
    val studioName: String = "Kira Live v2.1 custom",
    val fit: FitSettings = FitSettings(),
    val device: String = "",
    val target: String = "b:live21",
)

data class LabState(
    val measurements: List<LabMeasurement> = emptyList(),
    val targets: List<LabTarget> = emptyList(),
    val eqs: List<LabSavedEq> = emptyList(),
    val view: LabView = LabView(),
    /** The built-in targets' curves, by id, once they're made. */
    val builtins: Map<String, DoubleArray> = emptyMap(),
    val ref: LabRef? = null,
    val pending: List<PendingImport> = emptyList(),
    /** Files that couldn't be read, from the last import. */
    val problems: List<String> = emptyList(),
) {
    val ready: Boolean get() = ref != null
}

/**
 * Kira Lab's library on the phone: measurements, targets and saved fits, kept in the "lab"
 * settings file (so a backup carries them). It starts empty; the built-in Kira targets come
 * with the app.
 */
object LabStore {
    const val FILE = "lab"
    /** As many curves as the graph shows at once, each in its own line. */
    const val SLOTS = 6
    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // What the old "Fit to a target" kept: the Lab replaces it.
        runCatching { context.deleteSharedPreferences("fit") }
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = LabState(
            measurements = readList(prefs.getString(K_MEAS, null), ::measurement),
            targets = readList(prefs.getString(K_TARGETS, null), ::target),
            eqs = readList(prefs.getString(K_EQS, null), ::savedEq),
            view = prefs.getString(K_VIEW, null)?.let { runCatching { view(JSONObject(it)) }.getOrNull() } ?: LabView(),
        )
        // The reference curves and the built-in targets made from them, off the main thread.
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val ref = LabRef.parse(app.assets.open("lab/refdata.json").bufferedReader().use { it.readText() })
                val builtins = LabTargets.all(ref)
                _state.update { it.copy(ref = ref, builtins = builtins) }
            }
        }
    }

    // ---------- what's on the graph ----------

    fun updateView(transform: (LabView) -> LabView) {
        var changed = false
        _state.update { s ->
            val next = transform(s.view)
            changed = next != s.view
            s.copy(view = next)
        }
        if (changed) saveView()
    }

    /** Shows or hides [id] on the graph. False when the graph is full. */
    fun show(id: String, on: Boolean): Boolean {
        val v = _state.value.view
        if (on) {
            if (id in v.visible) return true
            val used = v.visible.mapNotNull { v.slots[it] }.toSet()
            val slot = (0 until SLOTS).firstOrNull { it !in used } ?: return false
            updateView { it.copy(visible = it.visible + id, slots = it.slots + (id to slot)) }
        } else {
            updateView { it.copy(visible = it.visible - id, slots = it.slots - id) }
        }
        return true
    }

    // ---------- the library ----------

    fun addMeasurement(m: LabMeasurement) {
        _state.update { it.copy(measurements = it.measurements + m) }
        saveList(K_MEAS)
    }

    fun addTarget(t: LabTarget) {
        _state.update { it.copy(targets = it.targets + t) }
        saveList(K_TARGETS)
    }

    fun addEq(e: LabSavedEq) {
        _state.update { it.copy(eqs = it.eqs + e) }
        saveList(K_EQS)
    }

    /** Takes [key] ("m:…", "t:…" or "e:…") out of the library and off the graph. */
    fun delete(key: String) {
        show(key, false)
        val id = key.substringAfter(':')
        when {
            key.startsWith("m:") -> { _state.update { it.copy(measurements = it.measurements.filterNot { m -> m.id == id }) }; saveList(K_MEAS) }
            key.startsWith("t:") -> { _state.update { it.copy(targets = it.targets.filterNot { t -> t.id == id }) }; saveList(K_TARGETS) }
            key.startsWith("e:") -> { _state.update { it.copy(eqs = it.eqs.filterNot { e -> e.id == id }) }; saveList(K_EQS) }
        }
    }

    // ---------- importing ----------

    /**
     * Reads [files] (name and text) as curves, pairing files whose names end in L and R into one
     * measurement, and holds them to be named and added.
     */
    fun stage(files: List<Pair<String, String>>) {
        class Group(val name: String) {
            val channels = ArrayList<LabChannel>()
            val names = ArrayList<String>()
            var span = ""
        }
        val groups = LinkedHashMap<String, Group>()
        val problems = ArrayList<String>()
        for ((file, text) in files) {
            val c = Lab.parseCurveText(text)
            if (c.f.size < 8 || c.f.last() / c.f.first() < 50) {
                problems += file
                continue
            }
            val (name, channel) = Lab.splitChannelName(file)
            val g = groups.getOrPut(name.lowercase()) { Group(name) }
            g.channels += LabChannel(channel, LabIds.r2(Lab.resample(c.f, c.y)))
            g.names += file
            g.span = "${LabIds.hz(c.f.first())}–${LabIds.hz(c.f.last())}"
        }
        val looksTarget = Regex("target|harman|kira|diffuse|\\bdf\\b|tilt|preference|curve", RegexOption.IGNORE_CASE)
        val pending = groups.values.map { g ->
            PendingImport(
                key = "p" + java.lang.Long.toString((Math.random() * 1e12).toLong(), 36),
                name = g.name,
                kind = if (looksTarget.containsMatchIn(g.name)) "target" else "measurement",
                rig = "711",
                channels = g.channels.toList(),
                files = g.names.toList(),
                span = g.span,
            )
        }
        _state.update { it.copy(pending = it.pending + pending, problems = problems) }
    }

    fun updatePending(key: String, transform: (PendingImport) -> PendingImport) =
        _state.update { s -> s.copy(pending = s.pending.map { if (it.key == key) transform(it) else it }) }

    fun dropPending(key: String?) = _state.update { s ->
        if (key == null) s.copy(pending = emptyList(), problems = emptyList()) else s.copy(pending = s.pending.filterNot { it.key == key })
    }

    /** Adds what's waiting to the library and shows it. Returns how many were added. */
    fun commitPending(): Int {
        val pending = _state.value.pending
        val now = System.currentTimeMillis()
        val added = ArrayList<String>()
        for (p in pending) {
            val name = p.name.trim().ifEmpty { "Measurement" }
            val note = "Imported from ${p.files.joinToString()}"
            val id = LabIds.slug(name, now + added.size)
            if (p.kind == "measurement") {
                addMeasurement(LabMeasurement(id, name, p.rig, note, now, p.channels))
                added += "m:$id"
            } else {
                addTarget(LabTarget(id, name, p.rig, note, now, LabIds.r2(Lab.powerAverage(p.channels.map { it.v }))))
                added += "t:$id"
            }
        }
        _state.update { it.copy(pending = emptyList(), problems = emptyList()) }
        for (id in added) show(id, true)
        // A first measurement is the one to fit.
        val first = added.firstOrNull { it.startsWith("m:") }
        if (first != null && _state.value.measurements.none { it.key == _state.value.view.device }) updateView { it.copy(device = first) }
        return added.size
    }

    // ---------- saving ----------

    private const val K_MEAS = "measurements"
    private const val K_TARGETS = "targets"
    private const val K_EQS = "eqs"
    private const val K_VIEW = "view"

    private fun prefs() = appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun saveList(key: String) {
        val s = _state.value
        val arr = JSONArray()
        when (key) {
            K_MEAS -> s.measurements.forEach { arr.put(json(it)) }
            K_TARGETS -> s.targets.forEach { arr.put(json(it)) }
            K_EQS -> s.eqs.forEach { arr.put(json(it)) }
        }
        prefs()?.edit()?.putString(key, arr.toString())?.apply()
    }

    private fun saveView() {
        prefs()?.edit()?.putString(K_VIEW, json(_state.value.view).toString())?.apply()
    }

    private fun <T> readList(raw: String?, read: (JSONObject) -> T?): List<T> {
        if (raw == null) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { read(it) }.getOrNull() } }
    }

    private fun arr(v: DoubleArray) = JSONArray().also { a -> v.forEach { a.put(it) } }
    private fun doubles(a: JSONArray?): DoubleArray? = a?.let { DoubleArray(it.length()) { i -> it.optDouble(i, 0.0) } }?.takeIf { it.size == Lab.N }

    private fun json(m: LabMeasurement) = JSONObject()
        .put("id", m.id).put("name", m.name).put("rig", m.rig).put("note", m.note).put("createdAt", m.createdAt)
        .put("channels", JSONArray().also { a -> m.channels.forEach { c -> a.put(JSONObject().put("label", c.label).put("v", arr(c.v))) } })

    private fun measurement(o: JSONObject): LabMeasurement? {
        val chs = o.optJSONArray("channels") ?: return null
        val channels = (0 until chs.length()).mapNotNull { i ->
            val c = chs.optJSONObject(i) ?: return@mapNotNull null
            LabChannel(c.optString("label", "avg"), doubles(c.optJSONArray("v")) ?: return@mapNotNull null)
        }
        if (channels.isEmpty()) return null
        return LabMeasurement(o.getString("id"), o.optString("name", "Measurement"), o.optString("rig", "711"), o.optString("note"), o.optLong("createdAt"), channels)
    }

    private fun json(m: StudioModel) = JSONObject()
        .put("rig", m.rig).put("r", m.r).put("playPhon", m.playPhon).put("livePhon", m.livePhon).put("projPeak", m.projPeak).put("Tmid", m.Tmid).put("BR", m.BR)

    private fun model(o: JSONObject?): StudioModel {
        val d = StudioModel()
        if (o == null) return d
        return StudioModel(
            rig = o.optString("rig", d.rig).takeIf { it in listOf("711", "5128iem", "5128") } ?: d.rig,
            r = o.optDouble("r", d.r), playPhon = o.optDouble("playPhon", d.playPhon), livePhon = o.optDouble("livePhon", d.livePhon),
            projPeak = o.optDouble("projPeak", d.projPeak), Tmid = o.optDouble("Tmid", d.Tmid), BR = o.optDouble("BR", d.BR),
        )
    }

    private fun json(t: StudioTaste) = JSONObject()
        .put("bassG", t.bassG).put("bassF", t.bassF).put("bodyG", t.bodyG).put("earG", t.earG).put("earF", t.earF)
        .put("presG", t.presG).put("airG", t.airG).put("airF", t.airF).put("tilt", t.tilt).put("sep", t.sep)

    private fun taste(o: JSONObject?, base: StudioTaste = StudioTaste()): StudioTaste {
        if (o == null) return base
        return StudioTaste(
            bassG = o.optDouble("bassG", base.bassG), bassF = o.optDouble("bassF", base.bassF), bodyG = o.optDouble("bodyG", base.bodyG),
            earG = o.optDouble("earG", base.earG), earF = o.optDouble("earF", base.earF), presG = o.optDouble("presG", base.presG),
            airG = o.optDouble("airG", base.airG), airF = o.optDouble("airF", base.airF), tilt = o.optDouble("tilt", base.tilt), sep = o.optDouble("sep", base.sep),
        )
    }

    private fun json(t: LabTarget) = JSONObject()
        .put("id", t.id).put("name", t.name).put("rig", t.rig).put("note", t.note).put("createdAt", t.createdAt).put("v", arr(t.v))
        .also { o -> t.recipe?.let { o.put("recipe", JSONObject().put("model", json(it.model)).put("taste", json(it.taste))) } }

    private fun target(o: JSONObject): LabTarget? {
        val v = doubles(o.optJSONArray("v")) ?: return null
        // A recipe made before the carve was a slider is plain Live v2: separation 0.
        val recipe = o.optJSONObject("recipe")?.let { LabRecipe(model(it.optJSONObject("model")), taste(it.optJSONObject("taste"), StudioTaste(sep = 0.0))) }
        return LabTarget(o.getString("id"), o.optString("name", "Target"), o.optString("rig", "711"), o.optString("note"), o.optLong("createdAt"), v, recipe)
    }

    private fun json(f: LabFilter) = JSONObject().put("type", f.type).put("fc", f.fc).put("gain", f.gain).put("q", f.q)
    private fun filter(o: JSONObject) = LabFilter(o.optString("type", "PK"), o.optDouble("fc", 1000.0), o.optDouble("gain", 0.0), o.optDouble("q", 1.0))
    private fun filters(a: JSONArray?): List<LabFilter> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::filter) }

    private fun json(s: FitSettings) = JSONObject()
        .put("fs", s.fs).put("fmax", s.fmax).put("maxFilters", s.maxFilters).put("maxBoost", s.maxBoost).put("perceptual", s.perceptual)
        .put("asym", s.asym).put("robustHF", s.robustHF).put("useSpread", s.useSpread).put("matchLR", s.matchLR)

    private fun settings(o: JSONObject?): FitSettings {
        val d = FitSettings()
        if (o == null) return d
        return FitSettings(
            fs = o.optDouble("fs", d.fs).takeIf { it in listOf(44100.0, 48000.0, 96000.0) } ?: d.fs,
            fmax = o.optDouble("fmax", d.fmax).coerceIn(10000.0, 20000.0),
            maxFilters = o.optInt("maxFilters", d.maxFilters).coerceIn(3, 12),
            maxBoost = o.optDouble("maxBoost", d.maxBoost).coerceIn(0.0, 12.0),
            perceptual = o.optBoolean("perceptual", d.perceptual), asym = o.optBoolean("asym", d.asym), robustHF = o.optBoolean("robustHF", d.robustHF),
            useSpread = o.optBoolean("useSpread", d.useSpread), matchLR = o.optBoolean("matchLR", d.matchLR),
        )
    }

    private fun json(e: LabSavedEq) = JSONObject()
        .put("id", e.id).put("name", e.name).put("device", e.device).put("target", e.target).put("fs", e.fs)
        .put("filters", JSONArray().also { a -> e.filters.forEach { a.put(json(it)) } }).put("preamp", e.preamp)
        .put("settings", json(e.settings)).put("errBefore", e.errBefore).put("errAfter", e.errAfter).put("createdAt", e.createdAt)
        .also { o ->
            e.channels?.let { chans ->
                o.put("channels", JSONArray().also { a ->
                    chans.forEach { c -> a.put(JSONObject().put("label", c.label).put("level", c.level).put("filters", JSONArray().also { fa -> c.filters.forEach { fa.put(json(it)) } })) }
                })
            }
        }

    private fun savedEq(o: JSONObject): LabSavedEq {
        val chans = o.optJSONArray("channels")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> ChannelFix(c.optString("label"), c.optDouble("level", 0.0), filters(c.optJSONArray("filters"))) } }
        }
        return LabSavedEq(
            o.getString("id"), o.optString("name", "EQ"), o.optString("device"), o.optString("target"), o.optDouble("fs", 48000.0),
            filters(o.optJSONArray("filters")), o.optDouble("preamp", 0.0), chans, settings(o.optJSONObject("settings")),
            o.optDouble("errBefore", 0.0), o.optDouble("errAfter", 0.0), o.optLong("createdAt"),
        )
    }

    private fun json(v: LabView) = JSONObject()
        .put("visible", JSONArray(v.visible))
        .put("slots", JSONObject().also { o -> v.slots.forEach { (k, s) -> o.put(k, s) } })
        .put("normHz", v.normHz).put("smooth", v.smooth).put("view", v.view).put("yRange", v.yRange)
        .put("model", json(v.model)).put("taste", json(v.taste)).put("studioName", v.studioName)
        .put("fit", json(v.fit)).put("device", v.device).put("target", v.target)

    private fun view(o: JSONObject): LabView {
        val d = LabView()
        val vis = (o.optJSONArray("visible")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: d.visible)
            .distinct().take(SLOTS)
        val slotsO = o.optJSONObject("slots")
        val slots = HashMap<String, Int>()
        for (id in vis) {
            val s = slotsO?.optInt(id, -1) ?: -1
            slots[id] = if (s in 0 until SLOTS && s !in slots.values) s else (0 until SLOTS).first { it !in slots.values }
        }
        return LabView(
            visible = vis, slots = slots,
            normHz = o.optDouble("normHz", d.normHz).takeIf { it == 500.0 || it == 1000.0 } ?: d.normHz,
            smooth = o.optString("smooth", d.smooth).takeIf { it in listOf("raw", "24", "12", "6", "3") } ?: d.smooth,
            view = o.optString("view", d.view).ifEmpty { d.view },
            yRange = o.optString("yRange", d.yRange).takeIf { it in listOf("auto", "30", "50") } ?: d.yRange,
            model = model(o.optJSONObject("model")), taste = taste(o.optJSONObject("taste")),
            studioName = o.optString("studioName", d.studioName),
            fit = settings(o.optJSONObject("fit")), device = o.optString("device", ""), target = o.optString("target", d.target).ifEmpty { d.target },
        )
    }
}
