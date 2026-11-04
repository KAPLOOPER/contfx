package dev.continuum.fx.volume.ui

import android.graphics.Rect as AndroidRect
import android.media.AudioManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import dev.continuum.fx.island.ui.glint
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import dev.continuum.fx.island.ui.Goo
import dev.continuum.fx.island.ui.InkColors
import dev.continuum.fx.island.ui.lerpF
import dev.continuum.fx.ui.theme.Libron
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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The rim drawn round the ink, so it still has an edge on a black screen. */
private val Rim = Color(0xFF3A3A38)

/**
 * The volume panel, in ink, growing out of the screen edge beside the volume buttons. A key
 * press brings out just a sliver against the edge, filled to the level. Press it and it swells
 * off the edge into a capsule you drag, with a drop pinching off its side to say the number and
 * two more separating from its ends: the ringer above, and below, the way into everything.
 * Opened out, the capsule spreads into a sheet. Folding away, everything merges back and sinks
 * into the edge, so ink only ever separates and merges, and always comes from the edge.
 */
@Composable
fun VolumePanel(
    ui: PanelUi,
    onBounds: (AndroidRect?) -> Unit,
    onSettled: (Boolean) -> Unit,
    openApp: () -> Unit,
) {
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
    val out = remember { Animatable(0f) }
    val buds = remember { Animatable(0f) }
    val open = remember { Animatable(0f) }
    val tell = remember { Animatable(0f) }
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
    // Far enough apart that the goo lets the drops go at rest; they only join while moving.
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
    // Most of it is past the edge, so the goo keeps the part you see full and flush.
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
    // a hairline rim round it. The goo takes over once it starts to open.
    val crisp = wc < 0.02f && e < 0.01f
    val crispRect = side(-sliverW, sliverW + sliverW * o.coerceIn(0f, 1f), rTop, rBottom)

    val cardW = min(stripW - margin * 2f, px(360f))
    val cardH = min(
        screenH - px(112f),
        px(16f + 44f + 12f + 44f + 10f + 52f) + VolStream.entries.size * px(50f) +
            (if (apps.isNotEmpty()) px(30f) + apps.size * px(50f) else 0f) + px(30f),
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
    val labelStyle = TextStyle(color = InkColors.on, fontSize = 14.sp, fontFamily = Libron, fontWeight = FontWeight.Bold)
    val labelLayout = remember(label) { measurer.measure(label, labelStyle) }
    val tellW = labelLayout.size.width + px(26f)
    val tellH = px(32f)
    val t = tell.value.coerceIn(0f, 1.1f) * (1f - e) * o.coerceIn(0f, 1f) * wc
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

    val goo = remember(density) { if (Build.VERSION.SDK_INT >= 31) Goo.effect(px(9f)) else null }
    val rim = goo
    val edgeX = if (left) 0f else stripW
    // Where the ink leaves the edge: a lip as the capsule comes out, or steps off the sliver.
    val lipW = px(22f) * max(
        sin(PI.toFloat() * o.coerceIn(0f, 1f)) * wc,
        sin(PI.toFloat() * wc) * o.coerceIn(0f, 1f),
    ).coerceAtLeast(0f)
    val lip = if (left) Rect(edgeX - px(4f), cy - baseH * 0.32f, edgeX + lipW, cy + baseH * 0.32f)
    else Rect(edgeX - lipW, cy - baseH * 0.32f, edgeX + px(4f), cy + baseH * 0.32f)
    if (o <= 0.001f && !visible) return

    fun DrawScope.ink(color: Color, grow: Float) {
        if (lipW > 0.5f) drawRoundRect(color, lip.topLeft - Offset(grow, grow), Size(lip.width + grow * 2, lip.height + grow * 2), CornerRadius(lipW))
        drawRoundRect(color, body.topLeft - Offset(grow, grow), Size(body.width + grow * 2, body.height + grow * 2), CornerRadius(radius + grow))
        if (budK > 0.02f && e < 0.98f) {
            drawCircle(color, budR + grow, ringerBud.center)
            drawCircle(color, budR + grow, moreBud.center)
        }
        if (t > 0.02f) {
            drawRoundRect(color, tellRect.topLeft - Offset(grow, grow), Size(tellRect.width + grow * 2, tellRect.height + grow * 2), CornerRadius(tellH / 2f + grow))
        }
    }

    // The blurred layers cover only the ink and a margin for the blur, not the whole window.
    val area = unionOf(body, lip, ringerBud, moreBud, tellRect).inflate(px(28f))
    val areaW = with(density) { area.width.toDp() }
    val areaH = with(density) { area.height.toDp() }

    Box(Modifier.fillMaxSize()) {
        if (crisp) {
            Canvas(Modifier.fillMaxSize()) {
                val r = crispRect
                val g = px(1.2f)
                val rad = min(r.width, r.height) / 2f
                drawRoundRect(Rim, r.topLeft - Offset(g, g), Size(r.width + g * 2f, r.height + g * 2f), CornerRadius(rad + g))
                drawRoundRect(InkColors.ink, r.topLeft, r.size, CornerRadius(rad))
            }
        }
        // The rim, then the ink: both through the goo, so they fuse into one liquid shape.
        if (!crisp) Canvas(
            Modifier
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(areaW, areaH)
                .graphicsLayer { renderEffect = rim },
        ) { translate(-area.left, -area.top) { ink(Rim, px(1.3f)) } }
        if (!crisp) Canvas(
            Modifier
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(areaW, areaH)
                .graphicsLayer { renderEffect = goo },
        ) { translate(-area.left, -area.top) { ink(InkColors.ink, 0f) } }

        // What sits on the ink: the liquid, the icons and the number.
        val streamPainter = painterResource(streamIcon(stream, level.index == level.min, snap.ringer))
        val ringerPainter = painterResource(ringerIcon(snap.ringer))
        val morePainter = painterResource(R.drawable.ic_tune)
        val liquid = if (stream == VolStream.Call) InkColors.live else InkColors.on
        // The liquid shows at full strength early on, rather than greying in with the capsule.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - e * 2.2f).coerceIn(0f, 1f) * ((o - 0.15f) / 0.35f).coerceIn(0f, 1f) }) {
            val box = if (crisp) crispRect else capsule
            val inner = RoundRect(
                // In the sliver it runs right to the edge, the ink's source.
                Rect(box.left + ins, innerTop, box.right - ins, innerBottom),
                CornerRadius(((box.width - ins * 2f) / 2f).coerceAtLeast(0f)),
            )
            val clip = Path().apply { addRoundRect(inner) }
            // The liquid's surface bulges with its speed: up as it rises, down as it falls.
            val wave = (-fill.velocity * px(10f)).coerceIn(-px(7f), px(7f))
            val surface = Path().apply {
                moveTo(inner.left, liquidY)
                quadraticTo(inner.left + inner.width / 2f, liquidY - wave, inner.right, liquidY)
                lineTo(inner.right, inner.bottom)
                lineTo(inner.left, inner.bottom)
                close()
            }
            clipPath(clip) {
                drawPath(surface, liquid)
                // Above 100%, the boost fills the top in green, with a drop of ink for each step.
                if (extraH > 0.5f) {
                    drawRect(InkColors.live, Offset(inner.left, innerTop), Size(inner.width, (fullY - innerTop + px(2f)).coerceAtLeast(0f)))
                    val steps = if (stream == VolStream.Media) boostSteps else 0
                    if (steps > 0 && wc > 0.5f) {
                        val each = (fullY - innerTop) / steps
                        val r = min(px(2.4f), each * 0.3f)
                        for (i in 0 until steps) drawCircle(InkColors.ink.copy(alpha = 0.6f), r, Offset((inner.left + inner.right) / 2f, fullY - each * (i + 0.5f)))
                    }
                }
            }
            glint(Offset(capsule.center.x, fullY + px(4f)), px(30f), fullGlint.value, InkColors.on)
            // The stream's icon, dark where the liquid covers it and light where it doesn't.
            val iconSize = px(22f)
            val iconTop = innerBottom - px(14f) - iconSize
            val iconLeft = capsule.center.x - iconSize / 2f
            if (wc > 0.5f) {
                val a = ((wc - 0.5f) / 0.5f).coerceIn(0f, 1f)
                clipRect(top = 0f, bottom = liquidY) { icon(streamPainter, iconLeft, iconTop, iconSize, InkColors.on.copy(alpha = a)) }
                clipRect(top = liquidY, bottom = size.height) { icon(streamPainter, iconLeft, iconTop, iconSize, InkColors.ink.copy(alpha = a)) }
            }

            if (budK > 0.3f) {
                val a = ((budK - 0.3f) / 0.6f).coerceIn(0f, 1f)
                val s = px(20f)
                icon(ringerPainter, ringerBud.center.x - s / 2f, ringerBud.center.y - s / 2f, s, InkColors.on.copy(alpha = a))
                icon(morePainter, moreBud.center.x - s / 2f, moreBud.center.y - s / 2f, s, InkColors.on.copy(alpha = a))
            }
            if (t > 0.6f) {
                val a = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
                drawText(
                    labelLayout,
                    color = InkColors.on.copy(alpha = a),
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
