package dev.continuum.fx.ui.lab

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.dsp.EqBand
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.FilterType
import dev.continuum.fx.dsp.SideTrim
import dev.continuum.fx.lab.ChannelFix
import dev.continuum.fx.lab.Device
import dev.continuum.fx.lab.FitSettings
import dev.continuum.fx.lab.Lab
import dev.continuum.fx.lab.LabFilter
import dev.continuum.fx.lab.LabFit
import dev.continuum.fx.lab.LabFitter
import dev.continuum.fx.lab.LabIds
import dev.continuum.fx.lab.LabRecipe
import dev.continuum.fx.lab.LabSavedEq
import dev.continuum.fx.lab.LabState
import dev.continuum.fx.lab.LabStore
import dev.continuum.fx.lab.LabTarget
import dev.continuum.fx.lab.LabTargets
import dev.continuum.fx.lab.LabView
import dev.continuum.fx.lab.PendingImport
import dev.continuum.fx.lab.Studio
import dev.continuum.fx.lab.StudioModel
import dev.continuum.fx.lab.StudioTaste
import dev.continuum.fx.ui.AppActions
import dev.continuum.fx.ui.Fmt
import dev.continuum.fx.ui.Gap
import dev.continuum.fx.ui.HeroLine
import dev.continuum.fx.ui.Kicker
import dev.continuum.fx.ui.MoreSection
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.bench.BenchValueRow
import dev.continuum.fx.ui.bleed
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Kira Lab, in Continuum's ink: the graph across the page, then the ledger under it. Your library
 * (measurements, targets, the Kira targets and references) is what's on the graph; the fit makes
 * an EQ from a measurement to a target, matching left and right first; Target Studio makes a
 * target of your own from Kira Live v2.1. A fit becomes an EQ like any other with one tap.
 */

/** A curve the Lab knows: a built-in, one of yours, or the studio's. */
internal class LabSeries(
    val id: String,
    val label: String,
    val name: String,
    val kind: String,
    val rig: String,
    val y: DoubleArray,
    /** Built-ins (and the studio) are already smooth, so the graph's smoothing leaves them be. */
    val builtin: Boolean,
    val device: Device? = null,
    val recipe: LabRecipe? = null,
)

private fun seriesOf(lab: LabState, id: String, studio: DoubleArray?): LabSeries? = when {
    id == "studio" -> studio?.let { LabSeries(id, "Studio target", "Studio target", "target", Studio.rigTag(lab.view.model.rig), it, true) }
    id.startsWith("b:") -> LabTargets.BY_ID[id]?.let { b -> lab.builtins[id]?.let { LabSeries(id, b.label, b.name, "target", b.rig, it, true) } }
    id.startsWith("m:") -> lab.measurements.firstOrNull { it.key == id }?.let { m -> LabSeries(id, m.name, m.name, "measurement", m.rig, m.device.y, false, m.device) }
    id.startsWith("t:") -> lab.targets.firstOrNull { it.key == id }?.let { t -> LabSeries(id, t.name, t.name, "target", t.rig, t.v, false, recipe = t.recipe) }
    else -> null
}

/** A fit, and what it was fitted from. */
private class FitRun(
    val fit: LabFit?,
    val working: Boolean,
    val ms: Long = 0,
    val devKey: String = "",
    val devName: String = "",
    val devRig: String = "",
    val nch: Int = 1,
    val tgtId: String = "",
    val tgtName: String = "",
    val tgtRig: String = "",
    val tgtOverEar5128: Boolean = false,
)

/** The fit as an EQ: the shared filters, and each side's own trim when left and right were matched. */
private fun LabFilter.band() = EqBand(FilterType.fromCode(type) ?: FilterType.PEAK, fc, gain, q)
private fun ChannelFix.trim() = SideTrim(level, filters.map { it.band() })
private fun profileOf(name: String, filters: List<LabFilter>, preamp: Double, chans: List<ChannelFix>?) = EqProfile(
    name = name,
    preampDb = preamp,
    bands = filters.map { it.band() },
    left = chans?.firstOrNull { it.label == "L" }?.trim(),
    right = chans?.firstOrNull { it.label == "R" }?.trim(),
)

private fun snap(v: Double, min: Double, max: Double, step: Double): Double {
    val s = ((v - min) / step).roundToLong() * step + min
    return (s.coerceIn(min, max) * 10000).roundToLong() / 10000.0
}

private fun one(v: Double) = Lab.fixed(v, 1)
private fun dbShort(v: Double) = (if (v > 0.05) "+" else if (v < -0.05) "−" else "") + Lab.fixed(abs(v), 1)

@Composable
internal fun LabContent(store: ProfileStore, actions: AppActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val lab by LabStore.state.collectAsState()
    val v = lab.view

    // Target Studio's target, made afresh as its sliders move.
    val studioY by produceState<DoubleArray?>(null, lab.ref, v.model, v.taste) {
        val ref = lab.ref ?: return@produceState
        value = withContext(Dispatchers.Default) { Studio.target(ref, v.model, v.taste) }
    }
    fun series(id: String) = seriesOf(lab, id, studioY)

    // What's fitted: the chosen measurement (or the first), to the chosen target.
    val dev = lab.measurements.firstOrNull { it.key == v.device } ?: lab.measurements.firstOrNull()
    val tgt = series(v.target) ?: series("b:live21")
    var last by remember { mutableStateOf<FitRun?>(null) }
    val run by produceState(FitRun(null, false), dev, tgt?.y, v.fit) {
        if (dev == null || tgt == null) {
            value = FitRun(null, false)
            return@produceState
        }
        value = FitRun(last?.fit, true)
        // Sliders move in steps: wait for them to settle before fitting.
        delay(180)
        val t0 = SystemClock.elapsedRealtime()
        val settings = v.fit
        val fit = withContext(Dispatchers.Default) {
            val plan = LabFitter.plan(dev.device, tgt.y, settings, alive = { isActive })
            val results = plan.map { o -> async { Lab.fitEq(o) } }.awaitAll()
            LabFitter.finish(dev.device, settings, results)
        }
        val overEar = tgt.id == "b:live2-5128" || tgt.id == "b:live21-5128" || (tgt.id == "studio" && v.model.rig == "5128") ||
            (tgt.recipe?.model?.rig == "5128")
        value = FitRun(
            fit, false, SystemClock.elapsedRealtime() - t0, dev.key, dev.name, dev.rig, dev.channels.size,
            tgt.id, tgt.name, tgt.rig, overEar,
        )
    }
    LaunchedEffect(run) { if (run.fit != null && !run.working) last = run }
    val fitShown = run.fit ?: last?.fit

    // The graph's curves, smoothed and levelled as the graph's settings say; kept, so a slider
    // moving elsewhere doesn't smooth them all again.
    // Keyed on the curve itself (an array is equal only to itself) and the smoothing.
    val cache = remember { HashMap<Pair<DoubleArray, String>, DoubleArray>() }
    fun smoothed(y: DoubleArray, keep: Boolean = true): DoubleArray {
        if (v.smooth == "raw") return y
        if (!keep) return Lab.smooth(y, 1.0 / v.smooth.toDouble())
        if (cache.size > 96) cache.clear()
        return cache.getOrPut(y to v.smooth) { Lab.smooth(y, 1.0 / v.smooth.toDouble()) }
    }
    fun shown(s: LabSeries): DoubleArray = Lab.norm(if (s.builtin) s.y else smoothed(s.y), v.normHz)

    var studioOpen by rememberSaveable { mutableStateOf(false) }
    val studioShown = studioOpen && studioY != null
    val relId = v.view.takeIf { it != "abs" && it != "lr" && it != "bal" }
    val relSeries = relId?.let { series(it) }
    val notes = ArrayList<String>()
    val lines = remember(lab, studioY, fitShown, v, studioShown) {
        val out = ArrayList<LabLine>()
        fun inkOf(id: String) = Ink.SLOTS[(v.slots[id] ?: 0).coerceIn(0, Ink.SLOTS.size - 1)]
        if (v.view == "bal") {
            for (id in v.visible) {
                val s = series(id) ?: continue
                val d = s.device ?: continue
                val ix = d.lr ?: continue
                out += LabLine("$id|bal", "${s.label}: L − R", smoothed(Lab.sub(d.chs[ix.first], d.chs[ix.second]), keep = false), inkOf(id))
                val f = fitShown
                if (f?.balAfter != null && dev?.key == id) out += LabLine("balout", "${s.label}, matched: L − R", smoothed(f.balAfter, keep = false), Ink.RESULT)
            }
        } else {
            for (id in v.visible) {
                val s = series(id) ?: continue
                val d = s.device
                val ix = d?.lr
                if (v.view == "lr" && d != null && ix != null) {
                    // Each side on the pair's level, so the difference between them shows.
                    val off = Lab.at(smoothed(d.y), v.normHz)
                    val l = smoothed(d.chs[ix.first]).let { y -> DoubleArray(Lab.N) { y[it] - off } }
                    val r = smoothed(d.chs[ix.second]).let { y -> DoubleArray(Lab.N) { y[it] - off } }
                    out += LabLine("$id|L", "${s.label} · L", l, inkOf(id))
                    out += LabLine("$id|R", "${s.label} · R", r, inkOf(id).dashed(Ink.SHORT))
                    continue
                }
                out += LabLine(id, s.label, shown(s), inkOf(id))
            }
            if (studioShown) {
                val baseId = Studio.baseId(v.model.rig)
                if (baseId !in v.visible) series(baseId)?.let { out += LabLine(baseId, "${it.label} (base)", shown(it), Ink.BASE) }
                series("studio")?.let { out += LabLine("studio", "Studio target", shown(it), Ink(0, floatArrayOf(9f, 3f), 3f, halo = true)) }
            }
            val f = fitShown
            val fd = dev
            if (f != null && fd != null) {
                val y = Lab.norm(Lab.add(shown(seriesOf(lab, fd.key, studioY)!!), f.main.eq), v.normHz)
                out += LabLine("eqout", "${fd.name} + EQ", y, Ink.RESULT)
            }
            val ref = relSeries?.let { shown(it) }
            if (ref != null) return@remember out.map { LabLine(it.id, it.label, Lab.sub(it.y, ref), it.ink) }
        }
        out
    }
    when {
        relSeries != null && v.view !in listOf("abs", "lr", "bal") -> notes += "Relative to ${relSeries.label}."
        v.view == "bal" -> notes += if (lines.isNotEmpty()) "Above 0, left is louder. Shaded: within 1 dB either way." else "Show a measurement with L and R files to see how its sides compare."
        v.view == "lr" && lines.none { it.id.endsWith("|R") } -> notes += "No measurement with L and R is showing, so each curve is the pair's average."
    }
    var scrubHz by remember { mutableStateOf<Double?>(null) }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Kicker("Sound · Kira Lab", "dB re ${if (v.normHz >= 1000) "1 kHz" else "500 Hz"}")
                Gap(6.dp)
                HeroLine(dev?.let { d -> tgt?.let { "${d.name} → ${it.name}" } } ?: "Kira Lab", p.text, size = 28.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.padding(top = 10.dp)) { PillButton("Done", onClose, icon = R.drawable.ic_check) }
        }
        Gap(12.dp)
        if (!lab.ready) {
            Txt("Making the Kira targets…", 15.sp, p.sub)
            return@Column
        }
        LabGraph(
            lines = lines,
            yRange = v.yRange,
            relative = relSeries != null,
            balance = v.view == "bal",
            scrubHz = scrubHz,
            onScrub = { scrubHz = it },
            modifier = Modifier.bleed(24.dp),
            inset = 24.dp,
            height = 300.dp,
        )
        Gap(8.dp)
        Box(Modifier.padding(start = 26.dp)) { LabLegend(lines, scrubHz, onClear = { scrubHz = null }, notes = notes) }
        GraphSettings(lab, v, studioShown, ::series)

        LibrarySection(lab, actions, ::series, onOpenStudio = { t ->
            val r = t.recipe ?: return@LibrarySection
            LabStore.updateView { it.copy(model = r.model, taste = r.taste, studioName = t.name) }
            studioOpen = true
        })
        FitSection(lab, v, run, fitShown, dev, tgt, store, actions, ::series)
        StudioSection(lab, v, studioY, studioOpen, { studioOpen = it }, actions)
    }
}

// ---------------------------------------------------------------- graph settings

@Composable
private fun GraphSettings(lab: LabState, v: LabView, studioShown: Boolean, series: (String) -> LabSeries?) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val viewName = when (v.view) {
        "abs" -> "Absolute"
        "lr" -> "Left and right"
        "bal" -> "Balance"
        else -> "Relative"
    }
    val smoothName = if (v.smooth == "raw") "No smoothing" else "1/${v.smooth} oct"
    BenchValueRow("Graph", "$viewName · $smoothName", expanded = open, onExpand = { open = !open }) {
        Txt("Show", 15.sp, p.sub)
        Gap(4.dp)
        val rel = LinkedHashSet<String>()
        v.visible.filter { series(it)?.kind == "target" }.forEach { rel += it }
        if (studioShown) rel += "studio"
        rel += v.target
        ChoiceRow {
            Chip("Absolute", v.view == "abs", onClick = { LabStore.updateView { it.copy(view = "abs") } })
            Chip("L and R", v.view == "lr", onClick = { LabStore.updateView { it.copy(view = "lr") } })
            Chip("Balance", v.view == "bal", onClick = { LabStore.updateView { it.copy(view = "bal") } })
            for (id in rel) {
                val s = series(id) ?: continue
                Chip("Relative to ${s.name}", v.view == id, onClick = { LabStore.updateView { it.copy(view = id) } })
            }
        }
        Gap(10.dp)
        Txt("Smoothing", 15.sp, p.sub)
        Gap(4.dp)
        ChoiceRow {
            for ((k, label) in listOf("raw" to "None", "24" to "1/24", "12" to "1/12", "6" to "1/6", "3" to "1/3")) {
                Chip(label, v.smooth == k, onClick = { LabStore.updateView { it.copy(smooth = k) } })
            }
        }
        Gap(10.dp)
        Txt("Level at", 15.sp, p.sub)
        Gap(4.dp)
        ChoiceRow {
            Chip("500 Hz", v.normHz == 500.0, onClick = { LabStore.updateView { it.copy(normHz = 500.0) } })
            Chip("1 kHz", v.normHz == 1000.0, onClick = { LabStore.updateView { it.copy(normHz = 1000.0) } })
        }
        Gap(10.dp)
        Txt("Scale", 15.sp, p.sub)
        Gap(4.dp)
        ChoiceRow {
            for ((k, label) in listOf("auto" to "Auto", "30" to "30 dB", "50" to "50 dB")) {
                Chip(label, v.yRange == k, onClick = { LabStore.updateView { it.copy(yRange = k) } })
            }
        }
        Gap(8.dp)
        Txt("Tap or drag across the graph to read each curve at one frequency. Shaded above 10 kHz: on a 711 coupler, a guide only.", 13.sp, p.sub)
    }
}

// ---------------------------------------------------------------- library

@Composable
private fun LibrarySection(lab: LabState, actions: AppActions, series: (String) -> LabSeries?, onOpenStudio: (LabTarget) -> Unit) {
    val p = LocalPalette.current
    val v = lab.view
    fun toggle(id: String, on: Boolean) {
        if (!LabStore.show(id, on)) actions.say("Six curves are showing. Hide one to show another.")
    }
    BenchSection(1, "Library", trail = "${v.visible.size} of ${LabStore.SLOTS} shown")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Import files", actions::labImport, icon = R.drawable.ic_import)
        PillButton("Paste", actions::labPaste, icon = R.drawable.ic_paste)
    }
    if (lab.pending.isNotEmpty() || lab.problems.isNotEmpty()) PendingBlock(lab.pending, lab.problems, actions)
    if (lab.measurements.isEmpty() && lab.targets.isEmpty() && lab.pending.isEmpty()) {
        Txt(
            "Nothing of yours yet. Bring in REW exports, squig.link .txt or AutoEq .csv files: ones whose names end in L and R pair up into one measurement.",
            14.sp, p.sub, modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 8.dp),
        )
    }
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }
    for (m in lab.measurements) {
        val shown = m.key in v.visible
        BenchRow(
            label = m.name,
            caption = listOf(if (m.channels.size > 1) m.channels.joinToString(" + ") { it.label } else "One channel", rigName(m.rig)).joinToString(" · "),
            on = shown,
            expanded = openRow == m.key,
            onExpand = { openRow = if (openRow == m.key) null else m.key },
            onToggle = { toggle(m.key, !shown) },
            switchDescription = "Show ${m.name} on the graph",
        ) {
            if (m.note.isNotEmpty()) Txt(m.note, 13.sp, p.sub)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                PillButton("Fit this", {
                    LabStore.updateView { it.copy(device = m.key) }
                    LabStore.show(m.key, true)
                }, icon = R.drawable.ic_tune)
                ArmedButton("Delete") { LabStore.delete(m.key) }
            }
        }
    }
    for (t in lab.targets) {
        val shown = t.key in v.visible
        BenchRow(
            label = t.name,
            caption = listOf("Target", rigName(t.rig)).joinToString(" · "),
            on = shown,
            expanded = openRow == t.key,
            onExpand = { openRow = if (openRow == t.key) null else t.key },
            onToggle = { toggle(t.key, !shown) },
            switchDescription = "Show ${t.name} on the graph",
        ) {
            if (t.note.isNotEmpty()) Txt(t.note, 13.sp, p.sub)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                PillButton("Fit to this", { LabStore.updateView { it.copy(target = t.key) } }, icon = R.drawable.ic_tune)
                if (t.recipe != null) PillButton("Open in Studio", { onOpenStudio(t) })
                PillButton("Share", { actions.labShare(t.name, Lab.targetTxt(t.v)) }, icon = R.drawable.ic_share)
                ArmedButton("Delete") { LabStore.delete(t.key) }
            }
        }
    }
    var kiraOpen by rememberSaveable { mutableStateOf(false) }
    var refsOpen by rememberSaveable { mutableStateOf(false) }
    val kira = LabTargets.BUILTIN.filter { it.group == "kira" }
    val refs = LabTargets.BUILTIN.filter { it.group == "ref" }
    BenchValueRow("Kira targets", "${kira.count { it.id in v.visible }} of ${kira.size} shown", expanded = kiraOpen, onExpand = { kiraOpen = !kiraOpen }) {
        for (b in kira) ToggleLine(b.name, listOfNotNull(b.sub, rigName(b.rig).takeIf { b.sub == null }).joinToString(" · "), b.id in v.visible) { toggle(b.id, b.id !in v.visible) }
    }
    BenchValueRow("References", "${refs.count { it.id in v.visible }} of ${refs.size} shown", expanded = refsOpen, onExpand = { refsOpen = !refsOpen }) {
        for (b in refs) ToggleLine(b.name, listOfNotNull(b.sub, rigName(b.rig).takeIf { b.sub == null }).joinToString(" · "), b.id in v.visible) { toggle(b.id, b.id !in v.visible) }
    }
}

private fun rigName(rig: String) = when (rig) {
    "5128" -> "B&K 5128"
    "GRAS" -> "GRAS over-ear"
    "other" -> "Other rig"
    else -> "711"
}

@Composable
private fun PendingBlock(pending: List<PendingImport>, problems: List<String>, actions: AppActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 6.dp, bottom = 10.dp)) {
        for (item in pending) {
            NameField(item.name, onDone = { n -> LabStore.updatePending(item.key) { it.copy(name = n) } })
            Gap(4.dp)
            ChoiceRow {
                Chip("Measurement", item.kind == "measurement", onClick = { LabStore.updatePending(item.key) { it.copy(kind = "measurement") } })
                Chip("Target", item.kind == "target", onClick = { LabStore.updatePending(item.key) { it.copy(kind = "target") } })
            }
            ChoiceRow {
                for ((k, label) in listOf("711" to "711", "GRAS" to "GRAS", "5128" to "B&K 5128", "other" to "Other")) {
                    Chip(label, item.rig == k, onClick = { LabStore.updatePending(item.key) { it.copy(rig = k) } })
                }
            }
            Txt("${item.channels.joinToString(" + ") { it.label }} · ${item.span} · ${item.files.joinToString()}", 13.sp, p.sub)
            PillButton("Leave out", { LabStore.dropPending(item.key) }, height = 38.dp)
            Gap(10.dp)
        }
        if (problems.isNotEmpty()) {
            Txt("Couldn't read ${problems.joinToString()}. Each line needs a frequency and a level, across a few octaves at least.", 14.sp, p.danger)
            Gap(6.dp)
        }
        Row {
            if (pending.isNotEmpty()) {
                PillButton("Add to the library", {
                    val n = LabStore.commitPending()
                    actions.say(if (n == 1) "Added." else "Added $n.")
                }, icon = R.drawable.ic_check, primary = true)
            }
            PillButton(if (pending.isEmpty()) "Dismiss" else "Cancel", { LabStore.dropPending(null) })
        }
    }
}

/** A name to type, in the page's own type, on a hairline. Saved when you're done with it. */
@Composable
private fun NameField(value: String, onDone: (String) -> Unit) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(60); onDone(text) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = Libron, fontSize = 19.sp, color = p.text),
        cursorBrush = SolidColor(p.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .drawBehind {
                drawLine(p.line, Offset(0f, size.height + 4.dp.toPx()), Offset(size.width, size.height + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
            },
    )
}

/** A line with a switch: a built-in target to show or hide. */
@Composable
private fun ToggleLine(label: String, caption: String?, on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, 16.sp, if (on) p.text else p.sub, maxLines = 1)
            if (!caption.isNullOrEmpty()) Txt(caption, 13.sp, p.sub, maxLines = 1)
        }
        InkSwitch(on, "Show $label", onClick = onToggle)
    }
}

/** A line to choose: a drop beside the one chosen. */
@Composable
private fun ChoiceLine(label: String, caption: String?, chosen: Boolean, onChoose: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClick = onChoose),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp)) {
            if (chosen) androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) { drawCircle(p.text) }
        }
        Column(Modifier.weight(1f)) {
            Txt(label, 16.sp, if (chosen) p.text else p.sub, maxLines = 1)
            if (!caption.isNullOrEmpty()) Txt(caption, 13.sp, p.sub, maxLines = 1)
        }
    }
}

/** Delete, asked twice: the first tap arms it, and it disarms by itself after a moment. */
@Composable
private fun ArmedButton(label: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3500)
            armed = false
        }
    }
    PillButton(if (armed) "Tap again to delete" else label, {
        if (armed) {
            armed = false
            onConfirm()
        } else armed = true
    }, icon = R.drawable.ic_trash)
}

// ---------------------------------------------------------------- the fit

@Composable
private fun FitSection(
    lab: LabState,
    v: LabView,
    run: FitRun,
    fit: LabFit?,
    dev: dev.continuum.fx.lab.LabMeasurement?,
    tgt: LabSeries?,
    store: ProfileStore,
    actions: AppActions,
    series: (String) -> LabSeries?,
) {
    val p = LocalPalette.current
    fun set(transform: (FitSettings) -> FitSettings) = LabStore.updateView { it.copy(fit = transform(it.fit)) }
    BenchSection(2, "Fit an EQ", trail = when {
        run.working -> "fitting"
        fit != null -> "${fit.main.filters.size} filters"
        else -> null
    })
    if (dev == null) {
        Txt("Bring in a measurement first: a fit starts from your earphones as they measure.", 14.sp, p.sub, modifier = Modifier.padding(start = 26.dp, top = 4.dp))
        return
    }
    var devOpen by rememberSaveable { mutableStateOf(false) }
    var tgtOpen by rememberSaveable { mutableStateOf(false) }
    BenchValueRow("Measurement", dev.name, expanded = devOpen, onExpand = { devOpen = !devOpen }) {
        for (m in lab.measurements) ChoiceLine(m.name, rigName(m.rig), m.key == dev.key) {
            LabStore.updateView { it.copy(device = m.key) }
            LabStore.show(m.key, true)
            devOpen = false
        }
    }
    BenchValueRow("Target", tgt?.name ?: "None", expanded = tgtOpen, onExpand = { tgtOpen = !tgtOpen }) {
        val chooseT: (String) -> Unit = { id ->
            LabStore.updateView { it.copy(target = id) }
            tgtOpen = false
        }
        ChoiceLine("Studio target", "As Target Studio has it now", v.target == "studio") { chooseT("studio") }
        for (t in lab.targets) ChoiceLine(t.name, rigName(t.rig), v.target == t.key) { chooseT(t.key) }
        for (b in LabTargets.BUILTIN) ChoiceLine(b.name, listOfNotNull(b.sub, rigName(b.rig).takeIf { b.sub == null }).joinToString(" · "), v.target == b.id) { chooseT(b.id) }
    }
    Gap(6.dp)
    Column(Modifier.padding(start = 26.dp)) {
        LabSlider("Most filters", v.fit.maxFilters.toDouble(), 3.0, 12.0, 1.0, "${v.fit.maxFilters}") { n -> set { it.copy(maxFilters = n.roundToInt()) } }
        LabSlider("Largest boost", v.fit.maxBoost, 0.0, 12.0, 0.5, Fmt.db(v.fit.maxBoost)) { b -> set { it.copy(maxBoost = b) } }
    }
    var rateOpen by rememberSaveable { mutableStateOf(false) }
    BenchValueRow("Sample rate and range", "${khz(v.fit.fs)} · to ${khz(v.fit.fmax)}", expanded = rateOpen, onExpand = { rateOpen = !rateOpen }) {
        Txt("Sample rate", 15.sp, p.sub)
        ChoiceRow {
            for (fs in listOf(44100.0, 48000.0, 96000.0)) Chip(khz(fs), v.fit.fs == fs, onClick = { set { it.copy(fs = fs) } })
        }
        Gap(8.dp)
        Txt("Fit up to", 15.sp, p.sub)
        ChoiceRow {
            for (f in listOf(10000.0, 12000.0, 16000.0, 20000.0)) Chip(khz(f), v.fit.fmax == f, onClick = { set { it.copy(fmax = f) } })
        }
    }
    var howOpen by rememberSaveable { mutableStateOf(false) }
    MoreSection(howOpen, { howOpen = !howOpen }, label = "How to fit") {
        BenchRow("Fit what the ear resolves", "Smooths the error to your hearing's frequency resolution (ERB), not every ripple.", v.fit.perceptual, onToggle = { set { it.copy(perceptual = !it.perceptual) } })
        BenchRow("Peaks matter more than dips", "Narrow peaks are easier to hear, so peaks above the target count almost twice as much.", v.fit.asym, onToggle = { set { it.copy(asym = !it.asym) } })
        BenchRow("Treat treble as uncertain", "Above 6 kHz, coupler resonances move with insertion: a broader fit, and no sharp filters there.", v.fit.robustHF, onToggle = { set { it.copy(robustHF = !it.robustHF) } })
        BenchRow("Trust where L and R agree", "Where your two earpieces disagree, the fit leans on the average less. Needs L and R files.", v.fit.useSpread, onToggle = { set { it.copy(useSpread = !it.useSpread) } })
        BenchRow("Match left and right", "When the sides differ by over 2 dB below 10 kHz, or 0.75 dB overall: a level and up to 3 filters a side, on top of the shared EQ.", v.fit.matchLR, onToggle = { set { it.copy(matchLR = !it.matchLR) } })
    }

    // What needs you: a target for another rig, or sides too far apart for EQ to be the fix.
    val warns = ArrayList<String>()
    val shownRun = if (run.fit != null) run else null
    if (shownRun != null && shownRun.devKey.isNotEmpty()) {
        val dr = LabFitter.rigFamily(shownRun.devRig)
        val tr = LabFitter.rigFamily(shownRun.tgtRig)
        if (dr != tr) warns += "This target is for a ${if (tr == "5128") "B&K 5128" else "711-type"} rig, but the measurement is from a ${if (dr == "5128") "B&K 5128" else if (dr == "other") "different" else "711-type"} rig. Pick the matching version of the target."
        if (shownRun.tgtOverEar5128 && dr == "5128") warns += "This is the over-ear 5128 version. For IEMs, pick the B&K 5128 · IEM version: on IEMs the 5128 reads the bass about 4 dB lower against the upper mids than a 711 does."
        if (v.fit.useSpread && shownRun.nch < 2) warns += "This measurement has one channel, so trusting where L and R agree does nothing. Bring in L and R files to use it."
    }
    val lr = fit?.lr
    if (lr != null && lr.before.max >= 3) {
        val side = if (lr.before.signed < 0) "left" else "right"
        warns += "Left and right differ by up to ${one(lr.before.max)} dB (the $side side is quieter around ${LabIds.hz(lr.before.hz)}). That's more than units usually vary: check the quieter side's nozzle for wax or debris and measure again before leaning on EQ for it."
    }
    if (warns.isNotEmpty()) {
        Column(Modifier.padding(start = 26.dp, top = 8.dp)) {
            for (w in warns) {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Note("check", p.danger, Modifier.padding(top = 3.dp, end = 10.dp))
                    Txt(w, 14.sp, p.text)
                }
            }
        }
    }
    if (fit == null) {
        Txt(if (run.working) "Fitting…" else "Choose a target to fit to.", 14.sp, p.sub, modifier = Modifier.padding(start = 26.dp, top = 10.dp))
        return
    }

    // The result: how close it gets, the filters, and the error before and after.
    val m = fit.main
    BenchValueRow("Error, 20 Hz–10 kHz", "${one(m.before.rms)} → ${Lab.fixed(m.after.rms, 2)} dB rms")
    BenchValueRow("Largest peak over the target", "${one(m.before.maxPeak)} → ${one(m.after.maxPeak)} dB")
    BenchValueRow(
        "Filters",
        "${m.filters.size} of ${v.fit.maxFilters}" + (fit.chans?.let { c -> " + ${c.joinToString("/") { "${it.filters.size}" }} a side" } ?: ""),
    )
    if (lr != null) {
        BenchValueRow("Left and right apart", if (lr.applied && lr.after != null) "${one(lr.before.max)} → ${one(lr.after.max)} dB" else "${one(lr.before.max)} dB · fine as it is")
    }
    BenchValueRow("Preamp", Fmt.db(fit.preamp))
    Gap(4.dp)
    Note(if (run.working) "fitting…" else "${run.ms} ms", p.quiet, Modifier.padding(start = 26.dp))
    Gap(10.dp)
    ResidualGraph(m.devBefore, m.devAfter, Modifier.bleed(24.dp), inset = 24.dp)
    Txt("The error from the target: grey before, ink after. Shaded: within 1 dB.", 13.sp, p.sub, modifier = Modifier.padding(start = 26.dp))
    Gap(10.dp)
    FilterTable(m.filters, fit.chans)
    Gap(8.dp)
    val name = "${run.devName.ifEmpty { dev.name }} → ${run.tgtName.ifEmpty { tgt?.name ?: "target" }}"
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Use as an EQ", {
            store.add(profileOf(name, m.filters, fit.preamp, fit.chans))
            actions.say("$name is your EQ now." + if (fit.chans != null) " Left and right are matched too." else "")
        }, icon = R.drawable.ic_check, primary = true)
        PillButton("Copy", {
            actions.labCopy(fit.apoText(), if (fit.chans != null) "Copied, with left and right sections, for EqualizerAPO or Peace." else "Copied for EqualizerAPO or Peace.")
        }, icon = R.drawable.ic_copy)
        PillButton("Share", { actions.labShare(name, fit.apoText()) }, icon = R.drawable.ic_share)
        PillButton("Save .txt", { actions.labSave("$name PEQ.txt", fit.apoText()) })
        PillButton("GraphicEQ", {
            actions.labCopy(
                Lab.graphicEqText(m.eq, m.preamp),
                if (fit.chans != null) "GraphicEQ copied: the shared EQ only. The left and right matching needs the EqualizerAPO text." else "GraphicEQ copied, for Wavelet or EqualizerAPO.",
            )
        }, icon = R.drawable.ic_copy)
        PillButton("Keep", {
            LabStore.addEq(
                LabSavedEq(
                    LabIds.slug(name), name, run.devKey, run.tgtId, m.fs, m.filters, fit.preamp, fit.chans, v.fit,
                    Lab.jsRound(m.before.rms * 100) / 100, Lab.jsRound(m.after.rms * 100) / 100, System.currentTimeMillis(),
                ),
            )
            actions.say("Kept in the Lab.")
        })
    }
    if (lab.eqs.isNotEmpty()) {
        var keptOpen by rememberSaveable { mutableStateOf(false) }
        BenchValueRow("Kept fits", "${lab.eqs.size}", expanded = keptOpen, onExpand = { keptOpen = !keptOpen }) {
            for (e in lab.eqs.sortedByDescending { it.createdAt }) {
                Txt(e.name, 16.sp, p.text, maxLines = 2)
                Txt("${e.filters.size} filters${e.channels?.let { " + sides" } ?: ""} · preamp ${Fmt.db(e.preamp)} · error ${one(e.errBefore)} → ${Lab.fixed(e.errAfter, 2)} dB", 13.sp, p.sub)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    PillButton("Use as an EQ", {
                        store.add(profileOf(e.name, e.filters, e.preamp, e.channels))
                        actions.say("${e.name} is your EQ now.")
                    }, icon = R.drawable.ic_check)
                    PillButton("Copy", { actions.labCopy(e.apoText(), "Copied for EqualizerAPO or Peace.") }, icon = R.drawable.ic_copy)
                    PillButton("Fit again", {
                        LabStore.updateView { it.copy(fit = e.settings, device = e.device.ifEmpty { it.device }, target = e.target.ifEmpty { it.target }) }
                        keptOpen = false
                    })
                    ArmedButton("Delete") { LabStore.delete("e:${e.id}") }
                }
                Gap(8.dp)
            }
        }
    }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    MoreSection(aboutOpen, { aboutOpen = !aboutOpen }, label = "How this fit works") {
        for (line in HOW) {
            Txt(line, 14.sp, p.sub, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

private fun khz(hz: Double) = if (hz == 44100.0) "44.1 kHz" else "${(hz / 1000).roundToInt()} kHz"

private val HOW = listOf(
    "Both curves are smoothed to the ear's frequency resolution (equivalent rectangular bandwidth, about 1/6 octave in the mids), so the fit spends filters on what you can hear.",
    "The level is solved as one free volume offset alongside the filters. It's only your volume, so it never goes in the EQ, and a target with a deliberate dip at 500 Hz (like Live v2.1) can't skew the alignment.",
    "Errors are weighted: full weight from 30 Hz to 8 kHz, easing to 30% above 12 kHz where 711 couplers have no tolerance, and less where your left and right disagree.",
    "Errors over 3 dB count less and less, so a roll-off or a coupler notch no EQ can fix doesn't pull the other filters off course.",
    "Peaks above the target count 1.8 times as much as dips below it: narrow peaks are easier to hear than narrow dips, and it keeps the EQ from boosting into deep notches.",
    "Filters go in one at a time, where they take away the most weighted error; after each, all of them are tuned together (Levenberg–Marquardt).",
    "It keeps the fewest filters within 0.05 dB of the best, drops any under 0.25 dB, and never lets the total boost pass your limit anywhere.",
    "Filters are RBJ biquads at your sample rate. The preamp matches the largest boost, so the EQ can't clip.",
    "Matching left and right: each side is fitted to the pair's average with a level and up to 3 filters, so they meet in the middle and neither takes a big boost; then the shared EQ is fitted to that average. Above 10 kHz the sides' differences are mostly insertion depth, so they're left alone.",
)

@Composable
private fun FilterTable(filters: List<LabFilter>, chans: List<ChannelFix>?) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 26.dp)) {
        filters.forEachIndexed { i, f -> FilterRow(i + 1, f) }
        var n = filters.size
        chans?.forEach { c ->
            Row(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
                Note("${if (c.label == "L") "left" else "right"} only · level ${dbShort(c.level)} dB", p.sub)
            }
            if (c.filters.isEmpty()) Txt("No filters, the level only.", 13.sp, p.sub)
            val start = n
            c.filters.forEachIndexed { i, f -> FilterRow(start + i + 1, f) }
            n += c.filters.size
        }
    }
}

@Composable
private fun FilterRow(n: Int, f: LabFilter) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp)) { Note("%02d".format(n), p.quiet) }
        Txt(when (f.type) { "LSC" -> "Low shelf"; "HSC" -> "High shelf"; else -> "Peak" }, 14.sp, p.sub, maxLines = 1, modifier = Modifier.weight(1f))
        Txt(Fmt.freq(f.fc), 14.sp, p.text, maxLines = 1, modifier = Modifier.width(78.dp))
        Txt(dbShort(f.gain), 14.sp, p.text, maxLines = 1, modifier = Modifier.width(48.dp))
        Txt("Q ${Lab.fixed(f.q, 2)}", 14.sp, p.sub, maxLines = 1, modifier = Modifier.width(58.dp))
    }
}

@Composable
private fun LabSlider(label: String, value: Double, min: Double, max: Double, step: Double, text: String, onChange: (Double) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, 15.sp, p.sub, maxLines = 1, modifier = Modifier.weight(1f))
            Txt(text, 15.sp, p.text, maxLines = 1)
        }
        InkSlider(
            value = ((value - min) / (max - min)).toFloat(),
            onChange = { t ->
                val next = snap(min + t * (max - min), min, max, step)
                if (next != value) onChange(next)
            },
            label = text,
        )
    }
}

// ---------------------------------------------------------------- target studio

private class Spec(val key: String, val label: String, val min: Double, val max: Double, val step: Double, val fmt: (Double) -> String)

private val MODEL_SPECS = listOf(
    Spec("r", "Seat distance", 4.0, 40.0, 1.0) { "${it.roundToInt()} m" },
    Spec("playPhon", "Your listening level", 60.0, 85.0, 1.0) { "${it.roundToInt()} phon" },
    Spec("livePhon", "Live level", 75.0, 100.0, 1.0) { "${it.roundToInt()} phon" },
    Spec("projPeak", "Upper-mid projection", 0.0, 6.0, 0.5) { "${one(it)} dB" },
    Spec("Tmid", "Hall reverb time", 1.2, 3.0, 0.1) { "${one(it)} s" },
    Spec("BR", "Bass ratio", 0.9, 1.5, 0.05) { Lab.fixed(it, 2) },
)

private val TASTE_SPECS = listOf(
    Spec("bassG", "Bass shelf", -6.0, 8.0, 0.5) { Fmt.db(it) },
    Spec("bassF", "Bass shelf corner", 40.0, 250.0, 5.0) { "${it.roundToInt()} Hz" },
    Spec("bodyG", "Body, 250–400 Hz", -3.0, 3.0, 0.5) { Fmt.db(it) },
    Spec("earG", "Ear gain", -6.0, 4.0, 0.5) { Fmt.db(it) },
    Spec("earF", "Ear gain centre", 2000.0, 4000.0, 50.0) { LabIds.hz(it) },
    Spec("presG", "Presence, 4–6 kHz", -6.0, 4.0, 0.5) { Fmt.db(it) },
    Spec("airG", "Air shelf", -4.0, 6.0, 0.5) { Fmt.db(it) },
    Spec("airF", "Air shelf corner", 7000.0, 14000.0, 250.0) { LabIds.hz(it) },
    Spec("tilt", "Tilt around 1 kHz", -1.0, 1.0, 0.1) { "${dbShort(it)} dB/oct" },
    Spec("sep", "Separation, your v2.1 carve", 0.0, 2.0, 0.25) { "×${Lab.fixed(it, 2)}" },
)

private fun StudioModel.get(k: String) = when (k) {
    "r" -> r; "playPhon" -> playPhon; "livePhon" -> livePhon; "projPeak" -> projPeak; "Tmid" -> Tmid; else -> BR
}

private fun StudioModel.withValue(k: String, x: Double) = when (k) {
    "r" -> copy(r = x); "playPhon" -> copy(playPhon = x); "livePhon" -> copy(livePhon = x); "projPeak" -> copy(projPeak = x); "Tmid" -> copy(Tmid = x); else -> copy(BR = x)
}

private fun StudioTaste.get(k: String) = when (k) {
    "bassG" -> bassG; "bassF" -> bassF; "bodyG" -> bodyG; "earG" -> earG; "earF" -> earF
    "presG" -> presG; "airG" -> airG; "airF" -> airF; "tilt" -> tilt; else -> sep
}

private fun StudioTaste.withValue(k: String, x: Double) = when (k) {
    "bassG" -> copy(bassG = x); "bassF" -> copy(bassF = x); "bodyG" -> copy(bodyG = x); "earG" -> copy(earG = x); "earF" -> copy(earF = x)
    "presG" -> copy(presG = x); "airG" -> copy(airG = x); "airF" -> copy(airF = x); "tilt" -> copy(tilt = x); else -> copy(sep = x)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioSection(lab: LabState, v: LabView, studioY: DoubleArray?, open: Boolean, onOpen: (Boolean) -> Unit, actions: AppActions) {
    val p = LocalPalette.current
    BenchSection(3, "Target Studio", trail = if (open) "on the graph" else null)
    BenchRow(
        "Show it on the graph",
        "Start from Kira Live v2.1 and change the hall, your listening level, or your taste. Separation scales your v2.1 carve (0 is plain Live v2).",
        open,
        onToggle = { onOpen(!open) },
    )
    ChoiceRow(Modifier.padding(start = 26.dp)) {
        for ((k, label) in listOf("711" to "711 · GRAS", "5128iem" to "5128 IEM", "5128" to "5128 over-ear")) {
            Chip(label, v.model.rig == k, onClick = { LabStore.updateView { it.copy(model = it.model.copy(rig = k)) } })
        }
    }
    val base = lab.builtins[Studio.baseId(v.model.rig)]
    if (studioY != null && base != null) {
        val (kv, peak) = Lab.keyValues(studioY)
        val (kb, peakB) = Lab.keyValues(base)
        fun diff(a: Double, b: Double) = (a - b).let { d -> if (abs(d) >= 0.05) " (${dbShort(d)})" else "" }
        FlowRow(Modifier.fillMaxWidth().padding(start = 26.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kv.forEachIndexed { k, (hz, db) -> Note("${LabIds.hz(hz)} ${dbShort(db)}${diff(db, kb[k].second)}", p.sub) }
            Note("peak ${dbShort(peak.second)} at ${LabIds.hz(peak.first)}${diff(peak.second, peakB.second)}", p.text)
        }
    }
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var tasteOpen by rememberSaveable { mutableStateOf(false) }
    BenchValueRow("Live model", "${v.model.r.roundToInt()} m · ${v.model.playPhon.roundToInt()} phon", expanded = modelOpen, onExpand = { modelOpen = !modelOpen }) {
        for (s in MODEL_SPECS) {
            val x = v.model.get(s.key)
            LabSlider(s.label, x, s.min, s.max, s.step, s.fmt(x)) { n -> LabStore.updateView { it.copy(model = it.model.withValue(s.key, n)) } }
        }
        PillButton("Reset", { LabStore.updateView { it.copy(model = StudioModel(rig = it.model.rig)) } }, height = 38.dp)
    }
    val tasteMoved = v.taste != StudioTaste()
    BenchValueRow("Taste", if (tasteMoved) "changed · carve ×${Lab.fixed(v.taste.sep, 2)}" else "as Live v2.1", expanded = tasteOpen, onExpand = { tasteOpen = !tasteOpen }) {
        for (s in TASTE_SPECS) {
            val x = v.taste.get(s.key)
            LabSlider(s.label, x, s.min, s.max, s.step, s.fmt(x)) { n -> LabStore.updateView { it.copy(taste = it.taste.withValue(s.key, n)) } }
        }
        PillButton("Reset", { LabStore.updateView { it.copy(taste = StudioTaste()) } }, height = 38.dp)
    }
    Gap(8.dp)
    Column(Modifier.padding(start = 26.dp)) {
        Txt("Name", 15.sp, p.sub)
        NameField(v.studioName, onDone = { n -> LabStore.updateView { it.copy(studioName = n) } })
    }
    val y = studioY ?: return
    val name = v.studioName.trim().ifEmpty { "Kira Live v2.1 custom" }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Keep", {
            val t = LabTarget(
                LabIds.slug(name), name, Studio.rigTag(v.model.rig), "Made in Target Studio from Kira Live v2.1", System.currentTimeMillis(),
                LabIds.r2(y), LabRecipe(v.model, v.taste),
            )
            LabStore.addTarget(t)
            LabStore.show(t.key, true)
            actions.say("Kept \"$name\" with your targets.")
        }, icon = R.drawable.ic_check, primary = true)
        PillButton("Fit to it", { LabStore.updateView { it.copy(target = "studio") } }, icon = R.drawable.ic_tune)
        PillButton("Share", { actions.labShare(name, Lab.targetTxt(y)) }, icon = R.drawable.ic_share)
        PillButton("Save .txt", { actions.labSave("$name.txt", Lab.targetTxt(y)) })
        PillButton("Save .csv", { actions.labSave("$name.csv", Lab.targetCsv(y)) })
        PillButton("Copy", { actions.labCopy(Lab.targetTxt(y), "Target copied as tab-separated text.") }, icon = R.drawable.ic_copy)
    }
}
