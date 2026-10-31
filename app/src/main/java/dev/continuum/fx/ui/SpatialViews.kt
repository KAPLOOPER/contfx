package dev.continuum.fx.ui

import dev.continuum.fx.ui.theme.Motion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.HeardApps
import dev.continuum.fx.audio.SpatialStore
import dev.continuum.fx.dsp.HeadModel
import dev.continuum.fx.dsp.HeadSize
import dev.continuum.fx.dsp.RoomKind
import dev.continuum.fx.dsp.SpatialLayout
import dev.continuum.fx.dsp.SpatialLibrary
import dev.continuum.fx.dsp.SpatialSetup
import dev.continuum.fx.dsp.Speaker
import dev.continuum.fx.dsp.SpeakerRole
import dev.continuum.fx.island.ui.InkFeel
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** "30° left", "ahead", "behind". */
internal fun angleText(a: Float): String {
    val r = a.roundToInt()
    return when {
        r == 0 -> "ahead"
        abs(r) >= 180 -> "behind"
        r < 0 -> "${-r}° left"
        else -> "$r° right"
    }
}

private fun dbText(x: Float): String {
    val r = (x * 2).roundToInt() / 2f
    val s = if (r % 1f == 0f) r.toInt().toString() else r.toString()
    return if (r > 0) "+$s dB" else if (r < 0) "−${s.removePrefix("-")} dB" else "0 dB"
}

/** The other half of a pair, mirrored across the middle. */
private fun SpeakerRole.partner(): SpeakerRole? = when (this) {
    SpeakerRole.FrontLeft -> SpeakerRole.FrontRight
    SpeakerRole.FrontRight -> SpeakerRole.FrontLeft
    SpeakerRole.SideLeft -> SpeakerRole.SideRight
    SpeakerRole.SideRight -> SpeakerRole.SideLeft
    SpeakerRole.BackLeft -> SpeakerRole.BackRight
    SpeakerRole.BackRight -> SpeakerRole.BackLeft
    SpeakerRole.Centre -> null
}

/** [s] put into [list], with its partner mirrored when [mirror] is on. */
private fun place(list: List<Speaker>, s: Speaker, mirror: Boolean): List<Speaker> {
    val partner = if (mirror) s.role.partner() else null
    return list.map {
        when (it.role) {
            s.role -> s
            partner -> it.copy(angle = -s.angle, height = s.height, distance = s.distance, levelDb = s.levelDb)
            else -> it
        }
    }
}

/** The summary under Spatial audio's name. */
internal fun spatialSummary(lib: SpatialLibrary, playingName: String?, resting: String?): String = when {
    !lib.enabled -> "Speakers around you, in a room you choose"
    resting != null -> resting
    playingName != null -> "Now through $playingName · tap to place the speakers"
    else -> "${lib.default.name} · tap to place the speakers"
}

/**
 * Inside the Spatial audio card: the setups, a map of the speakers around your head to drag
 * them about on, the room and the finer points, and which apps use which setup.
 */
@Composable
internal fun SpatialContent() {
    val p = LocalPalette.current
    val lib by SpatialStore.state.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = lib.byId(editingId) ?: lib.default
    val showingOff = editingId == SpatialLibrary.OFF

    Txt("Setups", 15.sp, p.sub)
    Spacer(Modifier.size(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lib.setups.forEach { s ->
            Chip(s.name, !showingOff && s.id == editing.id, onClick = { editingId = s.id }, on = Surface.Card)
        }
        Chip("Off for…", showingOff, onClick = { editingId = SpatialLibrary.OFF }, on = Surface.Card)
        Chip("+ New", false, onClick = { editingId = SpatialStore.duplicate(editing) }, on = Surface.Card)
    }
    Spacer(Modifier.size(12.dp))

    if (showingOff) {
        Txt("Apps that play exactly as they are, with spatial audio left out.", 14.sp, p.sub)
        Spacer(Modifier.size(4.dp))
        AppLinks(lib, SpatialLibrary.OFF, "Spatial off")
        return
    }

    SetupEditor(editing)
    Spacer(Modifier.size(18.dp))
    SetupHeader(lib, editing)
    Spacer(Modifier.size(14.dp))
    Txt("Apps", 15.sp, p.sub)
    AppLinks(lib, editing.id, editing.name)
}

@Composable
private fun SetupHeader(lib: SpatialLibrary, s: SpatialSetup) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var name by remember(s.id) { mutableStateOf(s.name) }
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(32) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = Libron, fontSize = 21.sp, color = p.text),
        cursorBrush = SolidColor(p.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            val n = name.trim()
            if (n.isNotEmpty()) SpatialStore.updateSetup(s.id) { it.copy(name = n) } else name = s.name
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth(),
    )
    Txt(
        if (s.id == lib.defaultId) "The default: for every app without its own. Tap the name to rename it."
        else "Tap the name to rename it.",
        13.sp, p.sub,
    )
    Spacer(Modifier.size(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.id != lib.defaultId) {
            PillButton("Make default", { SpatialStore.update { it.copy(defaultId = s.id) } }, icon = R.drawable.ic_check, height = 36.dp, on = Surface.Card)
        }
        if (lib.setups.size > 1) {
            PillButton("Delete", { SpatialStore.delete(s.id) }, icon = R.drawable.ic_trash, height = 36.dp, on = Surface.Card)
        }
    }
}

@Composable
private fun SetupEditor(s: SpatialSetup) {
    val p = LocalPalette.current
    fun set(transform: (SpatialSetup) -> SpatialSetup) = SpatialStore.updateSetup(s.id, transform)
    var selected by remember(s.id) { mutableStateOf<SpeakerRole?>(SpeakerRole.FrontLeft) }
    var mirror by rememberSaveable { mutableStateOf(true) }

    Txt("Hold a speaker and drag it where you like it: round you, or in and out.", 14.sp, p.text)
    Spacer(Modifier.size(10.dp))
    SpeakerMap(s, selected, mirror, onSelect = { selected = it }) { next -> set { it.copy(speakers = next) } }
    Spacer(Modifier.size(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt("Nearer the middle is closer.", 13.sp, p.sub, modifier = Modifier.weight(1f))
        Chip(if (mirror) "Pairs mirrored" else "Pairs free", mirror, onClick = { mirror = !mirror }, on = Surface.Card)
    }

    val sp = s.speakers.firstOrNull { it.role == selected }
    if (sp != null) {
        Spacer(Modifier.size(10.dp))
        Txt("${sp.role.label} · ${angleText(sp.angle)} · ${"%.1f".format(sp.distance)} m", 17.sp, p.text)
        fun change(next: Speaker) = set { it.copy(speakers = place(it.speakers, next, mirror)) }
        val hSpan = SpatialSetup.MAX_HEIGHT - SpatialSetup.MIN_HEIGHT
        SliderField("Height", if (sp.height.roundToInt() == 0) "ear level" else "${sp.height.roundToInt()}° ${if (sp.height > 0) "up" else "down"}", (sp.height - SpatialSetup.MIN_HEIGHT) / hSpan) { v ->
            change(sp.copy(height = ((SpatialSetup.MIN_HEIGHT + v * hSpan) / 5f).roundToInt() * 5f))
        }
        SliderField("Distance", "%.1f m".format(sp.distance), distanceToFraction(sp.distance)) { v ->
            change(sp.copy(distance = (fractionToDistance(v) * 10).roundToInt() / 10f))
        }
        SliderField("Level", dbText(sp.levelDb), (sp.levelDb + 12f) / 18f) { v ->
            change(sp.copy(levelDb = ((-12f + v * 18f) * 2).roundToInt() / 2f))
        }
    }

    Spacer(Modifier.size(14.dp))
    Txt("Layout", 15.sp, p.sub)
    Spacer(Modifier.size(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpatialLayout.entries.forEach { l -> Chip(l.label, s.layout == l, onClick = { set { it.withLayout(l) } }, on = Surface.Card) }
    }
    if (s.layout != SpatialLayout.Stereo) {
        Spacer(Modifier.size(6.dp))
        Txt(
            "Android mixes surround down to stereo before any app can capture it, so the centre and surrounds are drawn back out of the mix: clearly placed sounds (a lead voice) to the speakers either side of where they sit, the space around them to the surrounds. Anything only partly shared stays left and right, so the front stays wide.",
            13.sp, p.sub,
        )
    }

    Spacer(Modifier.size(14.dp))
    Txt("Width", 15.sp, p.sub)
    SliderField("Space kept wide", "${(s.space * 100).roundToInt()}%", s.space) { v -> set { it.copy(space = (v * 20).roundToInt() / 20f) } }
    Txt(
        "Speakers in front of you narrow a mix: each ear hears both. This much of its space (reverb, ambience, everything that differs between the channels) goes straight to your ears instead, as wide as plain headphones, while voices and instruments still come from the speakers.",
        13.sp, p.sub,
    )

    Spacer(Modifier.size(14.dp))
    Txt("Room", 15.sp, p.sub)
    Spacer(Modifier.size(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoomKind.entries.forEach { r -> Chip(r.label, s.room == r, onClick = { set { it.copy(room = r) } }, on = Surface.Card) }
    }
    Spacer(Modifier.size(4.dp))
    Txt(
        when (s.room) {
            RoomKind.None -> "Just the speakers. Clean, but the sound sits closer to your head."
            RoomKind.Studio -> "A treated mixing room: a few soft reflections place the speakers out in front, with almost no echo."
            RoomKind.Living -> "A furnished room: a little more of the room, still dry."
            RoomKind.Cinema -> "A large, damped room: the space of a film, without a hall's echo."
        },
        13.sp, p.sub,
    )
    if (s.room != RoomKind.None) {
        SliderField("Reflections", "${(s.reflections * 100).roundToInt()}%", s.reflections) { v -> set { it.copy(reflections = (v * 20).roundToInt() / 20f) } }
    }
    SliderField("Tail", s.tailDb?.let { dbText(it) } ?: "Off", s.tail) { v -> set { it.copy(tail = (v * 20).roundToInt() / 20f) } }
    if (s.tailDb != null) {
        SliderField("Decay", "%.2f s".format(s.decay), (s.decay - 0.1f) / 1.4f) { v -> set { it.copy(decay = ((0.1f + v * 1.4f) * 20).roundToInt() / 20f) } }
    }
    Txt("The tail is the room's echo after the reflections. Leave it low for a dry, close sound.", 13.sp, p.sub)

    if (s.layout != SpatialLayout.Stereo) {
        Spacer(Modifier.size(14.dp))
        Txt("Mix", 15.sp, p.sub)
        SliderField("Front width", "${(s.width * 100).roundToInt()}%", (s.width - 0.5f)) { v -> set { it.copy(width = ((0.5f + v) * 20).roundToInt() / 20f) } }
        SliderField("Centre", dbText(s.centreDb), (s.centreDb + 12f) / 21f) { v -> set { it.copy(centreDb = ((-12f + v * 21f) * 2).roundToInt() / 2f) } }
        SliderField("Surrounds", dbText(s.surroundDb), (s.surroundDb + 12f) / 21f) { v -> set { it.copy(surroundDb = ((-12f + v * 21f) * 2).roundToInt() / 2f) } }
        Txt("Centre lifts voices in films; surrounds set how much of the space comes from around and behind.", 13.sp, p.sub)
    }

    Spacer(Modifier.size(14.dp))
    Txt("Bass stays dry below", 15.sp, p.sub)
    Spacer(Modifier.size(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 80, 120, 200).forEach { hz ->
            Chip(if (hz == 0) "Off" else "$hz Hz", s.bassHz == hz, onClick = { set { it.copy(bassHz = hz) } }, on = Surface.Card)
        }
    }
    Spacer(Modifier.size(4.dp))
    Txt("Below this the sound skips the room and the head, so the bass stays as tight and full as your EQ made it.", 13.sp, p.sub)

    Spacer(Modifier.size(14.dp))
    Txt("Head", 15.sp, p.sub)
    Spacer(Modifier.size(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HeadModel.entries.forEach { h -> Chip(h.label, s.head == h, onClick = { set { it.copy(head = h) } }, on = Surface.Card) }
    }
    Spacer(Modifier.size(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HeadSize.entries.forEach { h -> Chip(h.label, s.headSize == h, onClick = { set { it.copy(headSize = h) } }, on = Surface.Card) }
    }
    Spacer(Modifier.size(4.dp))
    Txt(
        "Two measured dummy heads (from the University of York's SADIE II). Heads differ: try both and keep whichever puts the speakers furthest out. Size sets how far sounds swing as they move round.",
        13.sp, p.sub,
    )
}

private fun distanceToFraction(d: Float) =
    sqrt(((d - SpatialSetup.MIN_DISTANCE) / (SpatialSetup.MAX_DISTANCE - SpatialSetup.MIN_DISTANCE)).coerceIn(0f, 1f))

private fun fractionToDistance(f: Float) =
    SpatialSetup.MIN_DISTANCE + (SpatialSetup.MAX_DISTANCE - SpatialSetup.MIN_DISTANCE) * f * f

/**
 * You, from above, with the speakers around you as drops of ink. Drag one round to move it, in
 * or out to bring it closer or send it further; a light tick every 15 degrees.
 */
@Composable
private fun SpeakerMap(
    s: SpatialSetup,
    selected: SpeakerRole?,
    mirror: Boolean,
    onSelect: (SpeakerRole) -> Unit,
    onChange: (List<Speaker>) -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val density = LocalDensity.current
    // One state per setup, so the drag below always moves what's drawn; it follows the stored
    // speakers whenever you're not mid-drag.
    var live by remember(s.id) { mutableStateOf(s.speakers) }
    var dragging by remember { mutableStateOf<SpeakerRole?>(null) }
    LaunchedEffect(s.speakers) { if (dragging == null) live = s.speakers }
    var lastPush by remember { mutableLongStateOf(0L) }
    val latestChange by rememberUpdatedState(onChange)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestMirror by rememberUpdatedState(mirror)

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 340.dp)
        val sidePx = with(density) { side.toPx() }
        val dropPx = with(density) { 30.dp.toPx() }
        fun radiusFor(d: Float) = sidePx / 2f * (0.26f + 0.68f * distanceToFraction(d))
        fun position(sp: Speaker): Offset {
            val r = radiusFor(sp.distance)
            val a = Math.toRadians(sp.angle.toDouble())
            return Offset(sidePx / 2f + (r * sin(a)).toFloat(), sidePx / 2f - (r * cos(a)).toFloat())
        }
        fun nearest(at: Offset): Speaker? = live.minByOrNull { (position(it) - at).getDistance() }
            ?.takeIf { (position(it) - at).getDistance() < dropPx * 1.4f }

        Box(
            Modifier
                .size(side)
                .pointerInput(s.id, s.layout, sidePx) {
                    // A speaker is picked up only when the touch starts on it: anywhere else on
                    // the map, the page scrolls as usual.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val hit = nearest(down.position) ?: return@awaitEachGesture
                        latestSelect(hit.role)
                        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        dragging = hit.role
                        // The drop lifts off the floor.
                        InkFeel.split(view)
                        fun moveTo(at: Offset) {
                            val sp = live.first { it.role == hit.role }
                            val v = at - Offset(sidePx / 2f, sidePx / 2f)
                            var angle = Math.toDegrees(atan2(v.x.toDouble(), -v.y.toDouble())).toFloat()
                            angle = (angle / 5f).roundToInt() * 5f
                            if (latestMirror) {
                                angle = when {
                                    hit.role == SpeakerRole.Centre -> 0f
                                    hit.role.left == true -> angle.coerceIn(-175f, -5f)
                                    hit.role.left == false -> angle.coerceIn(5f, 175f)
                                    else -> angle
                                }
                            }
                            val f = ((hypot(v.x, v.y) / (sidePx / 2f) - 0.26f) / 0.68f).coerceIn(0f, 1f)
                            val distance = (fractionToDistance(f) * 10).roundToInt() / 10f
                            if ((angle / 15f).roundToInt() != (sp.angle / 15f).roundToInt()) InkFeel.detent(view)
                            live = place(live, sp.copy(angle = angle, distance = distance), latestMirror)
                            // Heard while dragging, a few times a second; settled when you let go.
                            val now = System.currentTimeMillis()
                            if (now - lastPush > 140) {
                                lastPush = now
                                latestChange(live)
                            }
                        }
                        moveTo(start.position)
                        val finished = drag(start.id) { change ->
                            change.consume()
                            moveTo(change.position)
                        }
                        latestChange(live)
                        if (finished) InkFeel.merge(view)
                        dragging = null
                    }
                },
        ) {
            Canvas(Modifier.size(side)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val stroke = Stroke(width = 1.5.dp.toPx())
                for (d in listOf(1f, 2f, 4f)) drawCircle(p.ground, radiusFor(d), c, style = stroke)
                // You: a head, and a drop for the nose, facing the front.
                drawCircle(p.sub, 13.dp.toPx(), c)
                drawCircle(p.sub, 4.5.dp.toPx(), c + Offset(0f, -14.dp.toPx()))
                // Each speaker's line to you, faint.
                for (sp in live) drawLine(p.ground, c, position(sp), strokeWidth = 1.dp.toPx())
            }
            live.forEach { sp ->
                key(sp.role) {
                    val at = position(sp)
                    val on = sp.role == selected
                    // Held, the drop swells under your finger, so it shows past it.
                    val swell by animateFloatAsState(if (dragging == sp.role) 1.45f else 1f, Motion.flow(), label = "swell")
                    Box(
                        Modifier
                            .offset { IntOffset((at.x - dropPx / 2f).roundToInt(), (at.y - dropPx / 2f).roundToInt()) }
                            .size(30.dp)
                            .graphicsLayer {
                                scaleX = swell
                                scaleY = swell
                            }
                            .clip(CircleShape)
                            .background(if (on) p.primary else p.text),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(sp.role.short, 12.sp, if (on) p.onPrimary else p.ground, bold = true, maxLines = 1)
                    }
                }
            }
            // Where the held speaker is, as you move it.
            live.firstOrNull { it.role == dragging }?.let { sp ->
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(50))
                        .background(p.ground)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Txt("${sp.role.short} · ${angleText(sp.angle)} · ${"%.1f".format(sp.distance)} m", 13.sp, p.text, maxLines = 1)
                }
            }
        }
    }
}

/** Which apps use [id] (a setup, or [SpatialLibrary.OFF]). */
@Composable
private fun AppLinks(lib: SpatialLibrary, id: String, what: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val heard by HeardApps.apps.collectAsState()
    val playing by EngineStatus.processingPackages.collectAsState()
    val list = (playing + heard + lib.apps.keys).distinct()
    if (list.isEmpty()) {
        Txt("Apps show up here once they've played something through the EQ.", 14.sp, p.sub)
        return
    }
    Column {
        list.forEach { pkg ->
            key(pkg) {
                val own = lib.apps[pkg]
                val on = own == id
                val name = remember(pkg) { appLabel(context, pkg) }
                val source = remember { MutableInteractionSource() }
                val toggle = {
                    SpatialStore.update { l -> l.copy(apps = if (on) l.apps - pkg else l.apps + (pkg to id)) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = toggle)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(name, 16.sp, p.text, maxLines = 1)
                        Txt(
                            when {
                                own == SpatialLibrary.OFF -> "Spatial off"
                                own != null -> "Uses ${lib.byId(own)?.name ?: "its own"}"
                                else -> "Uses the default, ${lib.default.name}"
                            },
                            13.sp, p.sub, maxLines = 1,
                        )
                    }
                    OnOffDot(on, R.drawable.ic_check, "$name: $what", toggle)
                }
            }
        }
    }
}
