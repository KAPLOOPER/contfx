package dev.continuum.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.FxEnvironment
import dev.continuum.fx.audio.FxStore
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.audio.SpatialStore
import dev.continuum.fx.audio.onNames
import dev.continuum.fx.dsp.DynamicsMode
import dev.continuum.fx.dsp.FxAvailability
import dev.continuum.fx.dsp.FxSettings
import dev.continuum.fx.dsp.Listener
import dev.continuum.fx.dsp.Loudness
import dev.continuum.fx.dsp.PunchMode
import dev.continuum.fx.dsp.StageMode
import dev.continuum.fx.ui.bench.BenchMore
import dev.continuum.fx.ui.bench.BenchRow
import dev.continuum.fx.ui.bench.BenchSection
import dev.continuum.fx.ui.bench.BenchValueRow
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
private fun db1(x: Double): String {
    val r = (x * 10).roundToInt() / 10.0
    val s = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
    return if (r > 0) "+$s dB" else if (r < 0) "−${s.removePrefix("-")} dB" else "±0 dB"
}

/** The twelve stages, ordered as the signal flows, in their four groups. */
private val STAGES: List<List<Pair<String, String>>> = listOf(
    listOf("bass" to "Analog bass", "punch" to "Punch", "speakerBass" to "Speaker bass"),
    listOf("tube" to "Tube warmth", "exciter" to "Exciter", "air" to "Hi-res detail"),
    listOf("spatial" to "Spatial audio", "stage" to "Stage", "crossfeed" to "Crossfeed"),
    listOf("dynamics" to "Dynamics", "loudness" to "Loudness", "bt" to "Bluetooth care"),
)

/** What an opened block ends with: any live notes, then "How it works" folded away. */
@Composable
private fun About(about: String, notes: List<String> = emptyList()) {
    val p = LocalPalette.current
    val how = rememberSaveable { mutableStateOf(false) }
    notes.filter { it.isNotEmpty() }.forEach { n ->
        Gap(6.dp)
        Txt(n, 14.sp, p.text)
    }
    Gap(4.dp)
    BenchMore("How it works", how.value) { how.value = !how.value }
    if (how.value) {
        Gap(4.dp)
        Txt(about, 14.sp, p.sub)
    }
}

/**
 * The effects page: what sits on top of the EQ. The hero is the chain strip — the twelve stages
 * as one continuous ink line, the live ones filled drops on it; tapping a stage goes to its row.
 * Under it, the ledger: numbered rules and a row per effect, each opening in place to its
 * controls under a 1dp thread of ink.
 */
@Composable
internal fun EffectsContent(state: UiState, actions: AppActions, scroll: androidx.compose.foundation.ScrollState) {
    val p = LocalPalette.current
    val fx by FxStore.state.collectAsState()
    val ctx by FxEnvironment.context.collectAsState()
    val output by OutputWatcher.current.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val spots = LocalEffectSpots.current
    val scope = rememberCoroutineScope()
    val padPx = with(LocalDensity.current) { 16.dp.roundToPx() }
    fun expand(key: String) {
        open = if (open == key) null else key
    }
    fun toRow(key: String) {
        spots?.topFor(key, padPx)?.let { y -> scope.launch { scroll.animateScrollTo(y, Motion.flow()) } }
    }
    // Tapping a group of the chain takes you to its section's rule, a little above its first row.
    val groupPad = with(LocalDensity.current) { 54.dp.roundToPx() }
    fun toGroup(key: String) {
        spots?.topFor(key, groupPad)?.let { y -> scope.launch { scroll.animateScrollTo(y, Motion.flow()) } }
    }
    fun set(transform: (FxSettings) -> FxSettings) = FxStore.update(transform)
    val spaces by SpatialStore.state.collectAsState()
    val names = fx.onNames() + if (spaces.enabled) listOf("Spatial audio") else emptyList()
    // The strip speaks in the settings' own keys, not the display names.
    val active = activeEffects(fx, spaces.enabled).toSet()
    // A group's rule says what the group is doing, so the ledger has a second axis to scan by.
    fun countOf(g: Int): String? = STAGES[g].count { it.first in active }.let { if (it == 0) null else "$it on" }

    Column {
        // What's on, said once as a counted rule; the master switch carries no caption — the hero
        // line beside it and the count above are the words.
        val waiting = fx.copy(enabled = true).onNames() + if (spaces.enabled) listOf("Spatial audio") else emptyList()
        Kicker(if (fx.enabled) "${names.size} of $EFFECTS on" else "Off")
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroLine(
                when {
                    !fx.enabled -> "Effects are off."
                    names.isEmpty() -> "Nothing on yet."
                    else -> inWords(names)
                },
                if (fx.enabled && names.isNotEmpty()) p.text else p.sub,
                Modifier.weight(1f).padding(end = 12.dp),
            )
            InkSwitch(fx.enabled, "All effects", onClick = { set { it.copy(enabled = !it.enabled) } })
        }
        val quiet = listOfNotNull(
            if (!fx.enabled && waiting.isNotEmpty()) "${waiting.size} set to come back" else null,
            if (fx.enabled && names.isNotEmpty() && !state.running) "Start processing to hear them" else null,
            if (ctx.direct) "Direct · a few work more simply" else null,
        )
        if (quiet.isNotEmpty()) {
            Gap(8.dp)
            Txt(quiet.joinToString(" · "), 13.sp, p.sub, maxLines = 2)
        }
        ChainStrip(active, ::toGroup, Modifier.padding(top = 18.dp, bottom = 22.dp))

        output?.let { device ->
            BenchSection(null, "Listening")
            ListeningOn(device.name, ctx.onPhoneSpeaker, ctx.listener(fx)) { l ->
                set { it.copy(listeners = it.listeners + (device.key to l)) }
            }
        }

        BenchSection(1, "Bass", trail = countOf(0))
        BenchRow(
            "Analog bass",
            if (fx.bassOn) "Lift at ${FxSettings.PULTEC_FREQS[fx.pultecFreq.coerceIn(0, 3)].toInt()} Hz · drive ${pct(fx.bassDrive)}" else "Console warmth and a Pultec-style lift",
            on = fx.bassOn, expanded = open == "bass", onExpand = { expand("bass") },
            onToggle = { set { it.copy(bassOn = !it.bassOn) } },
            switchDescription = "Analog bass",
            modifier = Modifier.effectSpot("bass"),
        ) {
            Txt("Frequency", 15.sp, p.sub)
            Gap(6.dp)
            ChoiceRow {
                FxSettings.PULTEC_FREQS.forEachIndexed { i, f ->
                    Chip("${f.toInt()} Hz", fx.pultecFreq == i, onClick = { set { it.copy(pultecFreq = i) } })
                }
            }
            Gap(8.dp)
            SliderField("Lift", db1(8.0 * fx.pultecBoost), fx.pultecBoost.toFloat(), hear = "The bass swells, round and deep.") { v -> set { it.copy(pultecBoost = v.toDouble()) } }
            SliderField("Cut", db1(-5.0 * fx.pultecCut), fx.pultecCut.toFloat(), hear = "The boxy, muddy part just above the bass thins out.") { v -> set { it.copy(pultecCut = v.toDouble()) } }
            SliderField("Drive", pct(fx.bassDrive), fx.bassDrive.toFloat(), hear = "Bass grows thicker and easier to hear, even on small drivers.") { v -> set { it.copy(bassDrive = v.toDouble()) } }
            SliderField("Warmth", pct(fx.bassWarmth), fx.bassWarmth.toFloat(), hear = "The drive turns from edgy to round.") { v -> set { it.copy(bassWarmth = v.toDouble()) } }
            SliderField("Drift", pct(fx.bassDrift), fx.bassDrift.toFloat(), hear = "The colour wanders, very slightly, as old gear does.") { v -> set { it.copy(bassDrift = v.toDouble()) } }
            About(
                "A vintage console's way with the low end. The Pultec trick lifts and cuts at once: the bass swells while the " +
                    "muddy region just above it dips away. Drive adds harmonics that make bass thicker and easier to hear, " +
                    "warmth tips them from edgy to round, and drift lets the colour wander very slightly, as old gear does.",
                listOfNotNull(if (ctx.direct) FxAvailability.directNote("bass") else null),
            )
        }
        BenchRow(
            "Punch",
            if (fx.punch == PunchMode.Off) "Kicks and bass notes hit harder" else "Up to ${db1(fx.punch.maxDb)} at each note's start",
            on = fx.punch != PunchMode.Off, expanded = open == "punch", onExpand = { expand("punch") },
            trailing = {
                if (fx.punch != PunchMode.Off) Txt(fx.punch.label, 15.sp, p.text, maxLines = 1) else Note("Off", p.sub)
            },
            modifier = Modifier.effectSpot("punch"),
        ) {
            ChoiceRow {
                PunchMode.entries.forEach { m -> Chip(m.label, fx.punch == m, onClick = { set { it.copy(punch = m) } }) }
            }
            About(
                "The bass is lifted at the start of each note, by how much faster it's rising than its average, and left " +
                    "alone while it's held. Kicks and plucked bass punch; a held note doesn't swell or boom.",
                listOfNotNull(if (ctx.direct) "In Direct mode: a slow-attack compressor that lets each note's start through." else null),
            )
        }
        val speakerRest = FxAvailability.speakerBass(ctx)
        BenchRow(
            "Speaker bass",
            when {
                fx.speakerBassOn && speakerRest != null -> "Resting · for the phone speaker"
                fx.speakerBassOn -> "Strength ${pct(fx.speakerBass)}"
                else -> "Bass you can hear from the phone's own speaker"
            },
            on = fx.speakerBassOn, resting = fx.speakerBassOn && speakerRest != null,
            expanded = open == "speakerBass", onExpand = { expand("speakerBass") },
            onToggle = { set { it.copy(speakerBassOn = !it.speakerBassOn) } },
            switchDescription = "Speaker bass",
            modifier = Modifier.effectSpot("speakerBass"),
        ) {
            SliderField("Strength", pct(fx.speakerBass), fx.speakerBass.toFloat(), hear = "More bass you can hear from the phone, built from harmonics.") { v -> set { it.copy(speakerBass = v.toDouble()) } }
            About(
                "A phone's speaker can't move enough air for real bass. Speaker bass takes out the deep notes it would only " +
                    "strain at, and adds their harmonics where it can play: hearing fills the notes back in from them.",
                listOfNotNull(speakerRest, if (ctx.direct) FxAvailability.directNote("speakerBass") else null),
            )
        }

        BenchSection(2, "Clarity", trail = countOf(1))
        BenchRow(
            "Tube warmth",
            if (fx.tubeOn) "Amount ${pct(fx.tube)}" else "The bloom and soft top of valve playback",
            on = fx.tubeOn, expanded = open == "tube", onExpand = { expand("tube") },
            onToggle = { set { it.copy(tubeOn = !it.tubeOn) } },
            switchDescription = "Tube warmth",
            modifier = Modifier.effectSpot("tube"),
        ) {
            SliderField("Amount", pct(fx.tube), fx.tube.toFloat(), hear = "Fuller lows and a softer, smoother top.") { v -> set { it.copy(tube = v.toDouble()) } }
            About(
                "For thin or clinical recordings: a bloom in the low end, a softer top, gentle low-order harmonics (more " +
                    "second than third, as valves give) and a slow glue that rounds off the dynamics without making anything quieter.",
                listOfNotNull(
                    if (ctx.direct) FxAvailability.directNote("tube") else null,
                    if (fx.tubeOn && fx.dynamics == DynamicsMode.Pure) "Pure dynamics is on, so the glue rests." else null,
                ),
            )
        }
        BenchRow(
            "Exciter",
            if (fx.exciterOn) "Amount ${pct(fx.exciter)}" else "Presence and sparkle in voices and guitars",
            on = fx.exciterOn, expanded = open == "exciter", onExpand = { expand("exciter") },
            onToggle = { set { it.copy(exciterOn = !it.exciterOn) } },
            switchDescription = "Exciter",
            modifier = Modifier.effectSpot("exciter"),
        ) {
            SliderField("Amount", pct(fx.exciter), fx.exciter.toFloat(), hear = "Voices and guitars step forward, clearer and brighter.") { v -> set { it.copy(exciter = v.toDouble()) } }
            About(
                "After the BBE and Aphex boxes: the upper mids, where presence lives, get harmonics of themselves, and only " +
                    "that new detail is added back. Clearer and brighter, without a treble boost's hiss.",
                listOfNotNull(if (ctx.direct) FxAvailability.directNote("exciter") else null),
            )
        }
        BenchRow(
            "Hi-res detail",
            if (fx.airOn) "Up to ${db1(5.5 * fx.air)} of air" else "Brings back what lossy files lose",
            on = fx.airOn, expanded = open == "air", onExpand = { expand("air") },
            onToggle = { set { it.copy(airOn = !it.airOn) } },
            switchDescription = "Hi-res detail",
            modifier = Modifier.effectSpot("air"),
        ) {
            SliderField("Amount", pct(fx.air), fx.air.toFloat(), hear = "More air and shimmer at the very top.") { v -> set { it.copy(air = v.toDouble()) } }
            About(
                "The air above 5 kHz is lifted while there's music in it, and left alone when there's only hiss. Where a file " +
                    "has cut away its top octave (most streams and MP3s stop at 16 kHz), that octave is rebuilt, faintly, from the one below.",
                listOfNotNull(if (ctx.direct) FxAvailability.directNote("air") else null),
            )
        }

        BenchSection(3, "Space", trail = countOf(2))
        val nowThrough by EngineStatus.spatialSetup.collectAsState()
        val spatialRest = when {
            ctx.direct -> "Resting · needs Capture"
            !ctx.onHeadphones(fx) -> "Resting · for headphones"
            else -> null
        }
        // While it places the sound, crossfeed and stage would only blur it.
        val spatialPlacing = spaces.enabled && fx.enabled && spatialRest == null
        BenchRow(
            "Spatial audio",
            spatialSummary(spaces, if (state.running) nowThrough else null, spatialRest),
            on = spaces.enabled, resting = spaces.enabled && spatialRest != null,
            expanded = open == "spatial", onExpand = { expand("spatial") },
            onToggle = {
                // Switching it on opens it: the speakers are the point.
                if (!spaces.enabled) open = "spatial"
                SpatialStore.update { it.copy(enabled = !it.enabled) }
            },
            switchDescription = "Spatial audio",
            modifier = Modifier.effectSpot("spatial"),
        ) {
            SpatialContent()
            About(
                "Your music from speakers placed around you in a room, as they'd reach your ears: each speaker heard " +
                    "through a measured head, and a few reflections off the walls to put the sound out in front of you rather " +
                    "than inside your head. Every part is yours to set, and each app can have its own setup: a cinema for the " +
                    "video player, a pair of monitors for music.",
                listOfNotNull(
                    if (ctx.direct) "Capture mode only: Direct mode's effect can't mix the two ears." else null,
                    if (!ctx.direct && !ctx.onHeadphones(fx)) "For headphones: it comes on by itself when they're what's playing." else null,
                    "Crossfeed and Stage step aside while it's on.",
                ),
            )
        }
        BenchRow(
            "Stage",
            when (fx.stage) {
                StageMode.Off -> "A wider mix, with voices left in the middle"
                StageMode.Natural -> "A touch wider, from the treble"
                StageMode.Front -> "Voices forward, a little narrower"
                StageMode.Wide -> "Wide open, the deepest bass centred"
            },
            on = fx.stage != StageMode.Off, expanded = open == "stage", onExpand = { expand("stage") },
            trailing = {
                if (fx.stage != StageMode.Off) Txt(fx.stage.label, 15.sp, p.text, maxLines = 1) else Note("Off", p.sub)
            },
            resting = spatialPlacing && fx.stage != StageMode.Off,
            modifier = Modifier.effectSpot("stage"),
        ) {
            ChoiceRow {
                StageMode.entries.forEach { m -> Chip(m.label, fx.stage == m, onClick = { set { it.copy(stage = m) } }) }
            }
            About(
                "Only what differs between your ears is widened, so whatever's in the middle (usually the voice) stays " +
                    "exactly there. Each adds a gentle smile of low end and air, and nothing to the mids.",
                listOfNotNull(if (ctx.direct) FxAvailability.directNote("stage") else null),
            )
        }
        val crossRest = FxAvailability.crossfeed(fx, ctx)
        BenchRow(
            "Crossfeed",
            when {
                fx.crossfeedOn && spatialPlacing -> "Resting · Spatial audio places the sound"
                fx.crossfeedOn && crossRest != null -> if (ctx.direct) "Resting · needs Capture" else "Resting · for headphones"
                fx.crossfeedOn -> "Strength ${pct(fx.crossfeed)}"
                else -> "Headphones that sound like speakers"
            },
            on = fx.crossfeedOn, resting = fx.crossfeedOn && (crossRest != null || spatialPlacing),
            expanded = open == "crossfeed", onExpand = { expand("crossfeed") },
            onToggle = { set { it.copy(crossfeedOn = !it.crossfeedOn) } },
            switchDescription = "Crossfeed",
            modifier = Modifier.effectSpot("crossfeed"),
        ) {
            SliderField("Strength", db1(-14.0 + 9.5 * fx.crossfeed) + " across", fx.crossfeed.toFloat(), hear = "Hard-panned sounds move from inside your head out in front of you.") { v -> set { it.copy(crossfeed = v.toDouble()) } }
            About(
                "On headphones each ear hears only its own side, so hard-panned sounds sit inside your head. Crossfeed lets " +
                    "each ear hear a little of the other, softened and a fraction late, as it would from speakers in front of you. " +
                    "Easier on long listens, and anything in the middle is left exactly as it was.",
                listOfNotNull(crossRest),
            )
        }

        BenchSection(4, "Level", trail = countOf(3))
        BenchRow(
            "Dynamics",
            when (fx.dynamics) {
                DynamicsMode.Off -> "Pure peaks, or squashed masters restored"
                DynamicsMode.Pure -> "The lightest touch on peaks"
                DynamicsMode.Restore -> "Squashed masters opened up"
            },
            on = fx.dynamics != DynamicsMode.Off, expanded = open == "dynamics", onExpand = { expand("dynamics") },
            trailing = {
                if (fx.dynamics != DynamicsMode.Off) Txt(fx.dynamics.label, 15.sp, p.text, maxLines = 1) else Note("Off", p.sub)
            },
            modifier = Modifier.effectSpot("dynamics"),
        ) {
            ChoiceRow {
                DynamicsMode.entries.forEach { m -> Chip(m.label, fx.dynamics == m, onClick = { set { it.copy(dynamics = m) } }) }
            }
            About(
                "Pure: peaks are rounded by a soft shoulder rather than stopped at a wall, and nothing else squeezes the " +
                    "music (Tube's glue included). Restore: for loud, flattened masters, quiet passages are let back down a little " +
                    "and the top opened up.",
            )
        }
        val phonNow = Loudness.phonAt(fx.loudnessRef, ctx.volumeDb)
        val bassNow = Loudness.compensationDb(50.0, phonNow, fx.loudnessRef, fx.loudness).coerceAtMost(15.0)
        BenchRow(
            "Loudness",
            if (fx.loudnessOn) (if (bassNow >= 0.5) "Now ${db1(bassNow)} at 50 Hz" else "Resting at this volume") else "Full bass and treble at low volume",
            on = fx.loudnessOn, resting = fx.loudnessOn && bassNow < 0.5,
            expanded = open == "loudness", onExpand = { expand("loudness") },
            onToggle = { set { it.copy(loudnessOn = !it.loudnessOn) } },
            switchDescription = "Loudness",
            modifier = Modifier.effectSpot("loudness"),
        ) {
            SliderField("Amount", pct(fx.loudness), fx.loudness.toFloat(), hear = "More of the bass and treble put back as the volume comes down.") { v -> set { it.copy(loudness = v.toDouble()) } }
            val span = FxSettings.MAX_REF - FxSettings.MIN_REF
            SliderField("Full volume is", "${fx.loudnessRef.roundToInt()} phon", ((fx.loudnessRef - FxSettings.MIN_REF) / span).toFloat(), hear = "Lower: more is put back at every volume. Higher: less.") { v ->
                set { it.copy(loudnessRef = (FxSettings.MIN_REF + v * span).roundToInt().toDouble()) }
            }
            Gap(2.dp)
            Txt("How loud full volume is for you: lower it if even full volume is a quiet listen.", 13.sp, p.sub)
            About(
                "As the volume comes down, hearing loses bass and treble faster than the middle, so a mix balanced loud " +
                    "sounds thin when quiet. Loudness follows your volume and puts back what's lost, by the ISO 226 equal-loudness " +
                    "contours. At full volume it does nothing.",
            )
        }
        val btRest = FxAvailability.bluetoothCare(ctx)
        BenchRow(
            "Bluetooth care",
            when {
                fx.bluetoothCare && btRest != null -> "Resting · for Bluetooth"
                fx.bluetoothCare -> "Easing the top octave"
                else -> "Less for Bluetooth's basic codec to struggle with"
            },
            on = fx.bluetoothCare, resting = fx.bluetoothCare && btRest != null,
            expanded = open == "bt", onExpand = { expand("bt") },
            onToggle = { set { it.copy(bluetoothCare = !it.bluetoothCare) } },
            switchDescription = "Bluetooth care",
            modifier = Modifier.effectSpot("bt"),
        ) {
            About(
                "Bluetooth's basic codec (SBC) shares a fixed budget of bits across the spectrum, every few milliseconds. " +
                    "Easing off the top octave, which few can hear, leaves more for the music below and steadies the swishy " +
                    "cymbals it's known for. AAC, aptX and LDAC barely notice.",
                listOfNotNull(btRest),
            )
        }

        Gap(30.dp)
    }
}

/** Keeps a row down the page, so the chain strip and the stepping can find it. */
@Composable
private fun Modifier.effectSpot(spot: String): Modifier {
    val spots = LocalEffectSpots.current
    return onGloballyPositioned { c -> spots?.place(spot, c.positionInRoot().y) }
}

/**
 * The effects that are on, by their keys, in the page's order.
 */
internal fun activeEffects(fx: FxSettings, spatial: Boolean): List<String> {
    if (!fx.enabled) return emptyList()
    return listOfNotNull(
        "bass".takeIf { fx.bassOn },
        "punch".takeIf { fx.punch != PunchMode.Off },
        "speakerBass".takeIf { fx.speakerBassOn },
        "tube".takeIf { fx.tubeOn },
        "exciter".takeIf { fx.exciterOn },
        "air".takeIf { fx.airOn },
        "spatial".takeIf { spatial },
        "stage".takeIf { fx.stage != StageMode.Off },
        "crossfeed".takeIf { fx.crossfeedOn },
        "dynamics".takeIf { fx.dynamics != DynamicsMode.Off },
        "loudness".takeIf { fx.loudnessOn },
        "bt".takeIf { fx.bluetoothCare },
    )
}

/**
 * Where each effect's row sits down the page, so the chain strip can take you to one and the
 * status strip can step through the ones that are on. Kept up to date as the page lays out;
 * plain numbers rather than state, since nothing is drawn from them.
 */
internal class EffectSpots {
    private val tops = HashMap<String, Int>()
    /** The page's scroll, and where its window starts on screen. */
    var scroll: androidx.compose.foundation.ScrollState? = null
    var viewTop = 0f
    private var last: String? = null

    fun place(key: String, rootY: Float) {
        val s = scroll ?: return
        tops[key] = (rootY - viewTop + s.value).toInt()
    }

    /** Where to scroll to show the row [key] sits in, once the page's top row has passed. */
    internal fun topFor(key: String, pad: Int): Int? =
        tops[key]?.let { (it - pad).coerceAtLeast(0) }

    /** Where to scroll to show the next effect that's on after the last one shown, round again. */
    fun next(keys: List<String>, pad: Int): Int? {
        val known = keys.filter { it in tops }
        if (known.isEmpty()) return null
        val key = known[(known.indexOf(last) + 1) % known.size]
        last = key
        return topFor(key, pad)
    }
}

internal val LocalEffectSpots = androidx.compose.runtime.staticCompositionLocalOf<EffectSpots?> { null }

/** How many effects the page holds. */
private const val EFFECTS = 12

/**
 * The chain strip: the twelve stages in their four groups — Bass, Clarity, Space, Level — each
 * group a cluster of its stages' marks with its name under it in the note face: a filled 7dp drop
 * for a stage that's on, a 3dp hollow ring for one that's off, 8dp between marks in a cluster and
 * 20dp between groups. A group's name is `p.text` the moment anything in it is on, `p.sub` when
 * not. Tapping a group goes to its section. 84dp of strip counting its stand before it: 44dp of
 * marks and names, 18dp between the hero line and the clusters, 22dp under them.
 */
@Composable
private fun ChainStrip(on: Set<String>, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        STAGES.forEachIndexed { g, group ->
            val anyOn = group.any { it.first in on }
            val name = GROUP_NAMES[g]
            val description = "$name. " + group.joinToString(", ") { stage ->
                "${stage.second}${if (stage.first in on) ", on" else ""}"
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember(g) { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Go to $name",
                    ) { onTap(group.first().first) }
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                ) {
                    val y = size.height / 2f
                    val step = size.width / (group.size + 1)
                    val r = 5.dp.toPx()
                    // The chain: one line the width of the cell, broken where a stage is on, so the
                    // ink reads as a bead sitting in the wire rather than a dot drawn over it.
                    val marks = List(group.size) { i -> step * (i + 1) }
                    var edge = 0f
                    marks.forEachIndexed { i, x ->
                        val on_ = group[i].first in on
                        drawLine(p.line, Offset(edge, y), Offset(if (on_) x - r else x, y), strokeWidth = 1.dp.toPx())
                        edge = if (on_) x + r else x
                        if (on_) {
                            drawCircle(p.text, r, Offset(x, y))
                        } else {
                            drawCircle(p.ground, 2.5.dp.toPx(), Offset(x, y))
                            drawCircle(p.line, 2.5.dp.toPx(), Offset(x, y), style = Stroke(width = 1.dp.toPx()))
                        }
                    }
                    drawLine(p.line, Offset(edge, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                Spacer(Modifier.height(7.dp))
                Note(name, if (anyOn) p.text else p.sub)
            }
        }
    }
}

/** The chain's groups, in the page's order: the ledger's sections. */
private val GROUP_NAMES = listOf("Bass", "Clarity", "Space", "Level")

/** What's being listened on, so effects fit it. */
@Composable
private fun ListeningOn(name: String, phone: Boolean, current: Listener, onPick: (Listener) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    BenchValueRow(
        label = "Listening on",
        value = if (phone || current == Listener.Unsure) name else "$name · ${current.label}",
        expanded = open,
        onExpand = { open = !open },
    ) {
        if (phone) {
            Txt("Effects keep to what the phone's speaker can play. Speaker bass is made for it.", 14.sp, LocalPalette.current.sub)
        } else {
            ChoiceRow {
                Listener.entries.filter { it != Listener.PhoneSpeaker }.forEach { l ->
                    Chip(l.label, current == l, onClick = { onPick(l) })
                }
            }
            Txt(
                "Bass and treble effects ask no more than this kind of driver can give, and crossfeed is for headphones.",
                14.sp, LocalPalette.current.sub, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}


