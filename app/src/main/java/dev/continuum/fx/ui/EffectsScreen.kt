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
import dev.continuum.fx.R
import dev.continuum.fx.audio.FxEnvironment
import dev.continuum.fx.audio.FxStore
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
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.roundToInt

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
private fun db1(x: Double): String {
    val r = (x * 10).roundToInt() / 10.0
    val s = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
    return if (r > 0) "+$s dB" else if (r < 0) "−${s.removePrefix("-")} dB" else "0 dB"
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
    val names = fx.onNames()

    Column {
        val status = when {
            !fx.enabled -> "Off · only the EQ plays"
            names.isEmpty() -> "Nothing on yet"
            !state.running -> "${names.joinToString()} · start the EQ to hear them"
            else -> "On · ${names.joinToString()}"
        }
        Txt(status, 17.sp, if (fx.enabled && names.isNotEmpty() && state.running) p.text else p.sub, maxLines = 3)
        Gap(14.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(if (fx.enabled) "Effects on" else "Effects off", fx.enabled, onClick = { set { it.copy(enabled = !it.enabled) } })
            if (state.running && fx.anyOn) ComparePill()
        }
        if (ctx.direct) {
            Gap(8.dp)
            Txt("Direct mode runs the effects inside Android's own effect, so a few work more simply. Each says how.", 14.sp, p.sub)
        }

        output?.let { device ->
            Gap(18.dp)
            ListeningOn(device.name, ctx.onPhoneSpeaker, ctx.listener(fx)) { l ->
                set { it.copy(listeners = it.listeners + (device.key to l)) }
            }
        }

        Group("Bass")
        EffectCard(
            "Analog bass",
            if (fx.bassOn) "Lift at ${FxSettings.PULTEC_FREQS[fx.pultecFreq.coerceIn(0, 3)].toInt()} Hz · drive ${pct(fx.bassDrive)}" else "Console warmth and a Pultec-style lift",
            expanded = open == "bass", onExpand = { expand("bass") },
            on = fx.bassOn, onToggle = { set { it.copy(bassOn = !it.bassOn) } },
            about = "A vintage console's way with the low end. The Pultec trick lifts and cuts at once: the bass swells while " +
                "the muddy region just above it dips away. Drive adds harmonics that make bass thicker and easier to hear, " +
                "warmth tips them from edgy to round, and drift lets the colour wander very slightly, as old gear does.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("bass") else null),
        ) {
            Txt("Frequency", 15.sp, p.sub)
            Gap(6.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FxSettings.PULTEC_FREQS.forEachIndexed { i, f ->
                    Chip("${f.toInt()} Hz", fx.pultecFreq == i, onClick = { set { it.copy(pultecFreq = i) } }, on = Surface.Card)
                }
            }
            Gap(8.dp)
            SliderField("Lift", db1(8.0 * fx.pultecBoost), fx.pultecBoost.toFloat()) { v -> set { it.copy(pultecBoost = v.toDouble()) } }
            SliderField("Cut", db1(-5.0 * fx.pultecCut), fx.pultecCut.toFloat()) { v -> set { it.copy(pultecCut = v.toDouble()) } }
            SliderField("Drive", pct(fx.bassDrive), fx.bassDrive.toFloat()) { v -> set { it.copy(bassDrive = v.toDouble()) } }
            SliderField("Warmth", pct(fx.bassWarmth), fx.bassWarmth.toFloat()) { v -> set { it.copy(bassWarmth = v.toDouble()) } }
            SliderField("Drift", pct(fx.bassDrift), fx.bassDrift.toFloat()) { v -> set { it.copy(bassDrift = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Punch",
            if (fx.punch == PunchMode.Off) "Kicks and bass notes hit harder" else "Up to ${db1(fx.punch.maxDb)} at each note's start",
            expanded = open == "punch", onExpand = { expand("punch") },
            on = fx.punch != PunchMode.Off, mode = fx.punch.label,
            about = "The bass is lifted at the start of each note, by how much faster it's rising than its average, and left " +
                "alone while it's held. Kicks and plucked bass punch; a held note doesn't swell or boom.",
            notes = listOfNotNull(if (ctx.direct) "In Direct mode: a slow-attack compressor that lets each note's start through." else null),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            expanded = open == "speakerBass", onExpand = { expand("speakerBass") },
            on = fx.speakerBassOn, onToggle = { set { it.copy(speakerBassOn = !it.speakerBassOn) } },
            resting = fx.speakerBassOn && speakerRest != null,
            about = "A phone's speaker can't move enough air for real bass. Speaker bass takes out the deep notes it would only " +
                "strain at, and adds their harmonics where it can play: hearing fills the notes back in from them.",
            notes = listOfNotNull(speakerRest, if (ctx.direct) FxAvailability.directNote("speakerBass") else null),
        ) {
            SliderField("Strength", pct(fx.speakerBass), fx.speakerBass.toFloat()) { v -> set { it.copy(speakerBass = v.toDouble()) } }
        }

        Group("Clarity")
        EffectCard(
            "Tube warmth",
            if (fx.tubeOn) "Amount ${pct(fx.tube)}" else "The bloom and soft top of valve playback",
            expanded = open == "tube", onExpand = { expand("tube") },
            on = fx.tubeOn, onToggle = { set { it.copy(tubeOn = !it.tubeOn) } },
            about = "For thin or clinical recordings: a bloom in the low end, a softer top, gentle low-order harmonics (more " +
                "second than third, as valves give) and a slow glue that rounds off the dynamics without making anything quieter.",
            notes = listOfNotNull(
                if (ctx.direct) FxAvailability.directNote("tube") else null,
                if (fx.tubeOn && fx.dynamics == DynamicsMode.Pure) "Pure dynamics is on, so the glue rests." else null,
            ),
        ) {
            SliderField("Amount", pct(fx.tube), fx.tube.toFloat()) { v -> set { it.copy(tube = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Exciter",
            if (fx.exciterOn) "Amount ${pct(fx.exciter)}" else "Presence and sparkle in voices and guitars",
            expanded = open == "exciter", onExpand = { expand("exciter") },
            on = fx.exciterOn, onToggle = { set { it.copy(exciterOn = !it.exciterOn) } },
            about = "After the BBE and Aphex boxes: the upper mids, where presence lives, get harmonics of themselves, and only " +
                "that new detail is added back. Clearer and brighter, without a treble boost's hiss.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("exciter") else null),
        ) {
            SliderField("Amount", pct(fx.exciter), fx.exciter.toFloat()) { v -> set { it.copy(exciter = v.toDouble()) } }
        }
        Gap(8.dp)
        EffectCard(
            "Hi-res detail",
            if (fx.airOn) "Up to ${db1(5.5 * fx.air)} of air" else "Brings back what lossy files lose",
            expanded = open == "air", onExpand = { expand("air") },
            on = fx.airOn, onToggle = { set { it.copy(airOn = !it.airOn) } },
            about = "The air above 5 kHz is lifted while there's music in it, and left alone when there's only hiss. Where a file " +
                "has cut away its top octave (most streams and MP3s stop at 16 kHz), that octave is rebuilt, faintly, from the one below.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("air") else null),
        ) {
            SliderField("Amount", pct(fx.air), fx.air.toFloat()) { v -> set { it.copy(air = v.toDouble()) } }
        }

        Group("Space")
        EffectCard(
            "Stage",
            when (fx.stage) {
                StageMode.Off -> "A wider mix, with voices left in the middle"
                StageMode.Natural -> "A touch wider, from the treble"
                StageMode.Front -> "Voices forward, a little narrower"
                StageMode.Wide -> "Wide open, the deepest bass centred"
            },
            expanded = open == "stage", onExpand = { expand("stage") },
            on = fx.stage != StageMode.Off, mode = fx.stage.label,
            about = "Only what differs between your ears is widened, so whatever's in the middle (usually the voice) stays " +
                "exactly there. Each adds a gentle smile of low end and air, and nothing to the mids.",
            notes = listOfNotNull(if (ctx.direct) FxAvailability.directNote("stage") else null),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageMode.entries.forEach { m -> Chip(m.label, fx.stage == m, onClick = { set { it.copy(stage = m) } }, on = Surface.Card) }
            }
        }
        Gap(8.dp)
        val crossRest = FxAvailability.crossfeed(fx, ctx)
        EffectCard(
            "Crossfeed",
            when {
                fx.crossfeedOn && crossRest != null -> if (ctx.direct) "Resting · needs Capture" else "Resting · for headphones"
                fx.crossfeedOn -> "Strength ${pct(fx.crossfeed)}"
                else -> "Headphones that sound like speakers"
            },
            expanded = open == "crossfeed", onExpand = { expand("crossfeed") },
            on = fx.crossfeedOn, onToggle = { set { it.copy(crossfeedOn = !it.crossfeedOn) } },
            resting = fx.crossfeedOn && crossRest != null,
            about = "On headphones each ear hears only its own side, so hard-panned sounds sit inside your head. Crossfeed lets " +
                "each ear hear a little of the other, softened and a fraction late, as it would from speakers in front of you. " +
                "Easier on long listens, and anything in the middle is left exactly as it was.",
            notes = listOfNotNull(crossRest),
        ) {
            SliderField("Strength", db1(-14.0 + 9.5 * fx.crossfeed) + " across", fx.crossfeed.toFloat()) { v -> set { it.copy(crossfeed = v.toDouble()) } }
        }

        Group("Level")
        EffectCard(
            "Dynamics",
            when (fx.dynamics) {
                DynamicsMode.Off -> "Pure peaks, or squashed masters restored"
                DynamicsMode.Pure -> "The lightest touch on peaks"
                DynamicsMode.Restore -> "Squashed masters opened up"
            },
            expanded = open == "dynamics", onExpand = { expand("dynamics") },
            on = fx.dynamics != DynamicsMode.Off, mode = fx.dynamics.label,
            about = "Pure: peaks are rounded by a soft shoulder rather than stopped at a wall, and nothing else squeezes the " +
                "music (Tube's glue included). Restore: for loud, flattened masters, quiet passages are let back down a little " +
                "and the top opened up.",
            notes = emptyList(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DynamicsMode.entries.forEach { m -> Chip(m.label, fx.dynamics == m, onClick = { set { it.copy(dynamics = m) } }, on = Surface.Card) }
            }
        }
        Gap(8.dp)
        val phonNow = Loudness.phonAt(fx.loudnessRef, ctx.volumeDb)
        val bassNow = Loudness.compensationDb(50.0, phonNow, fx.loudnessRef, fx.loudness).coerceAtMost(15.0)
        EffectCard(
            "Loudness",
            if (fx.loudnessOn) (if (bassNow >= 0.5) "Now ${db1(bassNow)} at 50 Hz" else "Resting at this volume") else "Full bass and treble at low volume",
            expanded = open == "loudness", onExpand = { expand("loudness") },
            on = fx.loudnessOn, onToggle = { set { it.copy(loudnessOn = !it.loudnessOn) } },
            about = "As the volume comes down, hearing loses bass and treble faster than the middle, so a mix balanced loud " +
                "sounds thin when quiet. Loudness follows your volume and puts back what's lost, by the ISO 226 equal-loudness " +
                "contours. At full volume it does nothing.",
            notes = emptyList(),
        ) {
            SliderField("Amount", pct(fx.loudness), fx.loudness.toFloat()) { v -> set { it.copy(loudness = v.toDouble()) } }
            val span = FxSettings.MAX_REF - FxSettings.MIN_REF
            SliderField("Full volume is", "${fx.loudnessRef.roundToInt()} phon", ((fx.loudnessRef - FxSettings.MIN_REF) / span).toFloat()) { v ->
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
            expanded = open == "bt", onExpand = { expand("bt") },
            on = fx.bluetoothCare, onToggle = { set { it.copy(bluetoothCare = !it.bluetoothCare) } },
            resting = fx.bluetoothCare && btRest != null,
            about = "Bluetooth's basic codec (SBC) shares a fixed budget of bits across the spectrum, every few milliseconds. " +
                "Easing off the top octave, which few can hear, leaves more for the music below and steadies the swishy " +
                "cymbals it's known for. AAC, aptX and LDAC barely notice.",
            notes = listOfNotNull(btRest),
        ) {}

        Gap(30.dp)
        BackupSection(actions)
    }
}

@Composable
private fun Group(title: String) {
    Gap(28.dp)
    Txt(title, 21.sp, LocalPalette.current.text, bold = true)
    Gap(10.dp)
}

/** What's being listened on, so effects fit it. */
@Composable
private fun ListeningOn(name: String, phone: Boolean, current: Listener, onPick: (Listener) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(if (phone || current == Listener.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(p.text),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Txt("Listening on", 13.sp, p.sub, maxLines = 1)
                Txt(name, 16.sp, p.text, bold = true, maxLines = 1)
            }
        }
        if (phone) {
            Txt(
                "Effects keep to what the phone's speaker can play. Speaker bass is made for it.",
                13.sp, p.sub, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
            )
        } else {
            Gap(10.dp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Listener.entries.filter { it != Listener.PhoneSpeaker }.forEach { l ->
                    Chip(l.label, current == l, onClick = { onPick(l) }, on = Surface.Card)
                }
            }
            Txt(
                "Bass and treble effects ask no more than this kind of driver can give, and crossfeed is for headphones.",
                13.sp, p.sub, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            )
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .animateContentSize(Motion.flow()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onExpand)
                .padding(start = 18.dp, end = if (onToggle != null) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title, 17.sp, p.text, bold = on, maxLines = 1)
                Txt(summary, 14.sp, if (on && !resting) p.text else p.sub, maxLines = 2)
            }
            if (onToggle != null) {
                OnOffDot(on, R.drawable.ic_check, title, onToggle)
            } else if (mode != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) p.primary else p.ground)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(mode, 13.sp, if (on) p.onPrimary else p.sub, bold = true, maxLines = 1)
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                Txt(about, 14.sp, p.sub)
                notes.forEach { n ->
                    Gap(6.dp)
                    Txt(n, 14.sp, p.text)
                }
                Gap(10.dp)
                content()
            }
        }
    }
}
