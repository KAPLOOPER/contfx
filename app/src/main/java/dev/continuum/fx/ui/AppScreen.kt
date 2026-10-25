package dev.continuum.fx.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.data.Library
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.dsp.EqBand
import dev.continuum.fx.dsp.EqLimits
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.dsp.FilterType
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.update.UpdateState
import dev.continuum.fx.setup.PrivilegeState
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.EqGraph
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.OnOffDot
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Section { Sound, Island, Volume, Keyboard }

data class UiState(
    val library: Library,
    val running: Boolean,
    val error: String?,
    val processing: List<String>,
    val blocked: List<String>,
    val privileges: PrivilegeState,
    val setupOpen: Boolean,
    val message: String?,
    val section: Section = Section.Sound,
    val island: IslandPrefs = IslandPrefs(),
    /** The island is attached and drawing right now. */
    val islandLive: Boolean = false,
    /** The island's notification listener is connected right now. */
    val islandFed: Boolean = false,
    val keyboard: KeyboardStatus = KeyboardStatus(),
    val update: UpdateState = UpdateState.Idle,
    val build: Int = 1,
)

interface AppActions {
    fun togglePower()
    fun requestRecord()
    fun requestNotifications()
    fun grantWithShizuku()
    fun copyAdbCommands()
    fun importFile()
    fun pasteFromClipboard()
    fun shareActive()
    fun setSetupOpen(open: Boolean)
    fun dismissMessage()
    fun setSection(section: Section)
    fun toggleIsland()
    fun updateIsland(transform: (IslandPrefs) -> IslandPrefs)
    fun grantIslandWithShizuku()
    fun grantPopupsWithShizuku()
    /** Asks for calendar access, then turns the island's Calendar events on. */
    fun requestCalendar()
    fun toggleVolume()
    fun updateVolume(transform: (dev.continuum.fx.volume.VolumePrefs) -> dev.continuum.fx.volume.VolumePrefs)
    fun grantVolumeWithShizuku()
    fun connectVolumeHelper()
    fun showVolumePanel(expanded: Boolean)
    fun setPlaysAlongside(pkg: String, on: Boolean)
    fun openAccessibilitySettings()
    fun openListenerSettings()
    fun openAppInfo()
    fun copyIslandCommands()
    fun installKeyboard()
    fun dismissKeyboardUpdate()
    fun enableKeyboard()
    fun chooseKeyboard()
    fun openKeyboardSettings(page: String?)
    fun checkForUpdates()
    fun installUpdate()
    fun allowInstalls()
    fun dismissUpdate()
}

@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun AppScreen(state: UiState, store: ProfileStore, actions: AppActions) {
    val p = LocalPalette.current
    BackHandler(enabled = state.setupOpen) { actions.setSetupOpen(false) }

    val sections = Section.entries
    val pivot = remember { PivotState(state.section.ordinal, sections.size) }
    val scope = rememberCoroutineScope()
    // Each page keeps its own place, as pivot pages do.
    val scrolls = remember { sections.associateWith { ScrollState(0) } }

    // The page on screen, always current: the swipe handler is made once and must not hold on
    // to the page that was open when it was.
    val shown by rememberUpdatedState(state.section)

    // The titles always settle on the page that's showing. A swipe or a tap sets both at once;
    // a page opened from elsewhere (a tile, a notification) brings the titles to it by the
    // shortest way round. Should they ever disagree for longer than a page change takes, the
    // page on screen wins.
    val lastSection = remember { arrayOf(state.section) }
    LaunchedEffect(state.section, pivot.contentSlot) {
        val opened = state.section != lastSection[0]
        lastSection[0] = state.section
        if (pivot.indexOf(pivot.contentSlot) != state.section.ordinal) {
            // A swipe or tap moved the titles and the page is on its way: give it a moment.
            // A page opened from elsewhere is followed straight away.
            if (!opened) delay(250)
            pivot.contentSlot = pivot.nearest(state.section.ordinal, pivot.contentSlot)
        }
        // Carries on with whatever speed a fling left it.
        pivot.slot.animateTo(pivot.contentSlot.toFloat(), Motion.flow())
    }
    fun go(slot: Int, velocity: Float = 0f) {
        val section = sections[pivot.indexOf(slot)]
        pivot.contentSlot = slot
        if (section != shown) actions.setSection(section)
        scope.launch { pivot.slot.animateTo(slot.toFloat(), Motion.flow(), initialVelocity = velocity) }
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
        Column(Modifier.fillMaxSize()) {
            PivotHeader(
                titles = sections.map { it.name.lowercase() },
                pivot = pivot,
                onSelect = { slot ->
                    if (slot == pivot.contentSlot) {
                        // The current title takes its page back to the top.
                        scope.launch { scrolls.getValue(shown).animateScrollTo(0) }
                    } else {
                        go(slot)
                    }
                },
                modifier = Modifier.statusBarsPadding().padding(top = 18.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        // Swiping the page sideways turns the pivot, the titles following the finger.
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                scope.launch { pivot.slot.stop() }
                            },
                            onDragEnd = {
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val v = tracker.calculateVelocity().x
                                val from = pivot.contentSlot
                                val target = when {
                                    v < -FLING -> from + 1
                                    v > FLING -> from - 1
                                    else -> pivot.slot.value.roundToInt().coerceIn(from - 1, from + 1)
                                }
                                go(target, -v / width)
                            },
                            onDragCancel = { go(pivot.contentSlot) },
                        ) { change, dx ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val from = pivot.contentSlot.toFloat()
                            scope.launch { pivot.slot.snapTo((pivot.slot.value - dx / width).coerceIn(from - 1f, from + 1f)) }
                        }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // The page slides with the titles and gives way to the next one halfway.
                            // Measured from this page's own slot, so in the frame between a swipe
                            // and the page changing, the old page doesn't jump.
                            val cs = pivot.contentSlot
                            val own = if (pivot.indexOf(cs) == state.section.ordinal) cs
                            else pivot.nearest(state.section.ordinal, pivot.slot.value.roundToInt())
                            val shift = own - pivot.slot.value
                            translationX = shift * size.width * 0.55f
                            alpha = (1f - abs(shift) * 1.3f).coerceIn(0f, 1f)
                        }
                        .verticalScroll(scrolls.getValue(state.section))
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 120.dp),
                ) {
                    UpdateCard(
                        update = state.update,
                        title = { "Build ${it.build} is ready" },
                        subtitle = "Your settings and permissions stay as they are.",
                        actionLabel = "Update now",
                        installingNote = "Installing. Continuum FX closes for a moment while it updates, then it's back as it was.",
                        onAction = actions::installUpdate,
                        onAllow = actions::allowInstalls,
                        onDismiss = actions::dismissUpdate,
                    )
                    when (state.section) {
                        Section.Sound -> Column {
                            StatusLine(state)
                            Gap(20.dp)
                            SoundContent(state, store, actions)
                        }
                        Section.Island -> IslandContent(state, actions)
                        Section.Volume -> VolumeContent(state, actions)
                        Section.Keyboard -> KeyboardContent(state, actions)
                    }
                    Gap(30.dp)
                    UpdateFooter(state, actions)
                }
                // Pages dissolve into the ground under the titles rather than being cut off.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(Brush.verticalGradient(listOf(p.ground, p.ground.copy(alpha = 0f)))),
                )
            }
        }

        MessagePill(state.message, actions::dismissMessage, Modifier.align(Alignment.BottomCenter))
        Dock(state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SoundContent(state: UiState, store: ProfileStore, actions: AppActions) {
    val p = LocalPalette.current
    val library = state.library
    val profile = library.active
    var selected by remember(profile.id) { mutableIntStateOf(-1) }
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            library.profiles.forEach { pr ->
                Chip(pr.name, pr.id == library.activeId, onClick = { store.select(pr.id) })
            }
            PillButton("Import", actions::importFile, icon = R.drawable.ic_import, height = 38.dp)
            PillButton("Paste", actions::pasteFromClipboard, icon = R.drawable.ic_paste, height = 38.dp)
        }
        val output by OutputWatcher.current.collectAsState()
        output?.let { device ->
            Gap(12.dp)
            OutputRow(device, library, onLink = { on -> store.link(device.key, if (on) profile.id else null) })
        }
        Gap(16.dp)

        val comparing by EngineStatus.comparing.collectAsState()
        EqGraph(
            profile = profile,
            live = state.running,
            comparing = comparing && state.running && library.eqEnabled,
            selected = selected,
            onSelect = { selected = it },
            onMove = { i, f, g ->
                store.updateActive { pr ->
                    pr.copy(bands = pr.bands.mapIndexed { j, b ->
                        if (j != i) b else b.copy(
                            frequency = Fmt.roundFreq(f),
                            gainDb = if (b.type.usesGain) Fmt.round1(g.coerceIn(EqLimits.MIN_GAIN, EqLimits.MAX_GAIN)) else b.gainDb,
                        )
                    })
                }
            },
        )
        Gap(18.dp)

        PreampSection(
            preampDb = profile.preampDb,
            eqEnabled = library.eqEnabled,
            canCompare = state.running && library.eqEnabled,
            onPreamp = { v -> store.updateActive { it.copy(preampDb = v) } },
            onAuto = { store.updateActive { it.copy(preampDb = EqResponse.suggestedPreamp(it)) } },
            onToggleEq = { store.setEqEnabled(!library.eqEnabled) },
        )
        Gap(26.dp)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Filters", 21.sp, p.text, bold = true)
            Spacer(Modifier.width(8.dp))
            Txt("${profile.bands.size}", 17.sp, p.sub, modifier = Modifier.weight(1f))
            IconBubble(R.drawable.ic_plus, "Add filter", onClick = {
                val newIndex = profile.bands.size
                store.updateActive { it.copy(bands = it.bands + EqBand(FilterType.PEAK, 1000.0, 0.0, 1.0)) }
                selected = newIndex
            })
        }
        Gap(8.dp)
        if (profile.bands.isEmpty()) {
            Txt("No filters yet. Add one, or import an EQ exported from squig.link.", 16.sp, p.sub)
        }
        profile.bands.forEachIndexed { i, band ->
            BandCard(
                band = band,
                expanded = selected == i,
                onToggle = { selected = if (selected == i) -1 else i },
                onChange = { nb ->
                    store.updateActive { pr -> pr.copy(bands = pr.bands.mapIndexed { j, b -> if (j == i) nb else b }) }
                },
                onRemove = {
                    store.updateActive { pr -> pr.copy(bands = pr.bands.filterIndexed { j, _ -> j != i }) }
                    selected = -1
                },
            )
            Gap(8.dp)
        }
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Share", actions::shareActive, icon = R.drawable.ic_share)
            PillButton("Delete", { store.delete(profile.id) }, icon = R.drawable.ic_trash)
        }
        if (state.blocked.isNotEmpty()) {
            Gap(18.dp)
            val names = state.blocked.joinToString()
            val one = state.blocked.size == 1
            Txt(
                "$names ${if (one) "blocks" else "block"} audio capture, so ${if (one) "it plays" else "they play"} without effects.",
                15.sp, p.sub,
            )
        }
    }
}

/** Where sound is going, and whether this EQ comes on by itself there. */
@Composable
private fun OutputRow(device: OutputDevice, library: Library, onLink: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val profile = library.active
    val linked = library.devices[device.key] == profile.id
    val other = library.profileFor(device.key)?.takeIf { it.id != profile.id }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(if (device.kind == OutputDevice.Kind.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(p.text),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Txt(device.name, 16.sp, p.text, bold = true, maxLines = 1)
            Txt(
                when {
                    linked -> "${profile.name} comes on by itself here"
                    other != null -> "${other.name} comes on by itself here"
                    else -> "Link this EQ to come on whenever you use it"
                },
                13.sp, p.sub, maxLines = 2,
            )
        }
        Chip(if (linked) "Linked" else "Link", linked, onClick = { onLink(!linked) }, on = Surface.Card)
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val p = LocalPalette.current
    val error = state.error
    val text: String = when {
        error != null -> error
        state.running && state.processing.isNotEmpty() -> "On · ${state.processing.joinToString()}"
        state.running -> "On · waiting for something to play"
        !state.privileges.ready -> "Off · finish setup to start"
        else -> "Off"
    }
    val color = when {
        error != null -> p.danger
        state.running -> p.text
        else -> p.sub
    }
    Txt(text, 17.sp, color, maxLines = 2)
}

@Composable
private fun PreampSection(
    preampDb: Double,
    eqEnabled: Boolean,
    canCompare: Boolean,
    onPreamp: (Double) -> Unit,
    onAuto: () -> Unit,
    onToggleEq: () -> Unit,
) {
    val p = LocalPalette.current
    val span = EqLimits.MAX_PREAMP - EqLimits.MIN_PREAMP
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Preamp", 17.sp, p.sub, modifier = Modifier.weight(1f))
            Txt(Fmt.db(preampDb), 17.sp, p.text)
        }
        InkSlider(
            value = ((preampDb - EqLimits.MIN_PREAMP) / span).toFloat(),
            onChange = { t -> onPreamp(Fmt.round1(EqLimits.MIN_PREAMP + t * span)) },
            track = p.surface,
            fill = p.text,
        )
        Gap(6.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(if (eqEnabled) "EQ on" else "EQ off", eqEnabled, onToggleEq)
            PillButton("Auto preamp", onAuto, height = 38.dp)
            if (canCompare) ComparePill()
        }
    }
}

/**
 * Hold to hear the music without the EQ. The loudness is matched, so the comparison is of
 * sound, not volume (the louder of two otherwise tends to sound better).
 */
@Composable
private fun ComparePill() {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val comparing by EngineStatus.comparing.collectAsState()
    DisposableEffect(Unit) { onDispose { EngineStatus.comparing.value = false } }
    val bg by animateColorAsState(if (comparing) p.primary else p.surface, Motion.flow(), label = "compareBg")
    val fg by animateColorAsState(if (comparing) p.onPrimary else p.text, Motion.flow(), label = "compareFg")
    Box(
        Modifier
            .squash(source, pill = true)
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { pos ->
                    val press = PressInteraction.Press(pos)
                    source.emit(press)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    EngineStatus.comparing.value = true
                    val lifted = tryAwaitRelease()
                    EngineStatus.comparing.value = false
                    source.emit(if (lifted) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Hold to hear without the EQ"
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt("Hold to compare", 15.sp, fg, maxLines = 1)
    }
}

@Composable
private fun BandCard(
    band: EqBand,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (EqBand) -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val textColor = if (band.enabled) p.text else p.sub
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
                .height(60.dp)
                .clickable(interactionSource = source, indication = null, onClick = onToggle)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(42.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (band.enabled) p.primary else p.line),
                contentAlignment = Alignment.Center,
            ) {
                Txt(band.type.shortLabel, 13.sp, if (band.enabled) p.onPrimary else p.text, bold = true)
            }
            Spacer(Modifier.width(12.dp))
            Txt(Fmt.freq(band.frequency), 17.sp, textColor, modifier = Modifier.weight(1f), maxLines = 1)
            if (band.type.usesGain) {
                Txt(Fmt.db(band.gainDb), 16.sp, textColor, maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            Txt("Q ${Fmt.q(band.q)}", 16.sp, p.sub, maxLines = 1)
            OnOffDot(band.enabled, R.drawable.ic_check, "Filter on") { onChange(band.copy(enabled = !band.enabled)) }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF,
                        FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.NOTCH,
                    ).forEach { t ->
                        Chip(t.label, band.type == t, onClick = { onChange(band.copy(type = t)) }, on = Surface.Card)
                    }
                }
                Gap(10.dp)
                SliderField("Frequency", Fmt.freq(band.frequency), Fmt.freqToT(band.frequency)) { t ->
                    onChange(band.copy(frequency = Fmt.roundFreq(Fmt.tToFreq(t))))
                }
                if (band.type.usesGain) {
                    val gSpan = EqLimits.MAX_GAIN - EqLimits.MIN_GAIN
                    SliderField("Gain", Fmt.db(band.gainDb), ((band.gainDb - EqLimits.MIN_GAIN) / gSpan).toFloat()) { t ->
                        onChange(band.copy(gainDb = Fmt.round1(EqLimits.MIN_GAIN + t * gSpan)))
                    }
                }
                SliderField("Q", Fmt.q(band.q), Fmt.qToT(band.q)) { t ->
                    onChange(band.copy(q = Fmt.roundQ(Fmt.tToQ(t))))
                }
                Gap(8.dp)
                PillButton("Remove filter", onRemove, icon = R.drawable.ic_trash, on = Surface.Card, height = 40.dp)
            }
        }
    }
}

@Composable
private fun SliderField(label: String, valueText: String, value: Float, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Txt(label, 15.sp, p.sub, modifier = Modifier.weight(1f))
            Txt(valueText, 15.sp, p.text)
        }
        InkSlider(value = value, onChange = onChange, track = p.ground, fill = p.text)
    }
}

@Composable
private fun MessagePill(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(3000)
            onDismiss()
        }
    }
    val t by animateFloatAsState(if (message != null) 1f else 0f, Motion.flow(), label = "message")
    val text = shown
    if (t > 0.01f && text != null) {
        Box(
            modifier
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 104.dp)
                .graphicsLayer {
                    alpha = t.coerceIn(0f, 1f)
                    translationY = (1f - t) * 80f
                    scaleX = 0.7f + 0.3f * t
                    scaleY = 0.7f + 0.3f * t
                }
                .clip(RoundedCornerShape(50))
                .background(p.ink)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Txt(text, 15.sp, p.onInk, maxLines = 3)
        }
    }
}

/**
 * The ink dock. It holds the power control, and grows straight up into the setup
 * sheet rather than opening a separate panel: the same ink, reshaped.
 */
@Composable
private fun Dock(state: UiState, actions: AppActions, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val openHeight = (screenHeight - 120).coerceIn(420, 660).dp
    val height by animateDpAsState(if (state.setupOpen) openHeight else 76.dp, Motion.flow(), label = "dockHeight")
    val side by animateDpAsState(if (state.setupOpen) 8.dp else 12.dp, Motion.flow(), label = "dockSide")
    val shape = RoundedCornerShape(38.dp)
    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = side, end = side, bottom = 10.dp)
            .fillMaxWidth()
            .height(height)
            .then(if (p.dark) Modifier.border(1.dp, p.edge, shape) else Modifier)
            .clip(shape)
            .background(p.ink),
    ) {
        Crossfade(targetState = state.setupOpen to state.section, label = "dockContent") { (open, section) ->
            when {
                open && section == Section.Island -> IslandSetupSheet(state, actions)
                open && section == Section.Volume -> VolumeSetupSheet(state, actions)
                open && section == Section.Sound -> SetupSheet(state, actions)
                section == Section.Island -> IslandDockBar(state, actions)
                section == Section.Volume -> VolumeDockBar(state, actions)
                section == Section.Keyboard -> KeyboardDockBar(state, actions)
                else -> DockBar(state, actions)
            }
        }
    }
}

@Composable
private fun DockBar(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val library = state.library
    val subtitle = when {
        !state.privileges.ready && !state.running -> "Tap to set up"
        !library.eqEnabled -> "${library.active.name} · EQ off"
        else -> library.active.name
    }
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerButton(state.running, R.drawable.ic_power, actions::togglePower)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(if (state.running) "Processing" else "Off", 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(subtitle, 14.sp, p.onInkSub, maxLines = 1)
        }
        IconBubble(R.drawable.ic_setup, "Setup", onClick = { actions.setSetupOpen(true) }, on = Surface.Ink)
    }
}

@Composable
private fun SetupSheet(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val priv = state.privileges
    val packageName = LocalContext.current.packageName
    var step = 0
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Set up capture", 24.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
            IconBubble(R.drawable.ic_chevron_down, "Close setup", onClick = { actions.setSetupOpen(false) }, on = Surface.Ink)
        }
        Gap(4.dp)
        Txt(
            "Continuum FX works without root: it captures what other apps play, processes it and plays it back.",
            15.sp, p.onInkSub,
        )
        Gap(8.dp)

        StepRow(++step, priv.recordAudio, "Hear other apps", "Lets the app capture the sound other apps play.") {
            PillButton("Allow", actions::requestRecord, primary = true, on = Surface.Ink)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            StepRow(++step, priv.notifications, "Notifications", "Shows when processing is on, with a stop button.", optional = true) {
                PillButton("Allow", actions::requestNotifications, on = Surface.Ink)
            }
        }
        StepRow(
            ++step, priv.dump, "Silence the originals",
            "Mutes each app's own sound so you only hear the processed version. Grant this once with Shizuku or from a computer.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = when (priv.shizuku) {
                    ShizukuState.READY -> "Grant with Shizuku"
                    ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                    ShizukuState.NOT_RUNNING -> "Use Shizuku"
                }
                PillButton(label, actions::grantWithShizuku, primary = true, on = Surface.Ink)
                Txt(
                    if (priv.shizuku == ShizukuState.NOT_RUNNING) "Shizuku isn't running. Start it, or run these from a computer with ADB:"
                    else "Or run these from a computer with ADB:",
                    14.sp, p.onInkSub,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.inkRaised)
                        .padding(14.dp),
                ) {
                    Txt(Privileges.adbCommands(packageName), 13.sp, p.onInk, mono = true)
                }
                PillButton("Copy commands", actions::copyAdbCommands, icon = R.drawable.ic_copy, on = Surface.Ink)
            }
        }
        StepRow(
            ++step, priv.projectMedia, "Skip the capture prompt",
            "Granted along with the step above, so processing starts without a system dialog each time.",
            optional = true,
        )
        Gap(14.dp)
        if (priv.ready) {
            PillButton(
                if (state.running) "Stop processing" else "Start processing",
                {
                    actions.setSetupOpen(false)
                    actions.togglePower()
                },
                icon = R.drawable.ic_power,
                primary = true,
                on = Surface.Ink,
            )
        }
    }
}

@Composable
internal fun StepRow(
    number: Int,
    done: Boolean,
    title: String,
    body: String,
    optional: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) p.live else p.inkTonal),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Image(painterResource(R.drawable.ic_check), contentDescription = "Done", modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(p.onLive))
            } else {
                Txt("$number", 15.sp, p.onInk, bold = true)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(if (optional) "$title · optional" else title, 18.sp, p.onInk, bold = true)
            Gap(2.dp)
            Txt(body, 15.sp, p.onInkSub)
            if (!done) {
                Gap(10.dp)
                action()
            }
        }
    }
}

/** Fling speed, in pixels a second, that turns the pivot without dragging it halfway. */
private const val FLING = 650f
