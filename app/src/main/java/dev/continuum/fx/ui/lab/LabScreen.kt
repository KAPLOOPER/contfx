package dev.continuum.fx.ui.lab

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import dev.continuum.fx.lab.LabView
import dev.continuum.fx.lab.PendingImport
import dev.continuum.fx.lab.Studio
import dev.continuum.fx.lab.StudioModel
import dev.continuum.fx.lab.StudioTaste
import dev.continuum.fx.ui.AppActions
import dev.continuum.fx.ui.Fmt
import dev.continuum.fx.ui.Gap
import dev.continuum.fx.ui.HeroLine
import dev.continuum.fx.ui.INK_RAIL
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
import dev.continuum.fx.ui.components.ActionRow
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
 * The Lab, in Continuum's ink: the graph across the page, then the ledger under it. Your library
 * (the measurements and targets you bring in: the app carries none of its own) is what's on the
 * graph; the fit makes an EQ from a measurement to a target, matching left and right first;
 * Target Studio reshapes one of your targets. A fit becomes an EQ like any other with one tap.
 */

/** A curve the Lab knows: one of yours, or the studio's. */
internal class LabSeries(
    val id: String,
    val label: String,
    val name: String,
    val kind: String,
    val rig: String,
    val y: DoubleArray,
    val device: Device? = null,
    val recipe: LabRecipe? = null,
)

/** The target Target Studio starts from: the one chosen, or your first. */
private fun studioBase(lab: LabState): LabTarget? =
    lab.targets.firstOrNull { it.key == lab.view.studioBase } ?: lab.targets.firstOrNull()

private fun seriesOf(lab: LabState, id: String, studio: DoubleArray?): LabSeries? = when {
    id == "studio" -> studio?.let { y -> studioBase(lab)?.let { b -> LabSeries(id, "Studio target", "Studio target", "target", b.rig, y) } }
    id.startsWith("m:") -> lab.measurements.firstOrNull { it.key == id }?.let { m -> LabSeries(id, m.name, m.name, "measurement", m.rig, m.device.y, m.device) }
    id.startsWith("t:") -> lab.targets.firstOrNull { it.key == id }?.let { t -> LabSeries(id, t.name, t.name, "target", t.rig, t.v, recipe = t.recipe) }
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

    // Target Studio's target, made afresh from one of yours as its sliders move.
    val base = studioBase(lab)
    val studioY by produceState<DoubleArray?>(null, base, v.model, v.taste) {
        val b = base
        value = if (b == null) null else withContext(Dispatchers.Default) { Studio.target(b.v, v.model, v.taste) }
    }
    fun series(id: String) = seriesOf(lab, id, studioY)

    // What's fitted: the chosen measurement (or the first), to the chosen target (or the first).
    val dev = lab.measurements.firstOrNull { it.key == v.device } ?: lab.measurements.firstOrNull()
    val tgt = series(v.target) ?: lab.targets.firstOrNull()?.let { series(it.key) }
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
        value = FitRun(
            fit, false, SystemClock.elapsedRealtime() - t0, dev.key, dev.name, dev.rig, dev.channels.size,
            tgt.id, tgt.name, tgt.rig,
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
    // The studio's target changes as its sliders move, so it's smoothed each time, not kept.
    fun shown(s: LabSeries): DoubleArray = Lab.norm(smoothed(s.y, keep = s.id != "studio"), v.normHz)

    var studioOpen by rememberSaveable { mutableStateOf(false) }
    val studioShown = studioOpen && studioY != null
    val relId = v.view.takeIf { it != "abs" && it != "lr" && it != "bal" }
    val relSeries = relId?.let { series(it) }
    val notes = ArrayList<String>()
    val lines = remember(lab, studioY, fitShown, v, studioShown, base) {
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
                val baseId = base?.key
                if (baseId != null && baseId !in v.visible) series(baseId)?.let { out += LabLine(baseId, "${it.label} (start)", shown(it), Ink.BASE) }
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
        // A page of its own, laid out as the keyboard's settings are: the title above is the page
        // it hangs from (the pivot, on Sound); the kicker names this page; the line says what it's
        // working on; and where a pivot page has its switch, at the line's end, is the way back.
        // "Done" ends on the margin (a button keeps 18dp of room after it, which the margin
        // already provides).
        Kicker("Lab")
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroLine(
                when {
                    dev != null && tgt != null -> "${dev.name} → ${tgt.name}"
                    dev != null -> "No target yet."
                    lab.targets.isNotEmpty() -> "No measurement yet."
                    else -> "Nothing in it yet."
                },
                p.text, Modifier.weight(1f), size = 28.sp,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.offset(x = 18.dp)) { PillButton("Done", onClose, icon = R.drawable.ic_check) }
        }
        Gap(12.dp)
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
            // How the curves are levelled only means something once there's a curve to level.
            reference = if (lines.isNotEmpty() && v.view != "bal") "0 dB at ${if (v.normHz >= 1000) "1 kHz" else "500 Hz"}" else null,
        )
        Gap(8.dp)
        Box(Modifier.padding(start = INK_RAIL)) { LabLegend(lines, scrubHz, onClear = { scrubHz = null }, notes = notes) }
        GraphSettings(lab, v, studioShown, ::series)

        LibrarySection(lab, actions, ::series, onOpenStudio = { t ->
            val r = t.recipe
            val from = r?.base?.takeIf { b -> lab.targets.any { it.key == b } }
            // Its own start if that's still here; if not, it starts from itself, as it is.
            LabStore.updateView {
                if (r != null && from != null) it.copy(studioBase = from, model = r.model, taste = r.taste, studioName = t.name)
                else it.copy(studioBase = t.key, model = StudioModel(), taste = StudioTaste(), studioName = t.name)
            }
            studioOpen = true
        })
        FitSection(lab, v, run, fitShown, dev, tgt, store, actions, ::series)
        StudioSection(lab, v, base, studioY, studioOpen, { studioOpen = it }, actions)
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
    // How many of the graph's slots are showing: said once there's something in the library to show.
    val empty = lab.measurements.isEmpty() && lab.targets.isEmpty()
    BenchSection(1, "Library", trail = if (empty) null else "${v.visible.size} of ${LabStore.SLOTS} shown")
    ActionRow(Modifier.padding(start = INK_RAIL)) {
        PillButton("Import files", actions::labImport, icon = R.drawable.ic_import)
        PillButton("Paste", actions::labPaste, icon = R.drawable.ic_paste)
    }
    if (lab.pending.isNotEmpty() || lab.problems.isNotEmpty()) PendingBlock(lab.pending, lab.problems, actions)
    if (lab.measurements.isEmpty() && lab.targets.isEmpty() && lab.pending.isEmpty()) {
        Txt(
            "Bring in your measurements and targets: REW exports, squig.link .txt or AutoEq .csv files. Ones whose names end in L and R pair up into one measurement.",
            14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 4.dp, bottom = 8.dp),
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
            ActionRow {
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
            ActionRow {
                PillButton("Fit to this", { LabStore.updateView { it.copy(target = t.key) } }, icon = R.drawable.ic_tune)
                PillButton(if (t.recipe != null) "Open in Studio" else "Shape in Studio", { onOpenStudio(t) }, icon = R.drawable.ic_tune)
                PillButton("Share", { actions.labShare(t.name, Lab.targetTxt(t.v)) }, icon = R.drawable.ic_share)
                ArmedButton("Delete") { LabStore.delete(t.key) }
            }
        }
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
    Column(Modifier.fillMaxWidth().padding(start = INK_RAIL, top = 6.dp, bottom = 10.dp)) {
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
            PillButton("Leave out", { LabStore.dropPending(item.key) }, icon = R.drawable.ic_close)
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
                }, icon = R.drawable.ic_plus, primary = true)
            }
            PillButton(if (pending.isEmpty()) "Dismiss" else "Cancel", { LabStore.dropPending(null) }, icon = R.drawable.ic_close)
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
        // The same air under it as the Library's empty note, so the three empty sections step evenly.
        Txt("Bring in a measurement first: a fit starts from your earphones as they measure.", 14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 4.dp, bottom = 8.dp))
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
        if (lab.targets.isEmpty()) Txt("Bring in a target with Import files or Paste, and it's here to fit to.", 14.sp, p.sub)
        for (t in lab.targets) ChoiceLine(t.name, rigName(t.rig), tgt?.id == t.key) { chooseT(t.key) }
        if (lab.targets.isNotEmpty()) ChoiceLine("Studio target", "As Target Studio has it now", tgt?.id == "studio") { chooseT("studio") }
    }
    Gap(6.dp)
    Column(Modifier.padding(start = INK_RAIL)) {
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
        if (v.fit.useSpread && shownRun.nch < 2) warns += "This measurement has one channel, so trusting where L and R agree does nothing. Bring in L and R files to use it."
    }
    val lr = fit?.lr
    if (lr != null && lr.before.max >= 3) {
        val side = if (lr.before.signed < 0) "left" else "right"
        warns += "Left and right differ by up to ${one(lr.before.max)} dB (the $side side is quieter around ${LabIds.hz(lr.before.hz)}). That's more than units usually vary: check the quieter side's nozzle for wax or debris and measure again before leaning on EQ for it."
    }
    if (warns.isNotEmpty()) {
        Column(Modifier.padding(start = INK_RAIL, top = 8.dp)) {
            for (w in warns) {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Note("check", p.danger, Modifier.padding(top = 3.dp, end = 10.dp))
                    Txt(w, 14.sp, p.text)
                }
            }
        }
    }
    if (fit == null) {
        Txt(
            when {
                run.working -> "Fitting…"
                lab.targets.isEmpty() -> "Bring in a target to fit to: your own, as a REW, squig.link or AutoEq file."
                else -> "Choose a target to fit to."
            },
            14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 10.dp),
        )
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
    Note(if (run.working) "fitting…" else "fitted in ${run.ms} ms", p.quiet, Modifier.padding(start = INK_RAIL))
    Gap(10.dp)
    ResidualGraph(m.devBefore, m.devAfter, Modifier.bleed(24.dp), inset = 24.dp)
    Txt("The error from the target: grey before, ink after. Shaded: within 1 dB.", 13.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL))
    Gap(10.dp)
    FilterTable(m.filters, fit.chans)
    Gap(8.dp)
    val name = "${run.devName.ifEmpty { dev.name }} → ${run.tgtName.ifEmpty { tgt?.name ?: "target" }}"
    ActionRow(Modifier.padding(start = INK_RAIL)) {
        PillButton("Use as an EQ", {
            store.add(profileOf(name, m.filters, fit.preamp, fit.chans))
            store.setEqEnabled(true)
            actions.say("$name is your EQ now." + if (fit.chans != null) " Left and right are matched too." else "")
        }, icon = R.drawable.ic_tune, primary = true)
        PillButton("Copy", {
            actions.labCopy(fit.apoText(), if (fit.chans != null) "Copied, with left and right sections, for EqualizerAPO or Peace." else "Copied for EqualizerAPO or Peace.")
        }, icon = R.drawable.ic_copy)
        PillButton("Share", { actions.labShare(name, fit.apoText()) }, icon = R.drawable.ic_share)
        PillButton("Save .txt", { actions.labSave("$name PEQ.txt", fit.apoText()) }, icon = R.drawable.ic_import)
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
        }, icon = R.drawable.ic_plus)
    }
    if (lab.eqs.isNotEmpty()) {
        var keptOpen by rememberSaveable { mutableStateOf(false) }
        BenchValueRow("Kept fits", "${lab.eqs.size}", expanded = keptOpen, onExpand = { keptOpen = !keptOpen }) {
            for (e in lab.eqs.sortedByDescending { it.createdAt }) {
                Txt(e.name, 16.sp, p.text, maxLines = 2)
                Txt("${e.filters.size} filters${e.channels?.let { " + sides" } ?: ""} · preamp ${Fmt.db(e.preamp)} · error ${one(e.errBefore)} → ${Lab.fixed(e.errAfter, 2)} dB", 13.sp, p.sub)
                ActionRow {
                    PillButton("Use as an EQ", {
                        store.add(profileOf(e.name, e.filters, e.preamp, e.channels))
                        store.setEqEnabled(true)
                        actions.say("${e.name} is your EQ now.")
                    }, icon = R.drawable.ic_tune)
                    PillButton("Copy", { actions.labCopy(e.apoText(), "Copied for EqualizerAPO or Peace.") }, icon = R.drawable.ic_copy)
                    PillButton("Fit again", {
                        LabStore.updateView { it.copy(fit = e.settings, device = e.device.ifEmpty { it.device }, target = e.target.ifEmpty { it.target }) }
                        keptOpen = false
                    }, icon = R.drawable.ic_refresh)
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
    "The level is solved as one free volume offset alongside the filters. It's only your volume, so it never goes in the EQ, and a target with a deliberate dip at 500 Hz can't skew the alignment.",
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
    Column(Modifier.fillMaxWidth().padding(start = INK_RAIL)) {
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
        Box(Modifier.width(INK_RAIL)) { Note("%02d".format(n), p.quiet) }
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
    Spec("sep", "Separation carve", -1.0, 2.0, 0.25) { if (it == 0.0) "as it is" else "${if (it > 0) "+" else "−"}${Lab.fixed(abs(it), 2)}" },
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
private fun StudioSection(
    lab: LabState,
    v: LabView,
    base: LabTarget?,
    studioY: DoubleArray?,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    actions: AppActions,
) {
    val p = LocalPalette.current
    BenchSection(3, "Target Studio", trail = if (open && base != null) "on the graph" else null)
    if (base == null) {
        Txt(
            "Target Studio reshapes one of your targets: the hall, your listening level, your taste. Bring in a target first.",
            14.sp, p.sub, modifier = Modifier.padding(start = INK_RAIL, top = 4.dp, bottom = 8.dp),
        )
        return
    }
    BenchRow(
        "Show it on the graph",
        "Change the hall, your listening level or your taste, starting from one of your targets. Separation adds the separation carve, or takes it away below 0.",
        open,
        onToggle = { onOpen(!open) },
    )
    var fromOpen by rememberSaveable { mutableStateOf(false) }
    BenchValueRow("Starts from", base.name, expanded = fromOpen, onExpand = { fromOpen = !fromOpen }) {
        for (t in lab.targets) ChoiceLine(t.name, rigName(t.rig), t.key == base.key) {
            LabStore.updateView { it.copy(studioBase = t.key) }
            fromOpen = false
        }
    }
    if (studioY != null) {
        val (kv, peak) = Lab.keyValues(studioY)
        val (kb, peakB) = Lab.keyValues(Lab.norm(base.v, 500.0))
        fun diff(a: Double, b: Double) = (a - b).let { d -> if (abs(d) >= 0.05) " (${dbShort(d)})" else "" }
        FlowRow(Modifier.fillMaxWidth().padding(start = INK_RAIL, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kv.forEachIndexed { k, (hz, db) -> Note("${LabIds.hz(hz)} ${dbShort(db)}${diff(db, kb[k].second)}", p.sub) }
            Note("peak ${dbShort(peak.second)} at ${LabIds.hz(peak.first)}${diff(peak.second, peakB.second)}", p.text)
        }
    }
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var tasteOpen by rememberSaveable { mutableStateOf(false) }
    val modelMoved = v.model != StudioModel()
    BenchValueRow("Live model", if (modelMoved) "${v.model.r.roundToInt()} m · ${v.model.playPhon.roundToInt()} phon" else "as it is", expanded = modelOpen, onExpand = { modelOpen = !modelOpen }) {
        Txt("Changes from a 20 m seat in a 2 s hall, live at 90 phon and played at 70: the target's own hall stays in it.", 13.sp, p.sub)
        for (s in MODEL_SPECS) {
            val x = v.model.get(s.key)
            LabSlider(s.label, x, s.min, s.max, s.step, s.fmt(x)) { n -> LabStore.updateView { it.copy(model = it.model.withValue(s.key, n)) } }
        }
        PillButton("Reset", { LabStore.updateView { it.copy(model = StudioModel()) } }, icon = R.drawable.ic_undo)
    }
    val tasteMoved = v.taste != StudioTaste()
    BenchValueRow("Taste", if (tasteMoved) "changed" else "as it is", expanded = tasteOpen, onExpand = { tasteOpen = !tasteOpen }) {
        for (s in TASTE_SPECS) {
            val x = v.taste.get(s.key)
            LabSlider(s.label, x, s.min, s.max, s.step, s.fmt(x)) { n -> LabStore.updateView { it.copy(taste = it.taste.withValue(s.key, n)) } }
        }
        PillButton("Reset", { LabStore.updateView { it.copy(taste = StudioTaste()) } }, icon = R.drawable.ic_undo)
    }
    Gap(8.dp)
    Column(Modifier.padding(start = INK_RAIL)) {
        Txt("Name", 15.sp, p.sub)
        NameField(v.studioName.ifEmpty { "${base.name} custom" }, onDone = { n -> LabStore.updateView { it.copy(studioName = n) } })
    }
    val y = studioY ?: return
    val name = v.studioName.trim().ifEmpty { "${base.name} custom" }
    ActionRow(Modifier.padding(start = INK_RAIL)) {
        PillButton("Keep", {
            val t = LabTarget(
                LabIds.slug(name), name, base.rig, "Made in Target Studio from ${base.name}", System.currentTimeMillis(),
                LabIds.r2(y), LabRecipe(base.key, v.model, v.taste),
            )
            LabStore.addTarget(t)
            LabStore.show(t.key, true)
            actions.say("Kept \"$name\" with your targets.")
        }, icon = R.drawable.ic_plus, primary = true)
        PillButton("Fit to it", { LabStore.updateView { it.copy(target = "studio") } }, icon = R.drawable.ic_tune)
        PillButton("Share", { actions.labShare(name, Lab.targetTxt(y)) }, icon = R.drawable.ic_share)
        PillButton("Save .txt", { actions.labSave("$name.txt", Lab.targetTxt(y)) }, icon = R.drawable.ic_import)
        PillButton("Save .csv", { actions.labSave("$name.csv", Lab.targetCsv(y)) }, icon = R.drawable.ic_import)
        PillButton("Copy", { actions.labCopy(Lab.targetTxt(y), "Target copied as tab-separated text.") }, icon = R.drawable.ic_copy)
    }
}
