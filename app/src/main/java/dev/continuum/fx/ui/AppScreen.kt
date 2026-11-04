package dev.continuum.fx.ui

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import dev.continuum.fx.ui.components.InkSection
import dev.continuum.fx.ui.components.rememberLiveLevels
import dev.continuum.fx.ui.theme.Note
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
    /** Choose your earphones' measured response, or the target, to fit an EQ between. */
    fun pickMeasurement()
    fun pickTarget()
    /** Fits an EQ from the measurement to the target and adds it as a new EQ. */
    fun makeFittedEq()
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

    // The ink the rest of the app's ink comes from: the title's drop, and the dock.
    val seeds = remember { Seeds() }
    // A new page is shown: whatever comes with it is simply there, not dripped in.
    remember(state.section) {
        seeds.pageAt = System.currentTimeMillis()
        0
    }
    val dockRect = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // While you scroll down to read, the dock draws back into a drop in the corner; scrolling up,
    // reaching the top, changing page or tapping the drop brings it back out.
    var dockTucked by remember { mutableStateOf(false) }
    LaunchedEffect(state.section) { dockTucked = false }
    val tuckOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (consumed.y < -6f) dockTucked = true
                else if (consumed.y > 6f) dockTucked = false
                if (available.y > 0f) dockTucked = false
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // How far down a page is read before its title has shrunk all it will.
    val collapsePx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
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
                modifier = Modifier.statusBarsPadding().padding(top = 22.dp),
                collapseOf = { i -> scrolls.getValue(sections[i]).value / collapsePx },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .nestedScroll(tuckOnScroll)
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
                            val before = pivot.slot.value
                            val next = (before - dx / width).coerceIn(from - 1f, from + 1f)
                            // Halfway across, the drop hands over to the next title: felt as a detent.
                            if (before.roundToInt() != next.roundToInt()) InkFeel.detent(view)
                            scope.launch { pivot.slot.snapTo(next) }
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
                        .onGloballyPositioned { if (state.section == Section.Effects) effectSpots.viewTop = it.positionInRoot().y }
                        .verticalScroll(scrolls.getValue(state.section))
                        // On a tablet or an open foldable, a comfortable reading width, kept to
                        // the left edge the titles start from.
                        .wrapContentWidth(Alignment.Start)
                        .widthIn(max = CONTENT_MAX + 48.dp)
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 120.dp),
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
                    when (state.section) {
                        Section.Sound -> Column {
                            // A player's Equalizer button sent you here: an ink card from the title's drop.
                            val from = rememberLastOf(state.openedFrom)
                            Seeded(visible = state.openedFrom != null, corner = 24.dp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)) {
                                if (from != null) OpenedFromRow(from, state.library, actions)
                            }
                            // The page only speaks up when something needs you; the dock says the rest.
                            if (state.error != null) {
                                StatusLine(state)
                                Gap(18.dp)
                            }
                            SoundContent(state, store, actions)
                        }
                        Section.Effects -> EffectsContent(state, actions)
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

        SeedOverlay(seeds)
        DockMessage(state.message, dockRect, actions::dismissMessage)
        Dock(state, actions, Modifier.align(Alignment.BottomEnd), tucked = dockTucked && !state.setupOpen, onUntuck = { dockTucked = false }, onStepEffects = stepEffects) { dockRect.value = it }
    }
    }
}

@Composable
private fun SoundContent(state: UiState, store: ProfileStore, actions: AppActions) {
    val p = LocalPalette.current
    val library = state.library
    val profile = library.active
    var selected by remember(profile.id) { mutableIntStateOf(-1) }
    var fitting by rememberSaveable { mutableStateOf(false) }
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
        EqHero(library, store, actions, fitting = fitting, onFit = { fitting = !fitting })
        Column(Modifier.fillMaxWidth().animateContentSize(Motion.flow())) {
            if (fitting) {
                Gap(12.dp)
                FitCard(actions)
            }
        }
        Gap(10.dp)

        val comparing by EngineStatus.comparing.collectAsState()
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

        if (eqOn) {
            Gap(20.dp)
            PreampRow(
                preampDb = profile.preampDb,
                suggested = EqResponse.suggestedPreamp(profile),
                onPreamp = { v -> store.updateActive { it.copy(preampDb = v) } },
                onAuto = { store.updateActive { it.copy(preampDb = EqResponse.suggestedPreamp(it)) } },
            )
            Gap(14.dp)

            // The filters, folded into one line: the graph is where you shape the sound; this is
            // for exact numbers and filter types.
            val filtersSource = remember { MutableInteractionSource() }
            InkSection(
                open = filtersOpen,
                header = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .clickable(interactionSource = filtersSource, indication = null, role = Role.Button) { filtersOpen = !filtersOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OpensName("Filters", filtersOpen, p.text, Modifier.weight(1f))
                        ValueNote(if (profile.bands.isEmpty()) "none" else "${profile.bands.size}")
                    }
                },
            ) {
                if (profile.bands.isEmpty()) {
                    Rest(
                        "${profile.name} has no filters, so music plays as it is. Add one, or bring in an EQ from squig.link or AutoEQ.",
                        action = "Import an EQ",
                        icon = R.drawable.ic_import,
                        onAction = actions::importFile,
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
            }
        }

        // Where this EQ comes on by itself: the headphones it's linked to, and apps of its own.
        val output by OutputWatcher.current.collectAsState()
        val linkedHere = output?.let { library.devices[it.key] == profile.id } == true
        val appCount = library.appsFor(profile.id).size
        val linksSource = remember { MutableInteractionSource() }
        InkSection(
            open = linksOpen,
            header = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable(interactionSource = linksSource, indication = null, role = Role.Button) { linksOpen = !linksOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpensName("Comes on by itself", linksOpen, p.text, Modifier.weight(1f))
                    val parts = listOfNotNull(
                        if (linkedHere) output?.name else null,
                        when (appCount) {
                            0 -> null
                            1 -> "1 app"
                            else -> "$appCount apps"
                        },
                    )
                    ValueNote(if (parts.isEmpty()) "not yet" else parts.joinToString(" · "))
                }
            },
        ) {
            output?.let { device ->
                OutputRow(device, library, onLink = { on -> store.link(device.key, if (on) profile.id else null) })
            }
            AppsList(library, actions)
        }

        if (eqOn) {
            // What's about this EQ itself, and rarely needed.
            MoreSection(moreOpen, { moreOpen = !moreOpen }, label = "More for ${profile.name}") {
                PolarityRow(profile.polarity) { pol -> store.updateActive { it.copy(polarity = pol) } }
                Gap(6.dp)
                Row {
                    PillButton("Share", actions::shareActive, icon = R.drawable.ic_share)
                    PillButton("Delete", { store.delete(profile.id) }, icon = R.drawable.ic_trash)
                }
                Gap(8.dp)
            }
        }

        if (state.blocked.isNotEmpty()) {
            Gap(18.dp)
            val names = state.blocked.joinToString()
            val one = state.blocked.size == 1
            Txt(
                "$names ${if (one) "blocks" else "block"} audio capture, so ${if (one) "it plays" else "they play"} without effects.",
                14.sp, p.sub,
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
private fun EqHero(library: Library, store: ProfileStore, actions: AppActions, fitting: Boolean, onFit: () -> Unit) {
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
    Kicker(if (eqOn) "Listening through" else "No EQ", if (eqOn) "$index of ${profiles.size}" else "off")
    Gap(6.dp)
    val source = remember { MutableInteractionSource() }
    InkSection(listOpen, header = {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = if (listOpen) "Close the list" else "Show every EQ") {
                    listOpen = !listOpen
                }
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
                HeroLine(name, if (name == "Off" && !eqOn) p.sub else p.text, size = 34.sp)
            }
            Box(Modifier.padding(start = 8.dp, top = 12.dp)) { Chevron(listOpen, p.quiet, 20.dp) }
        }
    }) {
        Column(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Import", { listOpen = false; actions.importFile() }, icon = R.drawable.ic_import)
                PillButton("Paste", { listOpen = false; actions.pasteFromClipboard() }, icon = R.drawable.ic_paste)
                PillButton(if (fitting) "Done fitting" else "Fit to a target", { listOpen = false; onFit() }, icon = R.drawable.ic_tune)
            }
        }
    }
}

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
        Txt(name, 17.sp, color, bold = chosen, maxLines = 1, modifier = Modifier.weight(1f))
        if (chosen) Note("Now", p.sub, Modifier.padding(start = 12.dp))
    }
}

/** The preamp: its value, and "auto" beside its name while it's where the EQ wants it. */
@Composable
private fun PreampRow(preampDb: Double, suggested: Double, onPreamp: (Double) -> Unit, onAuto: () -> Unit) {
    val p = LocalPalette.current
    val span = EqLimits.MAX_PREAMP - EqLimits.MIN_PREAMP
    val auto = kotlin.math.abs(preampDb - suggested) < 0.05
    val source = remember { MutableInteractionSource() }
    var held by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(Motion.flow())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Preamp", 17.sp, p.text, maxLines = 1)
            // Set by hand, the word offers to hand it back.
            dev.continuum.fx.ui.theme.Note(
                if (auto) "auto" else "back to auto",
                if (auto) p.quiet else p.sub,
                Modifier
                    .padding(start = 10.dp)
                    .clickable(interactionSource = source, indication = null, enabled = !auto, onClick = onAuto)
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Txt(Fmt.db(preampDb), 17.sp, p.text, maxLines = 1)
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
            Txt(device.name, 17.sp, p.text, maxLines = 1)
            Txt(
                when {
                    linked -> "${profile.name} comes on by itself here"
                    other != null -> "${other.name} comes on by itself here"
                    else -> "Link this EQ to come on whenever you use it"
                },
                14.sp, p.sub, maxLines = 1,
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
    ChoiceRow(scroll = false, on = Surface.Ink) {
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
                .clickable(interactionSource = source, indication = null) { open = !open }
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
                .clickable(interactionSource = source, indication = null, onClick = onToggle),
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
                if (band.type.usesGain) {
                    val gSpan = EqLimits.MAX_GAIN - EqLimits.MIN_GAIN
                    SliderField("Gain", Fmt.db(band.gainDb), ((band.gainDb - EqLimits.MIN_GAIN) / gSpan).toFloat(), hear = "Up brings this part of the sound forward; down sets it back.") { t ->
                        onChange(band.copy(gainDb = Fmt.round1(EqLimits.MIN_GAIN + t * gSpan)))
                    }
                }
                SliderField("Q", Fmt.q(band.q), Fmt.qToT(band.q), hear = "Right narrows it to a few notes; left spreads it wide and gentle.") { t ->
                    onChange(band.copy(q = Fmt.roundQ(Fmt.tToQ(t))))
                }
                Gap(8.dp)
                PillButton("Remove filter", onRemove, icon = R.drawable.ic_trash, on = Surface.Card, height = 40.dp)
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
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).animateContentSize(Motion.flow())) {
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
 * The ink dock. It holds the power control, and grows straight up into the setup
 * sheet rather than opening a separate panel: the same ink, reshaped.
 */
@Composable
private fun Dock(
    state: UiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    tucked: Boolean = false,
    onUntuck: () -> Unit = {},
    onStepEffects: () -> Unit = {},
    onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val p = LocalPalette.current
    val config = LocalConfiguration.current
    val openHeight = (config.screenHeightDp - 120).coerceIn(420, 660).dp
    val height by animateDpAsState(
        when {
            state.setupOpen -> openHeight
            tucked -> 60.dp
            else -> 76.dp
        },
        Motion.flow(), label = "dockHeight",
    )
    val side by animateDpAsState(if (state.setupOpen) 8.dp else 12.dp, Motion.flow(), label = "dockSide")
    // Tucked, it gathers towards the corner into a round drop; its full width is the screen's
    // (to a comfortable reading width on large screens).
    val full = minOf(config.screenWidthDp.dp, CONTENT_MAX + 24.dp) - side * 2
    val width by animateDpAsState(if (tucked) 60.dp else full, Motion.flow(), label = "dockWidth")
    val shape = RoundedCornerShape(38.dp)
    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = side, end = side, bottom = 10.dp)
            .width(width)
            .height(height)
            .onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .then(if (p.dark) Modifier.border(1.dp, p.edge, shape) else Modifier)
            .clip(shape)
            .background(p.ink),
    ) {
        Crossfade(targetState = Triple(state.setupOpen, state.section, tucked), label = "dockContent") { (open, section, small) ->
            when {
                small -> TuckedDock(state, section, onUntuck)
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

/** The dock, tucked into a drop: what it's for, and whether it's on. Tap to bring it back. */
@Composable
private fun TuckedDock(state: UiState, section: Section, onUntuck: () -> Unit) {
    val p = LocalPalette.current
    val volume by dev.continuum.fx.volume.VolumeSettings.state.collectAsState()
    val (icon, on) = when (section) {
        Section.Sound, Section.Effects -> R.drawable.ic_power to state.running
        Section.Island -> R.drawable.ic_island to islandOn(state)
        Section.Volume -> R.drawable.ic_volume to volumeOn(state, volume)
        Section.Keyboard -> R.drawable.ic_keyboard to state.keyboard.selected
    }
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onUntuck)
            .semantics { contentDescription = "Show the dock" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.squash(source).size(44.dp).clip(CircleShape).background(if (on) p.onInk else p.ink),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (on) p.ink else p.onInk))
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
            if (onStepEffects != null && (ready || state.running)) {
                val active = activeEffects(fx, spatialOn.enabled).size
                EffectsDockText(
                    title = when {
                        !fx.enabled -> "Effects off"
                        active == 0 -> "No effects on"
                        else -> "$active ${if (active == 1) "effect" else "effects"} active"
                    },
                    subtitle = when {
                        active == 0 -> if (fx.enabled) "Switch one on below" else "The EQ plays on its own"
                        !state.running -> "Start processing to hear them"
                        else -> "Hold to compare · tap to find them"
                    },
                    canCompare = state.running && active > 0,
                    onStep = if (active > 0) onStepEffects else null,
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
            Txt(title, 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(subtitle, 14.sp, p.onInkSub, maxLines = 1)
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
            Txt(if (comparing) "Without them" else title, 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt(if (comparing) "Let go to hear them again" else subtitle, 14.sp, p.onInkSub, maxLines = 1)
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
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(if (direct) "Set up the EQ" else "Set up capture", 24.sp, p.onInk, bold = true, modifier = Modifier.weight(1f))
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
            PillButton("Allow", actions::requestRecord, primary = !direct, on = Surface.Ink)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            StepRow(++step, priv.notifications, "Notifications", "Shows when processing is on, with a stop button.", optional = true) {
                PillButton("Allow", actions::requestNotifications, on = Surface.Ink)
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
            )
        }
        // Rarely wanted, so kept here rather than on a page.
        Gap(28.dp)
        Note("Backup", p.onInkSub)
        Gap(6.dp)
        Txt(
            "Everything you've set up in one file: your EQs and which apps and headphones use them, the effects, the island, " +
                "the volume panel and each app's volume. Restoring replaces what's here, and Continuum Kit restarts to load it.",
            14.sp, p.onInkSub,
        )
        Gap(6.dp)
        Row {
            PillButton("Save a backup", actions::saveBackup, icon = R.drawable.ic_share, on = Surface.Ink)
            PillButton("Restore", actions::restoreBackup, icon = R.drawable.ic_import, on = Surface.Ink)
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
            // Done is "on", so white with a tick, as a switch is; green is kept for what's live.
            Modifier.size(32.dp).clip(CircleShape).background(if (done) p.onInk else p.inkTonal),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Image(painterResource(R.drawable.ic_check), contentDescription = "Done", modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(p.ink))
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

/** Whether processing can start the [mode] way: Direct needs only to find apps' sound. */
private fun readyFor(mode: EngineMode, priv: PrivilegeState): Boolean =
    if (mode == EngineMode.Direct) priv.dump else priv.ready
