package dev.continuum.fx.ui

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.continuum.fx.audio.EngineMode
import dev.continuum.fx.audio.EngineSettings
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.FxStore
import dev.continuum.fx.audio.onNames
import dev.continuum.fx.audio.OutputDevice
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.data.Library
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.dsp.EqBand
import dev.continuum.fx.dsp.EqLimits
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.dsp.FilterType
import dev.continuum.fx.dsp.Polarity
import dev.continuum.fx.island.ui.InkFeel
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.update.UpdateState
import dev.continuum.fx.setup.PrivilegeState
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.ChoiceRow
import dev.continuum.fx.ui.components.Reveal
import dev.continuum.fx.ui.components.InkSection
import dev.continuum.fx.ui.components.rememberLiveLevels
import dev.continuum.fx.ui.theme.Note
import dev.continuum.fx.ui.bench.BenchSection
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.produceState
import dev.continuum.fx.ui.components.choice
import dev.continuum.fx.ui.components.EqGraph
import dev.continuum.fx.ui.components.IconBubble
import dev.continuum.fx.ui.components.InkSlider
import dev.continuum.fx.ui.components.InkSwitch
import dev.continuum.fx.ui.components.ActionRow
import dev.continuum.fx.ui.components.PillButton
import dev.continuum.fx.ui.components.PowerButton
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints

enum class Section { Sound, Effects, Island, Volume, Keyboard }

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
    /** The app whose equalizer button opened Continuum, until it's linked or put aside. */
    val openedFrom: String? = null,
)

interface AppActions {
    fun togglePower()
    /** Capture what apps play, or put the EQ on their own sound. */
    fun setEngineMode(mode: dev.continuum.fx.audio.EngineMode)
    fun requestRecord()
    fun requestNotifications()
    fun grantWithShizuku()
    fun copyAdbCommands()
    fun importFile()
    fun pasteFromClipboard()
    /** The Lab: choose measurement or target files to bring in (L and R files pair up). */
    fun labImport()
    /** The Lab: a curve copied as text, brought in like a file. */
    fun labPaste()
    /** Copies [text] and says [done]. */
    fun labCopy(text: String, done: String)
    fun labShare(title: String, text: String)
    /** Saves [text] as a file, named [fileName] to start with. */
    fun labSave(fileName: String, text: String)
    /** Says [text] in the dock, as the app's other messages are. */
    fun say(text: String)
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
    /** Gives the app [pkg] the EQ [profileId], or takes its own EQ away when null. */
    fun linkApp(pkg: String, profileId: String?)
    fun dismissOpenedFrom()
    fun saveBackup()
    fun restoreBackup()
}

@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun AppScreen(state: UiState, store: ProfileStore, actions: AppActions) {
    val p = LocalPalette.current
    BackHandler(enabled = state.setupOpen) { actions.setSetupOpen(false) }

    val view = LocalView.current
    val sections = Section.entries
    val pivot = remember { PivotState(state.section.ordinal, sections.size) }
    val scope = rememberCoroutineScope()
    // Each page keeps its own place, as pivot pages do.
    val scrolls = sections.associateWith { section -> key(section) { rememberScrollState() } }
    val pageState = rememberSaveableStateHolder()

    // The page on screen, always current: the swipe handler is made once and must not hold on
    // to the page that was open when it was.
    val shown by rememberUpdatedState(state.section)

    // The titles always settle on the page that's showing. A swipe or a tap sets both at once;
    // a page opened from elsewhere (a tile, a notification) brings the titles to it by the
    // shortest way round. Should they ever disagree for longer than a page change takes, the
    // page on screen wins.
    val lastSection = remember { arrayOf(state.section) }
    val drag = remember { PageDrag() }
    LaunchedEffect(state.section, pivot.contentSlot) {
        val opened = state.section != lastSection[0]
        lastSection[0] = state.section
        if (pivot.indexOf(pivot.contentSlot) != state.section.ordinal) {
            // A swipe or tap moved the titles and the page is on its way: give it a moment.
            // A page opened from elsewhere is followed straight away.
            if (!opened) delay(250)
            pivot.contentSlot = pivot.nearest(state.section.ordinal, pivot.contentSlot)
        }
        // Carries on with whatever speed a fling left it. Not under a finger: a page changed
        // halfway through a drag stays where the finger has it.
        if (!drag.on) pivot.slot.animateTo(pivot.contentSlot.toFloat(), Motion.flow())
    }
    // The page on screen is the one the titles are nearest: it changes as they pass halfway, by a
    // tap, a swipe or a fling alike. The page you're leaving slides and fades to nothing by then,
    // and the new one comes in from the other side out of nothing, so neither pops.
    fun show(slot: Int) {
        if (slot == pivot.contentSlot) return
        val section = sections[pivot.indexOf(slot)]
        pivot.contentSlot = slot
        if (section != shown) actions.setSection(section)
    }
    // The ink the rest of the app's ink comes from: the title's drop, and the dock.
    val seeds = remember { Seeds() }

    // Compose a page only when it becomes the shown page. Composing it ahead also starts
    // its effects (Volume's watchers, for example), even when it is never placed.
    fun go(slot: Int, velocity: Float = 0f) {
        scope.launch {
            pivot.slot.animateTo(slot.toFloat(), Motion.flow(), initialVelocity = velocity) {
                if (abs(value - slot) <= 0.5f) show(slot)
            }
            show(slot)
        }
    }
    // A new page is shown: whatever comes with it is simply there, not dripped in.
    remember(state.section) {
        seeds.pageAt = System.currentTimeMillis()
        0
    }
    val dockRect = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Read down a page and the pivot folds out of the way; back near the top it opens out. Two
    // marks rather than one, so a page that's only just long enough doesn't flicker between them.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val foldAt = with(density) { 56.dp.toPx() }
    val openAt = with(density) { 12.dp.toPx() }
    var folded by remember { mutableStateOf(false) }
    LaunchedEffect(state.section) {
        androidx.compose.runtime.snapshotFlow { scrolls.getValue(state.section).value }.collect { reading ->
            if (!folded && reading > foldAt) folded = true
            if (folded && reading < openAt) folded = false
        }
    }
    val fold by animateFloatAsState(if (folded) 1f else 0f, Motion.flow(), label = "pivotFold")
    val stepPad = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }

    // Where each effect sits on its page, so the dock can step through the ones that are on.
    val effectSpots = remember { EffectSpots() }
    effectSpots.scroll = scrolls.getValue(Section.Effects)
    val stepEffects: () -> Unit = {
        val keys = activeEffects(FxStore.state.value, dev.continuum.fx.audio.SpatialStore.state.value.enabled)
        effectSpots.next(keys, stepPad)?.let { y -> scope.launch { scrolls.getValue(Section.Effects).animateScrollTo(y, Motion.flow()) } }
    }

    CompositionLocalProvider(LocalSeeds provides seeds, LocalEffectSpots provides effectSpots) {
    Box(Modifier.fillMaxSize().background(p.ground)) {
        // The page is a column: the pivot at the top, the page, and the dock's place at the
        // bottom. Nothing is ever drawn over the page but the dock's own ink when you open it.
        Column(Modifier.fillMaxSize()) {
            PivotHeader(
                titles = sections.map { it.name.lowercase() },
                pivot = pivot,
                onSelect = { slot ->
                    if (slot == pivot.contentSlot) {
                        // Choosing the shown page also cancels a departure before halfway.
                        // Its title still takes the reading position back to the top.
                        go(slot)
                        scope.launch { scrolls.getValue(shown).animateScrollTo(0) }
                    } else {
                        go(slot)
                    }
                },
                modifier = Modifier.statusBarsPadding().padding(top = 14.dp),
                fold = { fold },
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
                                drag.on = true
                                drag.from = pivot.slot.value.roundToInt()
                                scope.launch { pivot.slot.stop() }
                            },
                            onDragEnd = {
                                drag.on = false
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val v = tracker.calculateVelocity().x
                                val from = drag.from
                                val target = when {
                                    v < -FLING -> from + 1
                                    v > FLING -> from - 1
                                    else -> pivot.slot.value.roundToInt().coerceIn(from - 1, from + 1)
                                }
                                go(target, -v / width)
                            },
                            onDragCancel = {
                                drag.on = false
                                go(drag.from)
                            },
                        ) { change, dx ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            // One page either side of where the drag began.
                            val from = drag.from.toFloat()
                            val before = pivot.slot.value
                            val next = (before - dx / width).coerceIn(from - 1f, from + 1f)
                            // Halfway across, the page changes: felt as a detent.
                            if (before.roundToInt() != next.roundToInt()) {
                                InkFeel.detent(view)
                                show(next.roundToInt())
                            }
                            scope.launch { pivot.slot.snapTo(next) }
                        }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // The page slides as the rail's ink travels; measured from this page's
                            // own slot, so in the frame between a swipe and the page changing, the
                            // old page doesn't jump.
                            val cs = pivot.contentSlot
                            val own = if (pivot.indexOf(cs) == state.section.ordinal) cs
                            else pivot.nearest(state.section.ordinal, pivot.slot.value.roundToInt())
                            val shift = own - pivot.slot.value
                            translationX = shift * size.width * 0.55f
                            // Gone by halfway, where the next page takes over from nothing.
                            alpha = (1f - abs(shift) * 2f).coerceIn(0f, 1f)
                        }
                        .onGloballyPositioned { if (state.section == Section.Effects) effectSpots.viewTop = it.positionInRoot().y }
                        .verticalScroll(scrolls.getValue(state.section))
                        // On a tablet or an open foldable, a comfortable reading width, kept to
                        // the left edge the header's title starts from.
                        .wrapContentWidth(Alignment.Start)
                        .widthIn(max = CONTENT_MAX + 48.dp)
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 32.dp),
                ) {
                    UpdateCard(
                        update = state.update,
                        title = { "Continuum Kit build ${it.build}" },
                        subtitle = "Settings and permissions stay.",
                        actionLabel = "Update now",
                        installingNote = "Installing. Continuum Kit closes for a moment while it updates, then it's back as it was.",
                        onAction = actions::installUpdate,
                        onAllow = actions::allowInstalls,
                        onDismiss = actions::dismissUpdate,
                    )
                    pageState.SaveableStateProvider(state.section.name) {
                        PageBody(state.section, state, store, actions, scrolls)
                    }
                    Gap(8.dp)
                    UpdateFooter(state, actions)
                }
                // The page dissolves into the ground under the titles and above the dock, so a
                // row is never sliced by either. Under the titles it's a long, soft dissolve (a
                // hero read down past the fold fades out rather than being cut), and only once the
                // page has moved, so what rests at the top is never washed. It's at full strength
                // by the time the first line (10dp down) reaches the edge, so a page too short to
                // fold the pivot still lets its kicker go by dissolving, never half cut.
                val reading = scrolls.getValue(state.section)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .graphicsLayer { alpha = (reading.value / 10.dp.toPx()).coerceIn(0f, 1f) }
                        .background(
                            Brush.verticalGradient(
                                0f to p.ground,
                                0.35f to p.ground.copy(alpha = 0.85f),
                                0.7f to p.ground.copy(alpha = 0.3f),
                                1f to p.ground.copy(alpha = 0f),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Brush.verticalGradient(listOf(p.ground.copy(alpha = 0f), p.ground))),
                )
                // With setup open, the page sits back behind the dock's ink.
                val dim by animateFloatAsState(if (state.setupOpen) 0.72f else 0f, Motion.flow(), label = "pageDim")
                if (dim > 0.01f) Box(Modifier.matchParentSize().background(p.ground.copy(alpha = dim)))
            }
            // The dock's place: it sits here, under the page, never on it.
            Spacer(Modifier.navigationBarsPadding().height(dockHeight() + DOCK_GAP))
        }

        SeedOverlay(seeds)
        DockMessage(state.message, dockRect, actions::dismissMessage)
        Dock(state, actions, Modifier.align(Alignment.BottomStart), onStepEffects = stepEffects) { dockRect.value = it }
    }
    }
}

/** What a pivot page holds, under the Kit's update card and above its colophon. */
@Composable
private fun PageBody(section: Section, state: UiState, store: ProfileStore, actions: AppActions, scrolls: Map<Section, ScrollState>) {
    when (section) {
        Section.Sound -> Column {
            // A player's Equalizer button sent you here: an ink card from the title's drop.
            val from = rememberLastOf(state.openedFrom)
            Seeded(visible = state.openedFrom != null, corner = 24.dp, edge = true, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)) {
                if (from != null) OpenedFromRow(from, state.library, actions)
            }
            // The page only speaks up when something needs you; the strip says the rest.
            if (state.error != null) {
                StatusLine(state)
                Gap(18.dp)
            }
            SoundContent(state, store, actions, scrolls.getValue(Section.Sound))
        }
        Section.Effects -> EffectsContent(state, actions, scrolls.getValue(Section.Effects))
        Section.Island -> IslandContent(state, actions)
        Section.Volume -> VolumeContent(state, actions)
        Section.Keyboard -> KeyboardContent(state, actions)
    }
}

/** The way into the Lab: its name, what's in it, and an arrow pointing on to its page. */
@Composable
private fun LabRow(onOpen: () -> Unit) {
    val lab by dev.continuum.fx.lab.LabStore.state.collectAsState()
    val n = lab.measurements.size
    dev.continuum.fx.ui.bench.BenchLinkRow(
        "Lab",
        "Fit an EQ from a measurement to a target, or make a target of your own.",
        onOpen = onOpen,
        value = if (n == 0) "new" else if (n == 1) "1 measurement" else "$n measurements",
        onClickLabel = "Open the Lab",
    )
}

/**
 * Left and right matched in the Lab: each side's level and filters, on top of the ones above.
 * The graph draws the shared filters; these play on one side each.
 */
@Composable
private fun SidesNote(profile: dev.continuum.fx.dsp.EqProfile, onRemove: () -> Unit) {
    val p = LocalPalette.current
    fun side(name: String, t: dev.continuum.fx.dsp.SideTrim?): String {
        if (t == null || t.isEmpty) return "$name: as the rest"
        val n = t.bands.count { it.enabled }
        return "$name: ${Fmt.db(t.levelDb)}" + if (n > 0) ", ${if (n == 1) "1 filter" else "$n filters"}" else ""
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        dev.continuum.fx.ui.theme.Note("left and right matched", p.sub)
        Gap(4.dp)
        Txt("${side("Left", profile.left)}. ${side("Right", profile.right)}.", 15.sp, p.text)
        Txt("On top of the filters above, so a pair that measures differently meets in the middle.", 13.sp, p.sub)
        PillButton("Play both sides alike", onRemove, icon = R.drawable.ic_undo)
    }
}

/** A sideways drag of the page: whether a finger has it, and the page it began on. */
private class PageDrag {
    var on = false
    var from = 0
}

/** The dock's height closed, and the ground left under it. */
private val DOCK_H = 72.dp

private val DOCK_GAP = 10.dp

/**
 * How many lines the dock's words may take: one each, until the type is large enough (past 1.4)
 * that "Volume panel off" or "Tap to set up · 2 steps" would be cut; then two, and the dock grows
 * to hold them rather than ending them in "…".
 */
@Composable
internal fun dockLines(): Int = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f) 2 else 1

/**
 * The dock's height closed: 72dp, or as tall as its words need at large type, with the same air
 * above and below them that its round ends need at any size (more than a line's worth, so the
 * first word never sits against the curve).
 */
@Composable
private fun dockHeight(): Dp {
    val lines = dockLines()
    return with(androidx.compose.ui.platform.LocalDensity.current) {
        maxOf(DOCK_H, ((18.sp * 1.38f).toDp() + (14.sp * 1.38f).toDp()) * lines + 3.dp + if (lines > 1) 44.dp else 24.dp)
    }
}

@Composable
private fun SoundContent(state: UiState, store: ProfileStore, actions: AppActions, scroll: ScrollState) {
    val p = LocalPalette.current
    val library = state.library
    val profile = library.active
    var selected by remember(profile.id) { mutableIntStateOf(-1) }
    // The Lab takes the page while it's open; back (or Done) returns to the EQ.
    var lab by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun openLab(open: Boolean) {
        lab = open
        scope.launch { scroll.scrollTo(0) }
    }
    // The dock owns Back while its sheet is open; the Lab underneath stays where it was.
    BackHandler(enabled = lab && !state.setupOpen) { openLab(false) }
    if (lab) {
        dev.continuum.fx.ui.lab.LabContent(store, actions, onClose = { openLab(false) })
        return
    }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var linksOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val eqOn = library.eqEnabled
    val canCompare = state.running && eqOn
    val view = LocalView.current
    // Nothing to compare once the EQ is off or stopped, so a hold that was comparing lets go.
    LaunchedEffect(canCompare) { if (!canCompare) EngineStatus.comparing.value = false }
    val compare: (Boolean) -> Unit = { on ->
        if (on) InkFeel.detent(view)
        EngineStatus.comparing.value = on
    }
    Column {
        // The EQ you're listening through, set large; tap its name for all of them.
        EqHero(library, store, actions, onLab = { openLab(true) })
        Gap(10.dp)

        val comparing by EngineStatus.comparing.collectAsState()
        // A flat EQ needs little room; one with filters to shape gets the room to shape them in.
        val graphH by animateDpAsState(if (eqOn && profile.bands.isNotEmpty()) 236.dp else 160.dp, Motion.flow(), label = "graphHeight")
        EqGraph(
            profile = profile,
            live = state.running,
            // With no EQ, the curve lies flat, as the sound does.
            comparing = !eqOn || (comparing && canCompare),
            editable = eqOn,
            selected = selected,
            onSelect = { selected = it },
            // Edge to edge: the centre of the page.
            modifier = Modifier.bleed(24.dp),
            inset = 24.dp,
            height = graphH,
            // Hold the graph anywhere away from a dot to hear the music without the EQ.
            onCompare = compare.takeIf { canCompare },
            onQ = { i, q ->
                store.updateActive { pr ->
                    pr.copy(bands = pr.bands.mapIndexed { j, b -> if (j != i) b else b.copy(q = Fmt.roundQ(q)) })
                }
            },
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
        Reveal(eqOn && profile.bands.isEmpty()) {
            // A flat graph says what it is, so the empty grid isn't a hole in the page. It's the
            // drawing's own caption, so it hangs from the drawing's edge.
            Txt("Flat · no filters yet", 14.sp, p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        }

        // The ledger under the instrument, one grammar all the way down: this EQ's own controls,
        // then the way to make new ones.
        BenchSection(1, "This EQ")
        // With the EQ off, this EQ's own controls go (they'd shape nothing), and come back with it.
        Reveal(eqOn) {
            PreampRow(
                preampDb = profile.preampDb,
                suggested = EqResponse.suggestedPreamp(profile),
                onPreamp = { v -> store.updateActive { it.copy(preampDb = v) } },
                onAuto = { store.updateActive { it.copy(preampDb = EqResponse.suggestedPreamp(it)) } },
            )

            // The filters, folded into one line: the graph is where you shape the sound; this is
            // for exact numbers and filter types.
            dev.continuum.fx.ui.bench.BenchValueRow(
                label = "Filters",
                caption = "Exact frequency, gain and shape of each one",
                value = if (profile.bands.isEmpty()) "None" else "${profile.bands.size}",
                expanded = filtersOpen,
                onExpand = { filtersOpen = !filtersOpen },
            ) {
                if (profile.bands.isEmpty()) {
                    Rest(
                        "${profile.name} has no filters, so music plays as it is. Add one, or bring in an EQ from squig.link or AutoEQ.",
                        action = "Import an EQ",
                        icon = R.drawable.ic_import,
                        onAction = actions::importFile,
                        inRail = false,
                    )
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
                }
                PillButton("Add a filter", {
                    val newIndex = profile.bands.size
                    store.updateActive { it.copy(bands = it.bands + EqBand(FilterType.PEAK, 1000.0, 0.0, 1.0)) }
                    selected = newIndex
                }, icon = R.drawable.ic_plus)
                Gap(8.dp)
                if (profile.sided) {
                    SidesNote(profile) { store.updateActive { it.copy(left = null, right = null) } }
                    Gap(8.dp)
                }
            }
        }

        // Where this EQ comes on by itself: the headphones it's linked to, and apps of its own.
        val output by OutputWatcher.current.collectAsState()
        val linkedHere = output?.let { library.devices[it.key] == profile.id } == true
        val appCount = library.appsFor(profile.id).size
        // A device's own name only while it's short enough to be a value; a long one (a headphone
        // named like a sentence) is "here", what you're listening on, and its whole name is in the
        // row that opens below.
        val parts = listOfNotNull(
            if (linkedHere) output?.name?.let { if (it.length <= 20) it else "Here" } else null,
            when (appCount) {
                0 -> null
                1 -> "1 app"
                else -> "$appCount apps"
            },
        )
        dev.continuum.fx.ui.bench.BenchValueRow(
            label = "Comes on by itself",
            caption = "Headphones and apps that bring this EQ in",
            value = if (parts.isEmpty()) "Not yet" else parts.joinToString(" · "),
            expanded = linksOpen,
            onExpand = { linksOpen = !linksOpen },
        ) {
            output?.let { device ->
                OutputRow(device, library, onLink = { on -> store.link(device.key, if (on) profile.id else null) })
            }
            AppsList(library, actions)
        }

        Reveal(eqOn) {
            // What's about this EQ itself, and rarely needed.
            MoreSection(moreOpen, { moreOpen = !moreOpen }, label = moreFor(profile.name)) {
                PolarityRow(profile.polarity) { pol -> store.updateActive { it.copy(polarity = pol) } }
                Gap(6.dp)
                ActionRow {
                    PillButton("Share", actions::shareActive, icon = R.drawable.ic_share)
                    PillButton("Delete", { store.delete(profile.id) }, icon = R.drawable.ic_trash)
                }
                Gap(8.dp)
            }
        }

        // The Lab: a page of its own, for making EQs from measurements and targets of your own.
        BenchSection(2, "New EQs")
        LabRow(onOpen = { openLab(true) })

        if (state.blocked.isNotEmpty()) {
            Gap(18.dp)
            val names = state.blocked.joinToString()
            val one = state.blocked.size == 1
            Txt(
                "$names ${if (one) "blocks" else "block"} audio capture, so ${if (one) "it plays" else "they play"} without effects.",
                14.sp, p.sub,
                modifier = Modifier.padding(start = INK_RAIL),
            )
        }
    }
}

/**
 * The EQ you're listening through, set large; swipe it for the next. Its name ends in a chevron:
 * tap it and every EQ drops down under it, each with a small drawing of its curve, "Off" at the
 * top, and the ways to bring in a new one at the bottom.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
private fun EqHero(library: Library, store: ProfileStore, actions: AppActions, onLab: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val profiles = library.profiles
    val eqOn = library.eqEnabled
    val index = if (!eqOn) 0 else 1 + profiles.indexOfFirst { it.id == library.activeId }.coerceAtLeast(0)
    var dir by remember { mutableIntStateOf(1) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    fun choose(i: Int) {
        if (i == index || i < 0 || i > profiles.size) return
        dir = if (i > index) 1 else -1
        InkFeel.detent(view)
        if (i == 0) {
            store.setEqEnabled(false)
        } else {
            store.select(profiles[i - 1].id)
            if (!eqOn) store.setEqEnabled(true)
        }
    }
    val latestChoose by rememberUpdatedState<(Int) -> Unit>({ i -> choose(i) })
    val latestIndex by rememberUpdatedState(index)
    // Off is the first of the list, so the hero says it; the kicker only says where in the list.
    Kicker("Listening through", if (eqOn) "$index of ${profiles.size}" else null)
    Gap(6.dp)
    val source = remember { MutableInteractionSource() }
    InkSection(listOpen, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = if (listOpen) "Close the list" else "Show every EQ") {
                    listOpen = !listOpen
                }
                .opens(listOpen) { listOpen = !listOpen }
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            val threshold = 36.dp.toPx()
                            if (dx < -threshold) latestChoose(latestIndex + 1) else if (dx > threshold) latestChoose(latestIndex - 1)
                        },
                    ) { change, amount ->
                        change.consume()
                        dx += amount
                    }
                },
            verticalAlignment = Alignment.Top,
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = if (eqOn) library.active.name else "Off",
                modifier = Modifier.weight(1f, fill = false),
                transitionSpec = {
                    val d = dir
                    (androidx.compose.animation.slideInHorizontally(Motion.flow()) { w -> d * w / 6 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(Motion.flow()) { w -> -d * w / 6 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)))
                },
                label = "eqName",
            ) { name ->
                HeroLine(name, p.text, size = 30.sp)
            }
            Box(Modifier.padding(start = 8.dp, top = 10.dp)) { Chevron(listOpen, p.quiet, 18.dp) }
        }
    }) {
        Column(Modifier.padding(start = INK_RAIL, top = 6.dp, bottom = 6.dp)) {
            EqListRow("Off", null, chosen = !eqOn) {
                latestChoose(0)
                listOpen = false
            }
            profiles.forEachIndexed { i, pr ->
                androidx.compose.runtime.key(pr.id) {
                    EqListRow(pr.name, pr, chosen = eqOn && pr.id == library.activeId) {
                        latestChoose(i + 1)
                        listOpen = false
                    }
                }
            }
            Gap(6.dp)
            ActionRow {
                PillButton("Import", { listOpen = false; actions.importFile() }, icon = R.drawable.ic_import)
                PillButton("Paste", { listOpen = false; actions.pasteFromClipboard() }, icon = R.drawable.ic_paste)
                PillButton("Lab", { listOpen = false; onLab() }, icon = R.drawable.ic_tune)
            }
        }
    }
}

/**
 * "More for Flat": the EQ's name where it's short enough to sit on the line with its chevron; a
 * long one (an imported "Sennheiser HD 800 S, Harman over-ear 2018…") is said as "this EQ"
 * instead, since the rule above already names the group and the hero names the EQ.
 */
private fun moreFor(name: String): String = if (name.length <= 22) "More for $name" else "More for this EQ"

/** One EQ in the list: a small drawing of its curve, its name, and "now" on the one playing. */
@Composable
private fun EqListRow(name: String, profile: dev.continuum.fx.dsp.EqProfile?, chosen: Boolean, onPick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val curve = remember(profile) { profile?.let { EqResponse.curve(it, 48) } }
    val color = if (chosen) p.text else p.sub
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = if (chosen) "$name, playing now" else name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 44.dp, height = 22.dp)) {
            val mid = size.height / 2f
            val line = 1.7.dp.toPx()
            if (curve == null || curve.isEmpty()) {
                // Off: the sound as it is, flat.
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), strokeWidth = line, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                return@Canvas
            }
            // Scaled to its own biggest move (at least 6 dB), so a gentle EQ still shows its shape.
            val most = maxOf(6.0, curve.maxOf { abs(it) })
            val k = (size.height / 2f - line) / most.toFloat()
            drawLine(p.line, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), strokeWidth = 1.dp.toPx())
            val path = androidx.compose.ui.graphics.Path()
            curve.forEachIndexed { i, db ->
                val x = size.width * i / (curve.size - 1).toFloat()
                val y = mid - db.toFloat() * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(line, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
        Spacer(Modifier.width(16.dp))
        // Up to three lines: two EQs for the same headphones often differ only at the end of
        // their names ("…Harman 2018" / "…Harman 2019"), and this is where you choose between them.
        Txt(name, 17.sp, color, bold = chosen, maxLines = 3, modifier = Modifier.weight(1f))
        if (chosen) Note("Now", p.sub, Modifier.padding(start = 12.dp))
    }
}

/**
 * The preamp, a field like the volume page's: its name at the rail and, at the far end, its value
 * with the one thing to know about it said on the same baseline: "auto" while it's where the EQ
 * wants it, "by hand" once it's been moved. Moved, the way back is a button under the slider (a
 * real 48dp target, below the finger that's dragging, so nothing shifts under it).
 */
@Composable
private fun PreampRow(preampDb: Double, suggested: Double, onPreamp: (Double) -> Unit, onAuto: () -> Unit) {
    val p = LocalPalette.current
    val span = EqLimits.MAX_PREAMP - EqLimits.MIN_PREAMP
    val auto = kotlin.math.abs(preampDb - suggested) < 0.05
    var held by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.animateContentSize(Motion.flow())) {
        Row(Modifier.fillMaxWidth().padding(start = INK_RAIL, top = 6.dp)) {
            Txt("Preamp", 17.sp, p.text, maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            dev.continuum.fx.ui.theme.Note(
                if (auto) "auto" else "by hand",
                if (auto) p.quiet else p.sub,
                Modifier.alignByBaseline().padding(horizontal = 12.dp),
            )
            Txt(Fmt.db(preampDb), 17.sp, p.text, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        InkSlider(
            value = ((preampDb - EqLimits.MIN_PREAMP) / span).toFloat(),
            onChange = { t -> onPreamp(Fmt.round1(EqLimits.MIN_PREAMP + t * span)) },
            label = Fmt.db(preampDb),
            onHeld = { h: Boolean -> held = h },
        )
        if (held) {
            Txt(
                if (preampDb > suggested + 0.05) "Louder, but the EQ's boosts may clip: auto keeps them clean."
                else "Everything quieter together, leaving room for the EQ's boosts.",
                14.sp, p.text,
                modifier = Modifier.padding(start = INK_RAIL),
            )
        } else if (!auto) {
            PillButton("Back to auto", onAuto, icon = R.drawable.ic_undo, modifier = Modifier.padding(start = INK_RAIL))
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
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Switch) { onLink(!linked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(if (device.kind == OutputDevice.Kind.Speaker) R.drawable.ic_speaker else R.drawable.ic_headphones),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(p.text),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            // Names that aren't ours (a headphone's, an imported EQ's) can be long: they wrap
            // whole (a fifty-letter headphone name is three lines here, four at 1.3) rather than
            // cutting off, with a row's 3dp before what happens here.
            Txt(device.name, 17.sp, p.text, maxLines = 4)
            Txt(
                when {
                    linked -> "${profile.name} comes on by itself here"
                    other != null -> "${other.name} comes on by itself here"
                    else -> "Link this EQ to come on whenever you use it"
                },
                14.sp, p.sub, maxLines = 3, modifier = Modifier.padding(top = 3.dp),
            )
        }
        InkSwitch(linked, "Link ${profile.name} to ${device.name}", onClick = { onLink(!linked) })
    }
}

/** How the EQ reaches other apps: by capturing what they play, or directly on their sound. */
@Composable
private fun ModeRow(actions: AppActions) {
    val p = LocalPalette.current
    val mode by EngineSettings.mode.collectAsState()
    ChoiceRow(Modifier.padding(start = INK_RAIL), fill = false, on = Surface.Ink) {
        Chip("Capture", mode == EngineMode.Capture, onClick = { actions.setEngineMode(EngineMode.Capture) }, on = Surface.Ink)
        Chip("Direct", mode == EngineMode.Direct, onClick = { actions.setEngineMode(EngineMode.Direct) }, on = Surface.Ink)
    }
    Gap(6.dp)
    Txt(
        when (mode) {
            EngineMode.Capture -> "Records what apps play and plays it back through the EQ, with exact filters. Android shows its capture icon."
            EngineMode.Direct -> "Puts the EQ on each app's own sound: no capture icon, less battery and delay, within a fraction of a decibel."
        },
        15.sp, p.onInkSub,
        modifier = Modifier.padding(start = INK_RAIL),
    )
}

@Composable
private fun StatusLine(state: UiState) {
    val p = LocalPalette.current
    val error = state.error
    val mode by EngineSettings.mode.collectAsState()
    val text: String = when {
        error != null -> error
        state.running && state.processing.isNotEmpty() -> "On · ${state.processing.joinToString()}"
        state.running -> "On · waiting for something to play"
        !readyFor(mode, state.privileges) -> "Off · finish setup to start"
        else -> "Off"
    }
    val color = when {
        error != null -> p.text
        state.running -> p.text
        else -> p.sub
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // What needs you is the one thing in colour: a red mark beside it.
        if (error != null) {
            dev.continuum.fx.island.ui.RedMark(16.dp)
            Spacer(Modifier.width(10.dp))
        }
        Txt(text, 17.sp, color, maxLines = 2)
    }
}


/**
 * Polarity, for earphones that come out the wrong way round: a push in the recording arriving
 * as a pull. Saved with the EQ, since an EQ is usually for one pair.
 */
@Composable
private fun PolarityRow(polarity: Polarity, onPick: (Polarity) -> Unit) {
    val p = LocalPalette.current
    val mode by EngineSettings.mode.collectAsState()
    val direct = mode == EngineMode.Direct
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val flipped = polarity != Polarity.Normal
    InkSection(open, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open }
                .opens(open) { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                OpensName("Polarity", open, p.text)
                Txt(
                    when {
                        flipped && direct -> "Needs Capture mode to turn the sound over"
                        polarity == Polarity.Inverted -> "Turned over, for earphones that push the wrong way"
                        polarity == Polarity.LeftInverted -> "The left channel turned over"
                        polarity == Polarity.RightInverted -> "The right channel turned over"
                        else -> "As recorded"
                    },
                    14.sp, if (flipped && !direct) p.text else p.sub, maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Txt(polarity.label, 15.sp, if (flipped) p.text else p.sub, maxLines = 1)
        }
    }) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Txt(
                    "Some earphones are built or wired so that a push in the recording comes out as a pull. Inverted turns " +
                        "the sound over so they push the right way round. Left only and Right only mend one channel wired " +
                        "backwards, which sounds hollow, with the middle and the bass gone. It's saved with this EQ.",
                    14.sp, p.sub,
                )
                Gap(6.dp)
                Txt(
                    "Turning both channels over is a subtle change, and recordings aren't always the right way round " +
                        "themselves, so trust your ears: switch back and forth on a track with a punchy kick drum.",
                    14.sp, p.sub,
                )
                if (direct) {
                    Gap(6.dp)
                    Txt(
                        "Needs Capture: Direct mode works through Android's own effect, which can only raise and lower the " +
                            "sound, not turn it over.",
                        14.sp, p.text,
                    )
                }
                Gap(10.dp)
                ChoiceRow {
                    Polarity.entries.forEach { pol ->
                        Chip(pol.label, polarity == pol, onClick = { onPick(pol) }, on = Surface.Card)
                    }
                }
            }
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
    InkSection(expanded, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
                .opens(expanded, onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(band.type.shortLabel, 14.sp, if (band.enabled) p.text else p.sub, bold = true, modifier = Modifier.width(36.dp))
            OpensName(Fmt.freq(band.frequency), expanded, textColor, Modifier.weight(1f))
            if (band.type.usesGain) {
                Txt(Fmt.db(band.gainDb), 16.sp, textColor, maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            Txt("Q ${Fmt.q(band.q)}", 16.sp, p.sub, maxLines = 1)
            InkSwitch(band.enabled, "Filter on", onClick = { onChange(band.copy(enabled = !band.enabled)) })
        }
    }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                ChoiceRow {
                    listOf(
                        FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF,
                        FilterType.LOW_PASS, FilterType.HIGH_PASS, FilterType.NOTCH,
                    ).forEach { t ->
                        Chip(t.label, band.type == t, onClick = { onChange(band.copy(type = t)) }, on = Surface.Card)
                    }
                }
                Gap(10.dp)
                SliderField("Frequency", Fmt.freq(band.frequency), Fmt.freqToT(band.frequency), hear = "Moves it along: left for the bass, right for the treble.") { t ->
                    onChange(band.copy(frequency = Fmt.roundFreq(Fmt.tToFreq(t))))
                }
                Reveal(band.type.usesGain) {
                    val gSpan = EqLimits.MAX_GAIN - EqLimits.MIN_GAIN
                    SliderField("Gain", Fmt.db(band.gainDb), ((band.gainDb - EqLimits.MIN_GAIN) / gSpan).toFloat(), hear = "Up brings this part of the sound forward; down sets it back.") { t ->
                        onChange(band.copy(gainDb = Fmt.round1(EqLimits.MIN_GAIN + t * gSpan)))
                    }
                }
                SliderField("Q", Fmt.q(band.q), Fmt.qToT(band.q), hear = "Right narrows it to a few notes; left spreads it wide and gentle.") { t ->
                    onChange(band.copy(q = Fmt.roundQ(Fmt.tToQ(t))))
                }
                Gap(8.dp)
                PillButton("Remove filter", onRemove, icon = R.drawable.ic_trash, on = Surface.Card)
            }
    }
}

/**
 * A labelled slider. With [hear], while your finger is on it a line under it says what you hear
 * change as it moves, and goes again when you let go.
 */
@Composable
internal fun SliderField(label: String, valueText: String, value: Float, hear: String? = null, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    var held by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 2.dp).animateContentSize(Motion.flow())) {
        Row(Modifier.fillMaxWidth()) {
            Txt(label, 15.sp, p.sub, modifier = Modifier.weight(1f))
            Txt(valueText, 15.sp, p.text)
        }
        InkSlider(value = value, onChange = onChange, label = valueText, onHeld = { h: Boolean -> held = h })
        if (held && hear != null) {
            Txt(hear, 14.sp, p.text, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * The ink dock: the capsule along the bottom that holds the page's power, what it's doing and the
 * way into setup. It has its own place under the page (the page ends above it, so it never covers
 * a row), and grows straight up into the setup sheet rather than opening a separate panel: the
 * same ink, reshaped, the page sitting back behind it while it's open.
 */
@Composable
private fun Dock(
    state: UiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    onStepEffects: () -> Unit = {},
    onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val p = LocalPalette.current
    val config = LocalConfiguration.current
    val openHeight = (config.screenHeightDp - 120).coerceIn(420, 660).dp
    // Out on Flow, a little past and back; home on a firmer spring, so the bar settles onto its
    // own height instead of dipping a third of the way under it (Flow from a full sheet overshoots
    // by ~50dp, which cut "Off / Tap to set up" in half for a fifth of a second).
    val height by animateDpAsState(if (state.setupOpen) openHeight else dockHeight(), if (state.setupOpen) Motion.flow() else Motion.settle(), label = "dockHeight")
    val side by animateDpAsState(if (state.setupOpen) 8.dp else 12.dp, Motion.flow(), label = "dockSide")
    // Under the page's column: the screen's width on a phone; on a tablet or an open foldable,
    // the reading width, hung from the same left edge as the page's words (12dp outside their
    // margin on each side, as on a phone), rather than centred under a column that isn't.
    val width = minOf(config.screenWidthDp.dp, CONTENT_MAX + 48.dp) - side * 2
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = side, bottom = DOCK_GAP)
            .width(width)
            .height(height)
            .onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .then(if (p.dark) Modifier.border(1.dp, p.edge, shape) else Modifier)
            .clip(shape)
            .background(p.ink),
    ) {
        // The bar's words and the sheet's never show at once: the old ones are gone before the new
        // ones come (a crossfade left two sets of words on top of each other for a few frames).
        androidx.compose.animation.AnimatedContent(
            targetState = state.setupOpen to state.section,
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 80)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80)) using null
            },
            label = "dockContent",
        ) { (open, section) ->
            when {
                open && section == Section.Island -> IslandSetupSheet(state, actions)
                open && section == Section.Volume -> VolumeSetupSheet(state, actions)
                open && (section == Section.Sound || section == Section.Effects) -> SetupSheet(state, actions)
                section == Section.Island -> IslandDockBar(state, actions)
                section == Section.Volume -> VolumeDockBar(state, actions)
                section == Section.Keyboard -> KeyboardDockBar(state, actions)
                else -> DockBar(state, actions, onStepEffects.takeIf { section == Section.Effects })
            }
        }
    }
}

/**
 * The processing dock, for Sound and Effects. On the Effects page ([onStepEffects] given) it's
 * about the effects: how many are on; hold the words to hear the music without them, tap to be
 * taken to each one that's on in turn.
 */
@Composable
private fun DockBar(state: UiState, actions: AppActions, onStepEffects: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val library = state.library
    val mode by EngineSettings.mode.collectAsState()
    val fx by FxStore.state.collectAsState()
    val effects = fx.onNames().size
    val withFx = if (effects == 0) "" else " · $effects ${if (effects == 1) "effect" else "effects"}"
    // The page shows the EQ; the dock says what it's working on.
    val priv = state.privileges
    val subtitle = when {
        !readyFor(mode, priv) && !state.running ->
            if (mode == EngineMode.Direct) setupSteps(0, 1) else setupSteps(listOf(priv.recordAudio, priv.dump).count { it }, 2)
        state.running && state.processing.isNotEmpty() -> state.processing.joinToString() + withFx
        state.running -> "Waiting for something to play"
        !library.eqEnabled -> "EQ off$withFx"
        else -> library.active.name + withFx
    }
    val playing = state.running && state.processing.isNotEmpty()
    val music = rememberLiveLevels(playing, DOCK_BANDS)
    val inside by animateFloatAsState(if (playing) 1f else 0f, Motion.flow(), label = "dockMusic")
    Box(Modifier.fillMaxSize()) {
        // The music, moving inside the ink as tiny drops of lighter ink.
        Canvas(Modifier.fillMaxSize()) {
            val tick = music.frame
            if (inside <= 0.01f || tick < 0) return@Canvas
            val step = 7.dp.toPx()
            val x0 = 84.dp.toPx()
            val x1 = size.width - 60.dp.toPx()
            val floor = size.height - 6.dp.toPx()
            val color = p.onInk.copy(alpha = 0.16f * inside)
            var x = x0
            while (x <= x1) {
                val b = (((x - x0) / (x1 - x0).coerceAtLeast(1f)) * (DOCK_BANDS - 1)).roundToInt().coerceIn(0, DOCK_BANDS - 1)
                val high = music.levels[b].coerceIn(0f, 1f) * size.height * 0.8f
                var y = floor
                while (floor - y < high) {
                    val d = (floor - y) / high
                    drawCircle(color, 0.5.dp.toPx() + 1.4.dp.toPx() * (1f - d), androidx.compose.ui.geometry.Offset(x, y))
                    y -= step
                }
                x += step
            }
        }
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PowerButton(state.running, R.drawable.ic_power, actions::togglePower, waiting = state.running && state.processing.isEmpty())
            val spatialOn by dev.continuum.fx.audio.SpatialStore.state.collectAsState()
            val ready = readyFor(mode, priv)
            val active = activeEffects(fx, spatialOn.enabled).size
            // On Effects the dock is about the effects once there are some to hear: to compare
            // them or step through them. With none on, the page's hero already says so, and the
            // dock goes on saying what it says on Sound: whether anything's being processed.
            if (onStepEffects != null && (ready || state.running) && active > 0) {
                EffectsDockText(
                    title = "$active ${if (active == 1) "effect" else "effects"} active",
                    subtitle = if (!state.running) "Start processing to hear them" else "Hold to compare · tap to find them",
                    canCompare = state.running,
                    onStep = onStepEffects,
                )
            } else {
                DockText(if (state.running) "Processing" else "Off", subtitle, "Open setup") { actions.setSetupOpen(true) }
            }
            IconBubble(R.drawable.ic_setup, "Setup", onClick = { actions.setSetupOpen(true) }, on = Surface.Ink)
        }
    }
}

/**
 * The dock's words. All of them open setup (or whatever comes next), so there's no small target
 * to hunt for: the gear beside them is only the sign of it.
 */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.DockText(title: String, subtitle: String, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = 14.dp, end = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.squash(source, pill = true)) {
            Txt(title, 18.sp, p.onInk, bold = true, maxLines = dockLines())
            // A row's 3dp between name and caption, so a descender never meets the line below.
            Txt(subtitle, 14.sp, p.onInkSub, maxLines = dockLines(), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * The Effects page's dock words. Held, the music plays without the EQ and effects (at the same
 * loudness) for as long as you hold; tapped, the page goes to the next effect that's on.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.EffectsDockText(title: String, subtitle: String, canCompare: Boolean, onStep: (() -> Unit)?) {
    val p = LocalPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val compare by rememberUpdatedState(canCompare)
    val step by rememberUpdatedState(onStep)
    var comparing by remember { mutableStateOf(false) }
    // Nothing to compare any more (stopped, or the last effect off): let go.
    LaunchedEffect(canCompare) { if (!canCompare && comparing) { comparing = false; EngineStatus.comparing.value = false } }
    // Gone mid-hold (the last effect switched off with another finger, and the dock goes back to
    // saying what's processing): let go too.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (comparing) EngineStatus.comparing.value = false } }
    val squeeze by animateFloatAsState(if (comparing) 1f else 0f, Motion.snap(), label = "dockHold")
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .semantics {
                role = Role.Button
                contentDescription = "$title. $subtitle"
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        var held = false
                        val hold = scope.launch {
                            delay(260)
                            if (compare) {
                                held = true
                                comparing = true
                                InkFeel.detent(view)
                                EngineStatus.comparing.value = true
                            }
                        }
                        val released = tryAwaitRelease()
                        hold.cancel()
                        if (held) {
                            comparing = false
                            EngineStatus.comparing.value = false
                        } else if (released) {
                            step?.let {
                                InkFeel.detent(view)
                                it()
                            }
                        }
                    },
                )
            }
            .padding(start = 14.dp, end = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.graphicsLayer { val k = 1f - 0.04f * squeeze; scaleX = k; scaleY = k }) {
            Txt(if (comparing) "Without them" else title, 18.sp, p.onInk, bold = true, maxLines = dockLines())
            Txt(if (comparing) "Let go to hear them again" else subtitle, 14.sp, p.onInkSub, maxLines = dockLines(), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Setup still to do, said as how far along it is: "Tap to set up · 2 steps", "1 of 2 steps done". */
internal fun setupSteps(done: Int, total: Int): String = when {
    done <= 0 -> "Tap to set up · $total ${if (total == 1) "step" else "steps"}"
    else -> "$done of $total steps done · tap to finish"
}

/** How many bands of the music move inside the dock. */
private const val DOCK_BANDS = 24

@Composable
private fun SetupSheet(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val priv = state.privileges
    val packageName = LocalContext.current.packageName
    val mode by EngineSettings.mode.collectAsState()
    val direct = mode == EngineMode.Direct
    var step = 0
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(if (direct) "Set up the EQ" else "Set up capture", 22.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
            IconBubble(R.drawable.ic_chevron_down, "Close setup", onClick = { actions.setSetupOpen(false) }, on = Surface.Ink)
        }
        Gap(4.dp)
        Txt(
            if (direct) "Direct mode works without root or screen capture: the EQ goes on each app's own sound."
            else "Continuum Kit works without root: it captures what other apps play, processes it and plays it back.",
            15.sp, p.onInkSub,
        )
        Gap(16.dp)
        // How the sound is reached, set once, so it lives here rather than on the page.
        ModeRow(actions)
        Gap(10.dp)

        StepRow(
            ++step, priv.recordAudio,
            if (direct) "The live spectrum" else "Hear other apps",
            if (direct) "Lets the graph show the music moving. The EQ works without it." else "Lets the app capture the sound other apps play.",
            optional = direct,
        ) {
            PillButton("Allow", actions::requestRecord, icon = R.drawable.ic_wave, primary = !direct, on = Surface.Ink)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            StepRow(++step, priv.notifications, "Notifications", "Shows when processing is on, with a stop button.", optional = true) {
                PillButton("Allow", actions::requestNotifications, icon = R.drawable.ic_bell, on = Surface.Ink)
            }
        }
        StepRow(
            ++step, priv.dump,
            if (direct) "Find each app's sound" else "Silence the originals",
            if (direct) "Lets the EQ find what each app is playing, to put itself on it. Grant this once with Shizuku or from a computer."
            else "Mutes each app's own sound so you only hear the processed version. Grant this once with Shizuku or from a computer.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = when (priv.shizuku) {
                    ShizukuState.READY -> "Grant with Shizuku"
                    ShizukuState.NEEDS_PERMISSION -> "Allow Shizuku"
                    ShizukuState.NOT_RUNNING -> "Use Shizuku"
                }
                PillButton(label, actions::grantWithShizuku, icon = R.drawable.ic_bolt, primary = true, on = Surface.Ink)
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
        if (!direct) {
            StepRow(
                ++step, priv.projectMedia, "Skip the capture prompt",
                "Granted along with the step above, so processing starts without a system dialog each time.",
                optional = true,
            )
        }
        Gap(14.dp)
        if (readyFor(mode, priv)) {
            PillButton(
                if (state.running) "Stop processing" else "Start processing",
                {
                    actions.setSetupOpen(false)
                    actions.togglePower()
                },
                icon = R.drawable.ic_power,
                primary = true,
                on = Surface.Ink,
                modifier = Modifier.padding(start = INK_RAIL),
            )
        }
        // Rarely wanted, so kept here rather than on a page: the sheet's end matter, under a rule
        // without a number, as the colophon is at a page's foot.
        Gap(12.dp)
        BenchSection(null, "Backup", onInk = true)
        Column(Modifier.padding(start = INK_RAIL)) {
            Txt(
                "Everything you've set up in one file: your EQs and which apps and headphones use them, the effects, the island, " +
                    "the volume panel and each app's volume. Restoring replaces what's here, and Continuum Kit restarts to load it.",
                14.sp, p.onInkSub,
            )
            Gap(6.dp)
            ActionRow {
                PillButton("Save a backup", actions::saveBackup, icon = R.drawable.ic_import, on = Surface.Ink)
                PillButton("Restore", actions::restoreBackup, icon = R.drawable.ic_undo, on = Surface.Ink)
            }
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
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        // The same margin column as every page: the step's number, or a drop once it's done.
        Box(Modifier.widthIn(min = INK_RAIL).padding(top = 3.dp)) {
            if (done) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(p.onInk),
                )
            } else {
                Note("%02d".format(number), p.onInkSub, Modifier.padding(end = dev.continuum.fx.ui.bench.NUMBER_GAP), tracking = dev.continuum.fx.ui.bench.NUMBER_TRACKING)
            }
        }
        Column(Modifier.weight(1f)) {
            Txt(if (optional) "$title · optional" else title, 17.sp, p.onInk)
            Gap(4.dp)
            Txt(body, 14.sp, p.onInkSub)
            if (!done) {
                Gap(6.dp)
                action()
            }
        }
    }
}

/** Fling speed, in pixels a second, that turns the pivot without dragging it halfway. */
private const val FLING = 650f

/** Whether processing can start the [mode] way: Direct needs only to find apps' sound. */
private fun readyFor(mode: EngineMode, priv: PrivilegeState): Boolean =
    if (mode == EngineMode.Direct) priv.dump else priv.ready
