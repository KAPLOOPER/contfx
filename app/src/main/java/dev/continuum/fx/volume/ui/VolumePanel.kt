package dev.continuum.fx.volume.ui

import android.graphics.Rect as AndroidRect
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.ui.LocalPlateSky
import dev.continuum.fx.island.ui.UsesSky
import dev.continuum.fx.island.ui.drawPlateSky
import dev.continuum.fx.island.ui.grainOver
import dev.continuum.fx.island.ui.rememberPlateSky
import dev.continuum.fx.ui.eidolon.Eidolon
import dev.continuum.fx.ui.eidolon.LocalEidolonColors
import dev.continuum.fx.ui.eidolon.Mincho
import dev.continuum.fx.ui.eidolon.PaperColors
import dev.continuum.fx.ui.eidolon.PasteShadow
import dev.continuum.fx.ui.eidolon.VoidColors
import dev.continuum.fx.ui.theme.EidolonFaces
import dev.continuum.fx.ui.theme.LocalFaces
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.drawscope.rotate
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.island.ui.lerpF
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.volume.AppVolumes
import dev.continuum.fx.volume.Booster
import dev.continuum.fx.volume.BoostPolicy
import dev.continuum.fx.volume.PanelUi
import dev.continuum.fx.volume.VolStream
import dev.continuum.fx.volume.VolumeModel
import dev.continuum.fx.volume.VolumeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The volume panel, printed as the app's poster is, beside the volume buttons. A key press brings
 * out just a strip of the paper against the edge, a thread of sky up it to the level. Press it and
 * it comes off the edge as a tall sheet hung on a fan of threads. The level is a window in it: the
 * sky from the foot of the sheet up to where the volume is, its head drawn to a point like a
 * star's arm. The number is set large and turned down the sheet, as the poster sets its hour, in
 * ink on the paper and in white where the window has risen behind it, and the stream's steps are
 * ruled up one side. A tab of paper comes off each end: the ringer above, and below, the way into
 * everything. Opened out, the sheet spreads into a poster of every stream; folding away, it all
 * goes back into the edge it came from.
 */
/** True only in previews: the panel drawn already at rest, with nothing left to animate in. */
internal val LocalPanelStill = staticCompositionLocalOf { false }

@Composable
fun VolumePanel(
    ui: PanelUi,
    onBounds: (AndroidRect?) -> Unit,
    onSettled: (Boolean) -> Unit,
    openApp: () -> Unit,
) {
    // Printed as the app is: on paper by day, in the negative when the phone is dark.
    CompositionLocalProvider(
        LocalFaces provides EidolonFaces,
        LocalEidolonColors provides if (isSystemInDarkTheme()) VoidColors else PaperColors,
    ) {
        CompositionLocalProvider(LocalPlateSky provides rememberPlateSky()) {
            PrintedPanel(ui, onBounds, onSettled, openApp)
        }
    }
}

@Composable
private fun PrintedPanel(
    ui: PanelUi,
    onBounds: (AndroidRect?) -> Unit,
    onSettled: (Boolean) -> Unit,
    openApp: () -> Unit,
) {
    val colors = Eidolon.colors
    val sky = LocalPlateSky.current
    val density = LocalDensity.current
    val view = LocalView.current
    fun px(dp: Float) = dp * density.density

    val prefs by VolumeSettings.state.collectAsState()
    val snap by VolumeModel.state.collectAsState()
    val boostSteps by Booster.steps.collectAsState()
    val armed by Booster.armed.collectAsState()
    val apps by AppVolumes.playing.collectAsState()

    val visible = ui.visible.value
    val expanded = ui.expanded.value
    val opened = ui.opened.value || expanded
    val stream = ui.stream.value
    val holding = ui.holding.value
    val keyTick = ui.keyTick.intValue
    val stripW = ui.stripW.intValue.toFloat()
    val screenH = ui.screenH.intValue.toFloat()
    if (stripW <= 0f || screenH <= 0f) return

    // ---- Motion ----
    // Held still for a drawing of it (the previews), it starts where it would have come to rest.
    val still = LocalPanelStill.current && visible
    val out = remember { Animatable(if (still) 1f else 0f) }
    val buds = remember { Animatable(if (still && opened) 1f else 0f) }
    val open = remember { Animatable(if (still && expanded) 1f else 0f) }
    val tell = remember { Animatable(if (still && opened && !expanded) 1f else 0f) }
    // 0 the sliver against the edge, 1 the capsule.
    val wide = remember { Animatable(if (opened) 1f else 0f) }
    val fill = remember { Animatable(snap[stream].fraction) }
    var touchTick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val settled by rememberUpdatedState(onSettled)

    LaunchedEffect(visible) {
        if (visible) {
            settled(true)
            launch { out.animateTo(1f, Motion.flow()) }
            delay(90)
            buds.animateTo(1f, Motion.flow())
        } else {
            launch { tell.animateTo(0f, Motion.snap()) }
            launch { open.animateTo(0f, Motion.snap()) }
            buds.animateTo(0f, Motion.snap())
            out.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 380f))
            wide.snapTo(0f)
            settled(false)
        }
    }
    LaunchedEffect(opened, visible) {
        if (visible) wide.animateTo(if (opened) 1f else 0f, Motion.flow())
    }
    LaunchedEffect(expanded) {
        if (visible || !expanded) open.animateTo(if (expanded) 1f else 0f, Motion.flow())
    }
    // Leaves on its own: shortly after the last press or touch, longer when opened out.
    LaunchedEffect(visible, expanded, keyTick, touchTick, holding, dragging) {
        if (!visible || holding || dragging) return@LaunchedEffect
        delay(if (expanded) 7_000L else if (opened) 2_300L else 1_500L)
        ui.expanded.value = false
        ui.opened.value = false
        ui.visible.value = false
    }
    // The number drop speaks up while the level moves, then merges back.
    LaunchedEffect(keyTick, dragging, expanded, opened) {
        if (!visible || expanded || !opened) {
            tell.animateTo(0f, Motion.snap())
            return@LaunchedEffect
        }
        tell.animateTo(1f, Motion.flow())
        if (!dragging && !holding) {
            delay(1_100L)
            tell.animateTo(0f, Motion.flow())
        }
    }
    val level = snap[stream]
    val target = dragFraction ?: level.fraction
    LaunchedEffect(target) { fill.animateTo(target, spring(dampingRatio = 0.5f, stiffness = 260f)) }
    // Reaching full, the top of the liquid glints.
    val full = target >= 0.999f
    val wasFull = remember { booleanArrayOf(full) }
    val fullGlint = remember { Animatable(1f) }
    LaunchedEffect(full) {
        val reached = full && !wasFull[0]
        wasFull[0] = full
        if (reached && visible) {
            fullGlint.snapTo(0f)
            fullGlint.animateTo(1f, tween(600, easing = LinearEasing))
        }
    }
    val boostMax = prefs.boostSteps.coerceAtLeast(1)
    val boostShown by animateFloatAsState(
        if (stream == VolStream.Media) boostSteps / boostMax.toFloat() else 0f,
        Motion.flow(),
        label = "boost",
    )

    // ---- Geometry, in the draw window's pixels ----
    val left = prefs.left
    val w = px(58f)
    val baseH = min(px(232f), screenH * 0.42f)
    val extraH = boostShown * px(52f)
    val margin = px(12f)
    val d = px(46f)
    // The stars off each end keep a clear gap from the column.
    val gap = px(16f)
    val stackH = d + gap + baseH + gap + d
    // Kept clear of the screen's ends; on a short (landscape) screen the room shrinks, never inverts.
    val room = min(px(64f), ((screenH - stackH) / 2f).coerceAtLeast(0f))
    val cyLow = room + stackH / 2f
    val cyHigh = screenH - room - stackH / 2f
    val cy = if (cyLow <= cyHigh) (screenH * prefs.height).coerceIn(cyLow, cyHigh) else screenH / 2f
    val capBottom = cy + baseH / 2f
    val capTop = cy - baseH / 2f - extraH

    /** A rect [near] pixels in from the panel's edge, [width] wide. */
    fun side(near: Float, width: Float, top: Float, bottom: Float): Rect =
        if (left) Rect(near, top, near + width, bottom) else Rect(stripW - near - width, top, stripW - near, bottom)

    val o = out.value
    val e = open.value.coerceIn(0f, 1.05f)
    val b = buds.value
    val wv = wide.value.coerceIn(0f, 1.05f)
    val wc = wv.coerceIn(0f, 1f)
    // The sliver: ink grown out of the edge, a little of it past the edge so it's flush there.
    val sliverW = px(8f)
    // Most of it is past the edge, so the part you see sits flush.
    val past = px(24f)
    val sliverWidth = past + sliverW * o.coerceIn(0f, 1f)
    // The capsule: hidden, it sits just past the edge; it slides out as it emerges.
    val capNear = margin - (1f - o) * (w + margin + px(10f))
    val near = lerpF(-past, capNear, wv)
    // The sliver is a little shorter than the capsule; opening, it grows to the capsule's length.
    val sliverH = baseH * 0.78f
    val rTop = lerpF(cy - sliverH / 2f - extraH * 0.78f, capTop, wc)
    val rBottom = lerpF(cy + sliverH / 2f, capBottom, wc)
    val capsule = side(near, lerpF(sliverWidth, w, wv), rTop, rBottom)
    // At rest the sliver is drawn crisp, as itself: flush with the edge, its ends rounded off,
    // a hairline rule round it.
    val crisp = wc < 0.02f && e < 0.01f
    val crispRect = side(-sliverW, sliverW + sliverW * o.coerceIn(0f, 1f), rTop, rBottom)

    val cardW = min(stripW - margin * 2f, px(360f))
    val cardH = min(
        screenH - px(112f),
        px(12f + 62f + 10f + 42f + 8f + 42f + 8f) + VolStream.entries.size * px(54f) +
            (if (apps.isNotEmpty()) px(22f) + apps.size * px(54f) else 0f) + px(18f),
    )
    val cardTop = (cy - cardH / 2f).coerceIn(px(56f), (screenH - px(56f) - cardH).coerceAtLeast(px(56f)))
    val card = side(margin, cardW, cardTop, cardTop + cardH)
    val body = lerpRect(capsule, card, e)
    val radius = lerpF(capsule.width / 2f, px(30f), e.coerceIn(0f, 1f))

    // Buds come out of the capsule's ends, and go back into the sheet when it opens.
    val budK = (b * (1f - e) * wc).coerceIn(0f, 1.1f)
    val budX = capsule.center.x
    val ringerCy = lerpF(capsule.top + d / 2f, capsule.top - gap - d / 2f, budK)
    val moreCy = lerpF(capsule.bottom - d / 2f, capsule.bottom + gap + d / 2f, budK)
    val budR = d / 2f * (0.55f + 0.45f * budK.coerceIn(0f, 1f))
    val ringerBud = Rect(Offset(budX, ringerCy), budR)
    val moreBud = Rect(Offset(budX, moreCy), budR)

    // The number drop: off the capsule's inner side, level with the liquid.
    val measurer = rememberTextMeasurer()
    val label = when {
        armed && stream == VolStream.Media -> "Again for more"
        boostSteps > 0 && stream == VolStream.Media -> "${BoostPolicy.percent(boostSteps)}%"
        else -> "${((dragFraction ?: level.fraction) * 100f).roundToInt()}"
    }
    val labelStyle = TextStyle(color = colors.ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    val labelLayout = remember(label) { measurer.measure(label, labelStyle) }
    val tellW = labelLayout.size.width + px(22f)
    val tellH = px(30f)
    val plain = !(armed && stream == VolStream.Media) && !(boostSteps > 0 && stream == VolStream.Media)
    val t = if (plain) 0f else tell.value.coerceIn(0f, 1.1f) * (1f - e) * o.coerceIn(0f, 1f) * wc
    // The level itself, set large and turned down the column as the poster sets its hour.
    val bigStyle = TextStyle(
        fontSize = 56.sp, fontFamily = GroteskTight, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1.7).sp, fontFeatureSettings = "tnum",
    )
    val bigText = "${((dragFraction ?: level.fraction) * 100f).roundToInt()}"
    val bigLayout = remember(bigText) { measurer.measure(bigText, bigStyle) }
    val markLayout = remember { measurer.measure("音", TextStyle(fontSize = 15.sp, fontFamily = Mincho, fontWeight = FontWeight.Medium)) }
    // The liquid sits inside: a thread of it in the sliver, a column in the capsule.
    val ins = lerpF(px(2.2f), px(5f), wc)
    val innerTop = capsule.top + ins
    val innerBottom = capsule.bottom - ins
    // The liquid runs from the bottom to the top of the capsule proper; a boost tops it up above.
    val fullY = rBottom - lerpF(sliverH, baseH, wc) + ins
    val liquidY = lerpF(innerBottom, fullY, fill.value.coerceIn(0f, 1f))
    val surfaceY = if (extraH > 0.5f) innerTop else liquidY
    val tellCy = if (innerBottom - innerTop > tellH) surfaceY.coerceIn(innerTop + tellH / 2f, innerBottom - tellH / 2f) else capsule.center.y
    val tellNear = lerpF(near + w / 2f - tellW / 2f, near + w + px(18f), t)
    val tellRect = side(tellNear, tellW, tellCy - tellH / 2f, tellCy + tellH / 2f)

    // Touchable: the sliver (a finger's width of the edge), the capsule and its buds, or the sheet.
    val budCx = side(margin, w, capTop, capBottom).center.x
    val boundsKey = when {
        !visible -> null
        expanded -> card
        opened -> unionOf(side(0f, margin + w, capTop, capBottom), Rect(Offset(budCx, capTop - gap - d / 2f), d / 2f), Rect(Offset(budCx, capBottom + gap + d / 2f), d / 2f))
        else -> side(0f, px(30f), capTop - px(6f), capBottom + px(6f))
    }
    val reportBounds by rememberUpdatedState(onBounds)
    LaunchedEffect(boundsKey) {
        reportBounds(boundsKey?.let { r -> AndroidRect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) })
    }

    val edgeX = if (left) 0f else stripW
    if (o <= 0.001f && !visible) return

    val streamPainter = painterResource(streamIcon(stream, level.index == level.min, snap.ringer))
    val ringerPainter = painterResource(ringerIcon(snap.ringer))
    val morePainter = painterResource(R.drawable.ic_tune)
    val dark = colors.isDark
    val bodyPath = remember { Path() }
    val windowPath = remember { Path() }
    val starPath = remember { Path() }
    // A window is open on the sky whenever the column or the sheet is out.
    if (visible && opened) UsesSky()

    /** The level's window: sky from the foot of [inner] up to [y], its head drawn up to a point like a star's arm. */
    fun levelWindow(inner: Rect, y: Float, into: Path): Path {
        val rise = inner.width * 0.34f
        val shoulder = (y + rise * 0.5f).coerceAtMost(inner.bottom)
        val peak = (y - rise * 0.5f).coerceAtLeast(inner.top)
        val k = 1f - Star.KEEN
        into.rewind()
        into.moveTo(inner.left, inner.bottom)
        into.lineTo(inner.left, shoulder)
        into.cubicTo(inner.left + inner.width / 2f * (1f - k), shoulder, inner.center.x, peak + (shoulder - peak) * k, inner.center.x, peak)
        into.cubicTo(inner.center.x, peak + (shoulder - peak) * k, inner.right - inner.width / 2f * (1f - k), shoulder, inner.right, shoulder)
        into.lineTo(inner.right, inner.bottom)
        into.close()
        return into
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            fun paper(r: Rect, fill: Color = colors.slip) {
                bodyPath.rewind()
                bodyPath.addRect(r)
                translate(px(2f), px(3f)) { drawPath(bodyPath, PasteShadow.copy(alpha = if (dark) 0.6f else 0.34f)) }
                drawPath(bodyPath, fill)
                grainOver(bodyPath, dark)
            }
            if (crisp) {
                // The sliver: a strip of the paper flush with the edge, a thread of sky up it to the level.
                val r = crispRect
                val visibleW = r.width - sliverW
                if (visibleW > 0.5f) {
                    val strip = side(0f, visibleW, r.top, r.bottom)
                    paper(strip)
                    val lineW = min(px(3f), visibleW * 0.5f)
                    val x = strip.center.x
                    val inTop = strip.top + px(5f)
                    val inBottom = strip.bottom - px(5f)
                    val y = lerpF(inBottom, inTop, fill.value.coerceIn(0f, 1f))
                    drawLine(colors.ink.copy(alpha = 0.2f), Offset(x, inTop), Offset(x, inBottom), px(1f))
                    drawLine(if (stream == VolStream.Call) colors.signal else colors.ultramarine, Offset(x, y), Offset(x, inBottom), lineW)
                }
                return@Canvas
            }
            val budA = ((1f - e) * wc).coerceIn(0f, 1f)
            val threadInk = if (dark) colors.inkSoft else colors.ink
            // It hangs off the screen's edge on threads, as the island's posters hang from the eye.
            if (budA > 0.02f) {
                val nearX = if (left) body.left else body.right
                for (i in 0 until 5) {
                    val y = body.top + body.height * (0.14f + 0.18f * i)
                    drawLine(threadInk.copy(alpha = 0.9f * budA), Offset(edgeX, body.center.y), Offset(nearX, y), px(0.9f))
                }
            }
            paper(body)
            // The ringer and the way into everything: a tab of paper off each end.
            if (budK > 0.02f && e < 0.98f) {
                listOf(ringerBud, moreBud).forEach { bud ->
                    val half = budR * 0.92f
                    paper(Rect(bud.center.x - half, bud.center.y - half, bud.center.x + half, bud.center.y + half), colors.slipRaised)
                }
            }
            // Anything more than the number (going past 100%) is typed on a tag tied beside the level.
            if (t > 0.02f) {
                val fromX = if (left) capsule.right else capsule.left
                val toX = if (left) tellRect.left else tellRect.right
                drawLine(threadInk.copy(alpha = t.coerceIn(0f, 1f)), Offset(fromX, tellRect.center.y), Offset(toX, tellRect.center.y), px(0.9f))
                paper(tellRect, colors.slipRaised)
            }
        }

        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - e * 2.2f).coerceIn(0f, 1f) * ((o - 0.15f) / 0.35f).coerceIn(0f, 1f) }) {
            if (crisp) return@Canvas
            val box = capsule
            val inner = Rect(box.left + ins, innerTop, box.right - ins, innerBottom)
            if (inner.width <= 1f || inner.height <= 1f) return@Canvas
            val shownY = if (extraH > 0.5f) fullY else liquidY
            levelWindow(inner, shownY, windowPath)
            // The level is a window: the sky from the foot of the column up to it.
            if (sky != null) drawPlateSky(sky, windowPath, Offset(inner.left - sky.size.width * 0.3f, inner.bottom - sky.size.height * 0.8f)) else drawPath(windowPath, colors.ultramarine)
            if (stream == VolStream.Call) drawPath(windowPath, colors.signal.copy(alpha = 0.28f))
            // Past 100%, the one red prints above the window, a star for each step.
            if (extraH > 0.5f) {
                drawRect(colors.signal, Offset(inner.left, innerTop), Size(inner.width, (fullY - inner.width * 0.17f - innerTop).coerceAtLeast(0f)))
                val steps = if (stream == VolStream.Media) boostSteps else 0
                if (steps > 0 && wc > 0.5f) {
                    val span = fullY - inner.width * 0.17f - innerTop
                    val each = span / steps
                    val r = min(px(4.5f), each * 0.36f)
                    for (i in 0 until steps) {
                        drawPath(Star.path(Offset(inner.center.x, innerTop + span - each * (i + 0.5f)), r, r, Star.KEEN, into = starPath), Color.White)
                    }
                }
            }
            val a = ((wc - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (a > 0f) {
                /** Draws [draw] in ink on the paper and in the window's own white where the sky is behind it. */
                fun knockedOut(draw: DrawScope.(Color) -> Unit) {
                    clipPath(windowPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) { draw(colors.ink.copy(alpha = a)) }
                    clipPath(windowPath) { draw(colors.onWindow.copy(alpha = a)) }
                }
                // The stream's own steps, ruled up one side like a gauge.
                val steps = level.steps.coerceIn(1, 40)
                knockedOut { c ->
                    for (i in 0..steps) {
                        val y = lerpF(rBottom - ins, fullY, i / steps.toFloat())
                        val long = i % 5 == 0
                        drawLine(c.copy(alpha = c.alpha * (if (long) 0.8f else 0.45f)), Offset(inner.left, y), Offset(inner.left + px(if (long) 7f else 4f), y), px(1f))
                    }
                }
                // The number, down the column.
                if (extraH <= 0.5f) {
                    val pivot = Offset(inner.center.x + px(4f), (fullY + innerBottom) / 2f - px(4f))
                    knockedOut { c ->
                        rotate(90f, pivot) {
                            drawText(bigLayout, color = c, topLeft = Offset(pivot.x - bigLayout.size.width / 2f, pivot.y - bigLayout.size.height / 2f))
                        }
                    }
                }
                // What it is: sound, in one character, at the head; its own mark at the foot.
                knockedOut { c ->
                    drawText(markLayout, color = c, topLeft = Offset(inner.right - markLayout.size.width - px(4f), fullY + px(2f)))
                }
                val iconSize = px(20f)
                val iconTop = innerBottom - px(10f) - iconSize
                val iconLeft = inner.right - iconSize - px(4f)
                knockedOut { c -> icon(streamPainter, iconLeft, iconTop, iconSize, c) }
            }
            if (budK > 0.3f) {
                val ba = ((budK - 0.3f) / 0.6f).coerceIn(0f, 1f)
                val sz = px(18f)
                icon(ringerPainter, ringerBud.center.x - sz / 2f, ringerBud.center.y - sz / 2f, sz, (if (snap.ringer == AudioManager.RINGER_MODE_SILENT) colors.signal else colors.ink).copy(alpha = ba))
                icon(morePainter, moreBud.center.x - sz / 2f, moreBud.center.y - sz / 2f, sz, colors.ink.copy(alpha = ba))
            }
            if (t > 0.6f) {
                val ta = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
                drawText(
                    labelLayout,
                    color = (if (boostSteps > 0 && stream == VolStream.Media) colors.signal else colors.ink).copy(alpha = ta),
                    topLeft = Offset(tellRect.center.x - labelLayout.size.width / 2f, tellRect.center.y - labelLayout.size.height / 2f),
                )
            }
        }

        // ---- Touch, collapsed: press the sliver to open it (and keep going to drag the level),
        // drag or tap the capsule, tap the buds. ----
        if (visible && !expanded) {
            val hapticsNow by rememberUpdatedState(prefs.haptics)
            val haptics = prefs.haptics
            val levelNow by rememberUpdatedState(level)
            val streamNow by rememberUpdatedState(stream)
            val openedNow by rememberUpdatedState(opened)
            val innerBottomNow by rememberUpdatedState(capBottom - px(5f))
            val fullYNow by rememberUpdatedState(capBottom - baseH + px(5f))
            val touchTop = capTop - px(4f)
            val touchTopNow by rememberUpdatedState(touchTop)
            fun openUp() {
                if (openedNow) return
                if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ui.opened.value = true
            }
            fun setFrom(y: Float) {
                val innerBottom = innerBottomNow
                val fullY = fullYNow
                val f = ((innerBottom - y) / (innerBottom - fullY)).coerceIn(0f, 1f)
                val lv = levelNow
                val index = lv.indexAt(f)
                val before = lv.indexAt(dragFraction ?: lv.fraction)
                dragFraction = (index - lv.min).toFloat() / lv.steps
                if (index != lv.index) VolumeModel.set(streamNow, index)
                if (index != before && hapticsNow) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            val pad = side(0f, margin + w, touchTop, capBottom + px(4f))
            Box(
                Modifier
                    .offset { IntOffset(pad.left.roundToInt(), pad.top.roundToInt()) }
                    .size(with(density) { pad.width.toDp() }, with(density) { pad.height.toDp() })
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { pos ->
                                dragging = true
                                touchTick++
                                openUp()
                                setFrom(touchTopNow + pos.y)
                            },
                            onDragEnd = {
                                dragging = false
                                dragFraction = null
                                touchTick++
                            },
                            onDragCancel = {
                                dragging = false
                                dragFraction = null
                            },
                        ) { change, _ ->
                            change.consume()
                            setFrom(touchTopNow + change.position.y)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            touchTick++
                            // A tap on the sliver opens it; on the capsule, it sets the level there.
                            if (!openedNow) {
                                openUp()
                            } else {
                                setFrom(touchTopNow + pos.y)
                                dragFraction = null
                            }
                        }
                    },
            )
            if (opened) {
                BudTouch(ringerBud, d, density.density) {
                    touchTick++
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    VolumeModel.setRinger(nextRinger(snap.ringer))
                }
                BudTouch(moreBud, d, density.density) {
                    touchTick++
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    ui.expanded.value = true
                }
            }
        }

        // ---- The sheet ----
        if (e > 0.55f) {
            Box(
                Modifier
                    .offset { IntOffset(card.left.roundToInt(), card.top.roundToInt()) }
                    .size(with(density) { card.width.toDp() }, with(density) { card.height.toDp() })
                    .graphicsLayer { alpha = ((e - 0.55f) / 0.4f).coerceIn(0f, 1f) },
            ) {
                VolumeSheet(
                    snap = snap,
                    apps = apps,
                    boostSteps = boostSteps,
                    haptics = prefs.haptics,
                    onTouch = { touchTick++ },
                    onDone = {
                        ui.expanded.value = false
                        ui.opened.value = false
                        ui.visible.value = false
                    },
                    openApp = openApp,
                )
            }
        }
    }
}

/** A touch target over a bud, a little larger than it looks. */
@Composable
private fun BudTouch(bud: Rect, d: Float, density: Float, onTap: () -> Unit) {
    val reach = d / 2f + 6f * density
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .offset { IntOffset((bud.center.x - reach).roundToInt(), (bud.center.y - reach).roundToInt()) }
            .size(with(androidx.compose.ui.unit.Density(density)) { (reach * 2f).toDp() })
            .pointerInput(Unit) { detectTapGestures { tap() } },
    )
}

private fun DrawScope.icon(painter: Painter, x: Float, y: Float, size: Float, tint: Color) {
    translate(x, y) {
        with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
    }
}

internal fun streamIcon(stream: VolStream, silent: Boolean, ringer: Int): Int = when (stream) {
    VolStream.Media -> if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume
    VolStream.Call -> R.drawable.ic_call
    VolStream.Ring -> ringerIcon(if (silent) AudioManager.RINGER_MODE_VIBRATE else ringer)
    VolStream.Notification -> R.drawable.ic_notification
    VolStream.Alarm -> R.drawable.ic_alarm
}

internal fun ringerIcon(mode: Int): Int = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_bell_off
    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vibrate
    else -> R.drawable.ic_bell
}

/** Ring, then vibrate, then silent, then round again, as Android's own panel goes. */
internal fun nextRinger(mode: Int): Int = when (mode) {
    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
    else -> AudioManager.RINGER_MODE_NORMAL
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    lerpF(a.left, b.left, t),
    lerpF(a.top, b.top, t),
    lerpF(a.right, b.right, t),
    lerpF(a.bottom, b.bottom, t),
)

private fun unionOf(vararg r: Rect): Rect = Rect(
    r.minOf { it.left },
    r.minOf { it.top },
    r.maxOf { it.right },
    r.maxOf { it.bottom },
)
