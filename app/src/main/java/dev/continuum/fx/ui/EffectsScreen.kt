package dev.continuum.fx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import dev.continuum.fx.R
import dev.continuum.fx.audio.FxEnvironment
import dev.continuum.fx.audio.FxStore
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.SpatialStore
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.audio.onNames
import dev.continuum.fx.dsp.DynamicsMode
import dev.continuum.fx.dsp.FxAvailability
import dev.continuum.fx.dsp.FxSettings
import dev.continuum.fx.dsp.Listener
import dev.continuum.fx.dsp.Loudness
import dev.continuum.fx.dsp.PunchMode
import dev.continuum.fx.dsp.StageMode
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.InkSection
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.components.MasterSwitch
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.roundToInt

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
private fun db1(x: Double): String {
    val r = (x * 10).roundToInt() / 10.0
    val s = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
    return if (r > 0) "+$s dB" else if (r < 0) "−${s.removePrefix("-")} dB" else "±0 dB"
}

/**
 * The effects page: what sits on top of the EQ. Grouped as Bass, Clarity, Space and Level, each
 * effect a card that says in a line what it does, opens to explain and to set it, and says when
 * it's resting (an effect for the phone speaker, while you're on headphones) or works more simply
 * in Direct mode.
 */
@Composable
internal fun EffectsContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val fx by FxStore.state.collectAsState()
    val ctx by FxEnvironment.context.collectAsState()
    val output by OutputWatcher.current.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun expand(key: String) {
        open = if (open == key) null else key
    }
    fun set(transform: (FxSettings) -> FxSettings) = FxStore.update(transform)
    val spaces by SpatialStore.state.collectAsState()
    val names = fx.onNames() + if (spaces.enabled) listOf("Spatial audio") else emptyList()

    Column {
        // What's on, said once as a sentence, with the one switch for all of it beside it.
        val waiting = fx.copy(enabled = true).onNames() + if (spaces.enabled) listOf("Spatial audio") else emptyList()
        Kicker(
            if (fx.enabled) "On now" else "Off",
            when {
                fx.enabled -> "${names.size} of $EFFECTS"
                waiting.isNotEmpty() -> "${waiting.size} set to come back"
                else -> null
            },
        )
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            HeroLine(
                when {
                    !fx.enabled -> "Effects are off."
                    names.isEmpty() -> "Nothing on yet."
                    else -> inWords(names)
                },
                if (fx.enabled && names.isNotEmpty()) p.text else p.sub,
                Modifier.weight(1f).padding(end = 12.dp),
            )
            MasterSwitch(fx.enabled, "All effects") { set { it.copy(enabled = !it.enabled) } }
        }
        val quiet = listOfNotNull(
            if (fx.enabled && names.isNotEmpty() && !state.running) "Start processing to hear them" else null,
            if (ctx.direct) "Direct · a few work more simply" else null,
        )
        if (quiet.isNotEmpty()) {
            Gap(10.dp)
            Note(quiet.joinToString(" · "), p.sub)
        }

        output?.let { device ->
            Gap(22.dp)
            ListeningOn(device.name, ctx.onPhoneSpeaker, ctx.listener(fx)) { l ->
                set { it.copy(listeners = it.listeners + (device.key to l)) }
            }
        }

        Group("Bass", 1)
        EffectCard(
            "Analog bass",
            if (fx.bassOn) "Lift at ${FxSettings.PULTEC_FREQS[fx.pultecFreq.coerceIn(0, 3)].toInt()} Hz · drive ${pct(fx.bassDrive)}" else "Console warmth and a Pultec-style lift",
            spot = "bass", expanded = open == "bass", onExpand = { expand("bass") },
            on = fx.bassOn, onToggle = { set { it.copy(bassOn = !it.bassOn) } },
            about = "A vintage console's way with the low end. The Pultec trick lifts and cuts at once: the bass swells while " +
                "the muddy region just above it dips away. Drive adds harmonics that make bass thicker and easier to hear, " +
                "warmth tips them from edgy to round, and drift lets the colour wander very slightly, as old gear does.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("bass") else null),
        ) {
            Txt("Frequency", 15.sp, p.sub)
            Gap(6.dp)
            ChoiceRow {
                FxSettings.PULTEC_FREQS.forEachIndexed { i, f ->
                    Chip("${f.toInt()} Hz", fx.pultecFreq == i, onClick = { set { it.copy(pultecFreq = i) } }, on = Surface.Card)
                }
            }
            Gap(8.dp)
            SliderField("Lift", db1(8.0 * fx.pultecBoost), fx.pultecBoost.toFloat(), hear = "The bass swells, round and deep.") { v -> set { it.copy(pultecBoost = v.toDouble()) } }
            SliderField("Cut", db1(-5.0 * fx.pultecCut), fx.pultecCut.toFloat(), hear = "The boxy, muddy part just above the bass thins out.") { v -> set { it.copy(pultecCut = v.toDouble()) } }
            SliderField("Drive", pct(fx.bassDrive), fx.bassDrive.toFloat(), hear = "Bass grows thicker and easier to hear, even on small drivers.") { v -> set { it.copy(bassDrive = v.toDouble()) } }
            SliderField("Warmth", pct(fx.bassWarmth), fx.bassWarmth.toFloat(), hear = "The drive turns from edgy to round.") { v -> set { it.copy(bassWarmth = v.toDouble()) } }
            SliderField("Drift", pct(fx.bassDrift), fx.bassDrift.toFloat(), hear = "The colour wanders, very slightly, as old gear does.") { v -> set { it.copy(bassDrift = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Punch",
            if (fx.punch == PunchMode.Off) "Kicks and bass notes hit harder" else "Up to ${db1(fx.punch.maxDb)} at each note's start",
            spot = "punch", expanded = open == "punch", onExpand = { expand("punch") },
            on = fx.punch != PunchMode.Off, mode = fx.punch.label,
            about = "The bass is lifted at the start of each note, by how much faster it's rising than its average, and left " +
                "alone while it's held. Kicks and plucked bass punch; a held note doesn't swell or boom.",
            notes = listOfNotNull(if (ctx.direct) "In Direct mode: a slow-attack compressor that lets each note's start through." else null),
        ) {
            ChoiceRow {
                PunchMode.entries.forEach { m -> Chip(m.label, fx.punch == m, onClick = { set { it.copy(punch = m) } }, on = Surface.Card) }
            }
        }
        Gap(8.dp)
        val speakerRest = FxAvailability.speakerBass(ctx)
        EffectCard(
            "Speaker bass",
            when {
                fx.speakerBassOn && speakerRest != null -> "Resting · for the phone speaker"
                fx.speakerBassOn -> "Strength ${pct(fx.speakerBass)}"
                else -> "Bass you can hear from the phone's own speaker"
            },
            spot = "speakerBass", expanded = open == "speakerBass", onExpand = { expand("speakerBass") },
            on = fx.speakerBassOn, onToggle = { set { it.copy(speakerBassOn = !it.speakerBassOn) } },
            resting = fx.speakerBassOn && speakerRest != null,
            about = "A phone's speaker can't move enough air for real bass. Speaker bass takes out the deep notes it would only " +
                "strain at, and adds their harmonics where it can play: hearing fills the notes back in from them.",
            notes = listOfNotNull(speakerRest, if (ctx.direct) FxAvailability.directNote("speakerBass") else null),
        ) {
            SliderField("Strength", pct(fx.speakerBass), fx.speakerBass.toFloat(), hear = "More bass you can hear from the phone, built from harmonics.") { v -> set { it.copy(speakerBass = v.toDouble()) } }
        }

        Group("Clarity", 2)
        EffectCard(
            "Tube warmth",
            if (fx.tubeOn) "Amount ${pct(fx.tube)}" else "The bloom and soft top of valve playback",
            spot = "tube", expanded = open == "tube", onExpand = { expand("tube") },
            on = fx.tubeOn, onToggle = { set { it.copy(tubeOn = !it.tubeOn) } },
            about = "For thin or clinical recordings: a bloom in the low end, a softer top, gentle low-order harmonics (more " +
                "second than third, as valves give) and a slow glue that rounds off the dynamics without making anything quieter.",
            notes = listOfNotNull(
                if (ctx.direct) FxAvailability.directNote("tube") else null,
                if (fx.tubeOn && fx.dynamics == DynamicsMode.Pure) "Pure dynamics is on, so the glue rests." else null,
            ),
        ) {
            SliderField("Amount", pct(fx.tube), fx.tube.toFloat(), hear = "Fuller lows and a softer, smoother top.") { v -> set { it.copy(tube = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Exciter",
            if (fx.exciterOn) "Amount ${pct(fx.exciter)}" else "Presence and sparkle in voices and guitars",
            spot = "exciter", expanded = open == "exciter", onExpand = { expand("exciter") },
            on = fx.exciterOn, onToggle = { set { it.copy(exciterOn = !it.exciterOn) } },
            about = "After the BBE and Aphex boxes: the upper mids, where presence lives, get harmonics of themselves, and only " +
                "that new detail is added back. Clearer and brighter, without a treble boost's hiss.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("exciter") else null),
        ) {
            SliderField("Amount", pct(fx.exciter), fx.exciter.toFloat(), hear = "Voices and guitars step forward, clearer and brighter.") { v -> set { it.copy(exciter = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Hi-res detail",
            if (fx.airOn) "Up to ${db1(5.5 * fx.air)} of air" else "Brings back what lossy files lose",
            spot = "air", expanded = open == "air", onExpand = { expand("air") },
            on = fx.airOn, onToggle = { set { it.copy(airOn = !it.airOn) } },
            about = "The air above 5 kHz is lifted while there's music in it, and left alone when there's only hiss. Where a file " +
                "has cut away its top octave (most streams and MP3s stop at 16 kHz), that octave is rebuilt, faintly, from the one below.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("air") else null),
        ) {
            SliderField("Amount", pct(fx.air), fx.air.toFloat(), hear = "More air and shimmer at the very top.") { v -> set { it.copy(air = v.toDouble()) } }
        }

        Group("Space", 3)
        val nowThrough by EngineStatus.spatialSetup.collectAsState()
        val spatialRest = when {
            ctx.direct -> "Resting · needs Capture"
            !ctx.onHeadphones(fx) -> "Resting · for headphones"
            else -> null
        }
        // While it places the sound, crossfeed and stage would only blur it.
        val spatialPlacing = spaces.enabled && fx.enabled && spatialRest == null
        EffectCard(
            "Spatial audio",
            spatialSummary(spaces, if (state.running) nowThrough else null, spatialRest),
            spot = "spatial", expanded = open == "spatial", onExpand = { expand("spatial") },
            on = spaces.enabled,
            onToggle = {
                // Switching it on opens it: the speakers are the point.
                if (!spaces.enabled) open = "spatial"
                SpatialStore.update { it.copy(enabled = !it.enabled) }
            },
            resting = spaces.enabled && spatialRest != null,
            about = "Your music from speakers placed around you in a room, as they'd reach your ears: each speaker heard " +
                "through a measured head, and a few reflections off the walls to put the sound out in front of you rather " +
                "than inside your head. Every part is yours to set, and each app can have its own setup: a cinema for the " +
                "video player, a pair of monitors for music.",
            notes = listOfNotNull(
                if (ctx.direct) "Capture mode only: Direct mode's effect can't mix the two ears." else null,
                if (!ctx.direct && !ctx.onHeadphones(fx)) "For headphones: it comes on by itself when they're what's playing." else null,
                "Crossfeed and Stage step aside while it's on.",
            ),
        ) {
            SpatialContent()
        }
        Gap(8.dp)
        EffectCard(
            "Stage",
            when (fx.stage) {
                StageMode.Off -> "A wider mix, with voices left in the middle"
                StageMode.Natural -> "A touch wider, from the treble"
                StageMode.Front -> "Voices forward, a little narrower"
                StageMode.Wide -> "Wide open, the deepest bass centred"
            },
            spot = "stage", expanded = open == "stage", onExpand = { expand("stage") },
            on = fx.stage != StageMode.Off, mode = fx.stage.label,
            resting = spatialPlacing && fx.stage != StageMode.Off,
            about = "Only what differs between your ears is widened, so whatever's in the middle (usually the voice) stays " +
                "exactly there. Each adds a gentle smile of low end and air, and nothing to the mids.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("stage") else null),
        ) {
            ChoiceRow {
                StageMode.entries.forEach { m -> Chip(m.label, fx.stage == m, onClick = { set { it.copy(stage = m) } }, on = Surface.Card) }
            }
        }
        Gap(8.dp)
        val crossRest = FxAvailability.crossfeed(fx, ctx)
        EffectCard(
            "Crossfeed",
            when {
                fx.crossfeedOn && spatialPlacing -> "Resting · Spatial audio places the sound"
                fx.crossfeedOn && crossRest != null -> if (ctx.direct) "Resting · needs Capture" else "Resting · for headphones"
                fx.crossfeedOn -> "Strength ${pct(fx.crossfeed)}"
                else -> "Headphones that sound like speakers"
            },
            spot = "crossfeed", expanded = open == "crossfeed", onExpand = { expand("crossfeed") },
            on = fx.crossfeedOn, onToggle = { set { it.copy(crossfeedOn = !it.crossfeedOn) } },
            resting = fx.crossfeedOn && (crossRest != null || spatialPlacing),
            about = "On headphones each ear hears only its own side, so hard-panned sounds sit inside your head. Crossfeed lets " +
                "each ear hear a little of the other, softened and a fraction late, as it would from speakers in front of you. " +
                "Easier on long listens, and anything in the middle is left exactly as it was.",
            notes = listOfNotNull(crossRest),
        ) {
            SliderField("Strength", db1(-14.0 + 9.5 * fx.crossfeed) + " across", fx.crossfeed.toFloat(), hear = "Hard-panned sounds move from inside your head out in front of you.") { v -> set { it.copy(crossfeed = v.toDouble()) } }
        }

        Group("Level", 4)
        EffectCard(
            "Dynamics",
            when (fx.dynamics) {
                DynamicsMode.Off -> "Pure peaks, or squashed masters restored"
                DynamicsMode.Pure -> "The lightest touch on peaks"
                DynamicsMode.Restore -> "Squashed masters opened up"
            },
            spot = "dynamics", expanded = open == "dynamics", onExpand = { expand("dynamics") },
            on = fx.dynamics != DynamicsMode.Off, mode = fx.dynamics.label,
            about = "Pure: peaks are rounded by a soft shoulder rather than stopped at a wall, and nothing else squeezes the " +
                "music (Tube's glue included). Restore: for loud, flattened masters, quiet passages are let back down a little " +
                "and the top opened up.",
            notes = emptyList(),
        ) {
            ChoiceRow {
                DynamicsMode.entries.forEach { m -> Chip(m.label, fx.dynamics == m, onClick = { set { it.copy(dynamics = m) } }, on = Surface.Card) }
            }
        }
        Gap(8.dp)
        val phonNow = Loudness.phonAt(fx.loudnessRef, ctx.volumeDb)
        val bassNow = Loudness.compensationDb(50.0, phonNow, fx.loudnessRef, fx.loudness).coerceAtMost(15.0)
        EffectCard(
            "Loudness",
            if (fx.loudnessOn) (if (bassNow >= 0.5) "Now ${db1(bassNow)} at 50 Hz" else "Resting at this volume") else "Full bass and treble at low volume",
            spot = "loudness", expanded = open == "loudness", onExpand = { expand("loudness") },
            on = fx.loudnessOn, onToggle = { set { it.copy(loudnessOn = !it.loudnessOn) } },
            about = "As the volume comes down, hearing loses bass and treble faster than the middle, so a mix balanced loud " +
                "sounds thin when quiet. Loudness follows your volume and puts back what's lost, by the ISO 226 equal-loudness " +
                "contours. At full volume it does nothing.",
            notes = emptyList(),
        ) {
            SliderField("Amount", pct(fx.loudness), fx.loudness.toFloat(), hear = "More of the bass and treble put back as the volume comes down.") { v -> set { it.copy(loudness = v.toDouble()) } }
            val span = FxSettings.MAX_REF - FxSettings.MIN_REF
            SliderField("Full volume is", "${fx.loudnessRef.roundToInt()} phon", ((fx.loudnessRef - FxSettings.MIN_REF) / span).toFloat(), hear = "Lower: more is put back at every volume. Higher: less.") { v ->
                set { it.copy(loudnessRef = (FxSettings.MIN_REF + v * span).roundToInt().toDouble()) }
            }
            Txt("How loud full volume is for you: lower it if even full volume is a quiet listen.", 13.sp, p.sub)
        }
        Gap(8.dp)
        val btRest = FxAvailability.bluetoothCare(ctx)
        EffectCard(
            "Bluetooth care",
            when {
                fx.bluetoothCare && btRest != null -> "Resting · for Bluetooth"
                fx.bluetoothCare -> "Easing the top octave"
                else -> "Less for Bluetooth's basic codec to struggle with"
            },
            spot = "bt", expanded = open == "bt", onExpand = { expand("bt") },
            on = fx.bluetoothCare, onToggle = { set { it.copy(bluetoothCare = !it.bluetoothCare) } },
            resting = fx.bluetoothCare && btRest != null,
            about = "Bluetooth's basic codec (SBC) shares a fixed budget of bits across the spectrum, every few milliseconds. " +
                "Easing off the top octave, which few can hear, leaves more for the music below and steadies the swishy " +
                "cymbals it's known for. AAC, aptX and LDAC barely notice.",
            notes = listOfNotNull(btRest),
        ) {}

        Gap(30.dp)
    }
}

@Composable
private fun Group(title: String, index: Int) = SectionLabel(title, index = index)

/** The effects that are on, by the names their cards go by, in the page's order. */
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
 * Where each effect's card sits down the page, so the dock can take you from one that's on to
 * the next. Kept up to date as the page lays out; plain numbers rather than state, since nothing
 * is drawn from them.
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

    /** Where to scroll to show the next effect that's on after the last one shown, round again. */
    fun next(keys: List<String>, pad: Int): Int? {
        val known = keys.filter { it in tops }
        if (known.isEmpty()) return null
        val key = known[(known.indexOf(last) + 1) % known.size]
        last = key
        return (tops.getValue(key) - pad).coerceAtLeast(0)
    }
}

internal val LocalEffectSpots = androidx.compose.runtime.staticCompositionLocalOf<EffectSpots?> { null }

/** How many effects the page holds. */
private const val EFFECTS = 12


/** What's being listened on, so effects fit it. */
@Composable
private fun ListeningOn(name: String, phone: Boolean, current: Listener, onPick: (Listener) -> Unit) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    InkSection(open, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpensName("Listening on", open, p.text, Modifier.weight(1f))
            ValueNote(if (phone || current == Listener.Unsure) name else "$name · ${current.label}")
        }
    }) {
        Column(Modifier.padding(bottom = 10.dp)) {
            if (phone) {
                Txt("Effects keep to what the phone's speaker can play. Speaker bass is made for it.", 14.sp, p.sub)
            } else {
                ChoiceRow {
                    Listener.entries.filter { it != Listener.PhoneSpeaker }.forEach { l ->
                        Chip(l.label, current == l, onClick = { onPick(l) }, on = Surface.Card)
                    }
                }
                Txt(
                    "Bass and treble effects ask no more than this kind of driver can give, and crossfeed is for headphones.",
                    14.sp, p.sub, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * One effect: its name and a line on what it's doing, with a switch (or its mode) at the end.
 * Tapping opens it out to explain and to set it.
 */
@Composable
private fun EffectCard(
    title: String,
    summary: String,
    /** Its name for the dock, which steps through the effects that are on. */
    spot: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    on: Boolean,
    onToggle: (() -> Unit)? = null,
    mode: String? = null,
    resting: Boolean = false,
    about: String,
    notes: List<String>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    var how by rememberSaveable(title) { mutableStateOf(false) }
    val spots = LocalEffectSpots.current
    // Off, the line under its name says what it would do; on, what it's doing.
    InkSection(expanded, quiet = !on, modifier = Modifier.onGloballyPositioned { c -> spots?.place(spot, c.positionInRoot().y) }, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onExpand)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                OpensName(title, expanded, p.text)
                Txt(summary, 14.sp, if (on && !resting) p.text else p.sub, maxLines = 1)
            }
            if (onToggle != null) {
                InkSwitch(on, title, onToggle)
            } else if (mode != null) {
                Spacer(Modifier.width(12.dp))
                if (on) Txt(mode, 15.sp, p.text, maxLines = 1) else Note("Off", p.sub)
            }
        }
    }) {
        // What you set comes first; why it works is there for whoever wants it.
        Column(Modifier.padding(top = 2.dp, bottom = 12.dp)) {
            content()
            notes.forEach { n ->
                Gap(6.dp)
                Txt(n, 14.sp, p.text)
            }
            Gap(4.dp)
            MoreSection(how, { how = !how }, label = "How it works") {
                Txt(about, 14.sp, p.sub, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
}
