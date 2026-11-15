/*
 * The print shop the overlays share.
 *
 * The island and the volume panel are printed the way the app's poster is: on grainy paper, in
 * ink, with windows cut through to a halftone sky. Everything here is one of that poster's
 * materials, small enough to hang from the camera:
 *
 *  - the sky itself, a small plate of it drifting behind any window that's open;
 *  - pictures (album art, a caller's photo) put through the same five inks and the same dot
 *    screen, so they belong to the print rather than being pasted onto it;
 *  - the paper, with its grain and the hard shadow of something pasted down;
 *  - the type: big numerals set up the page's edge, a few kanji, the small bracketed readouts of
 *    a camera's screen, the serial lines and barcode of a record sleeve;
 *  - and the eye: the star-shaped window with an iris in it, whose pupil is the camera.
 */

package dev.continuum.fx.island.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.eidolon.Eidolon
import dev.continuum.fx.ui.eidolon.EidolonColors
import dev.continuum.fx.ui.eidolon.Grain
import dev.continuum.fx.ui.eidolon.Grotesk
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.GroteskWide
import dev.continuum.fx.ui.eidolon.Mincho
import dev.continuum.fx.ui.eidolon.PasteShadow
import dev.continuum.fx.ui.eidolon.SkyField
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.eidolon.dotScreen
import dev.continuum.fx.ui.eidolon.skyInks
import dev.continuum.fx.ui.eidolon.windowTextShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The sky

/** A small plate of the app's halftone sky, printed for whatever windows the overlays cut. */
@Stable
internal class PlateSky(val plate: SkyField, val cellPx: Int, val screen: ShaderBrush) {
    /** Bumped as each new frame of sky is printed, so windows redraw. */
    val frame = mutableIntStateOf(0)

    /** How many windows are open on it; it only drifts while one is. */
    val users = mutableIntStateOf(0)

    val size: Size get() = Size((plate.cols * cellPx).toFloat(), (plate.rows * cellPx).toFloat())
}

internal val LocalPlateSky = staticCompositionLocalOf<PlateSky?> { null }

/**
 * The overlays' sky: printed once straight away, then a few frames a second (the stutter is part
 * of the print) for as long as a window is open on it, and not at all otherwise.
 */
@Composable
internal fun rememberPlateSky(): PlateSky {
    val colors = Eidolon.colors
    val cell = with(LocalDensity.current) { 2.7.dp.toPx() }.roundToInt().coerceAtLeast(3)
    val sky = remember(cell, colors) {
        PlateSky(SkyField(SKY_COLS, SKY_ROWS), cell, dotScreen(cell, colors.windowGround)).also {
            it.plate.print(SKY_START, colors.skyInks(1f), SKY_ROWS)
            it.plate.commit()
        }
    }
    val open = sky.users.intValue > 0
    LaunchedEffect(sky, open) {
        if (!open) return@LaunchedEffect
        val inks = colors.skyInks(1f)
        var time = SKY_START + sky.frame.intValue * SKY_DRIFT
        while (true) {
            delay(150)
            time += SKY_DRIFT
            withContext(Dispatchers.Default) { sky.plate.print(time, inks, SKY_ROWS) }
            sky.plate.commit()
            sky.frame.intValue += 1
        }
    }
    return sky
}

private const val SKY_COLS = 156
private const val SKY_ROWS = 120
private const val SKY_START = 2.4f
private const val SKY_DRIFT = 0.013f

/** Keeps the sky drifting while this is in the composition. */
@Composable
internal fun UsesSky() {
    val sky = LocalPlateSky.current ?: return
    DisposableEffect(sky) {
        sky.users.intValue += 1
        onDispose { sky.users.intValue -= 1 }
    }
}

/** The sky seen through [path], the plate laid with its corner at [origin]. */
internal fun DrawScope.drawPlateSky(sky: PlateSky, path: Path, origin: Offset = Offset.Zero, alpha: Float = 1f) {
    sky.frame.intValue // a new frame has been printed: redraw
    val printed = sky.size
    clipPath(path) {
        translate(origin.x, origin.y) {
            drawImage(
                image = sky.plate.lit,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(sky.plate.cols, sky.plate.rows),
                dstSize = IntSize(printed.width.toInt(), printed.height.toInt()),
                alpha = alpha,
                filterQuality = FilterQuality.None,
            )
            drawRect(sky.screen, size = printed, alpha = alpha)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pictures, through the plate

private val BAYER: Array<FloatArray> =
    arrayOf(
        floatArrayOf(0f, 8f, 2f, 10f),
        floatArrayOf(12f, 4f, 14f, 6f),
        floatArrayOf(3f, 11f, 1f, 9f),
        floatArrayOf(15f, 7f, 13f, 5f),
    ).map { row -> FloatArray(4) { row[it] / 16f } }.toTypedArray()

/**
 * [src] printed as the sky is: cropped square, [cells] to a side, its light stretched to the full
 * range and broken into [inks] (darkest first) with the same ordered dither.
 */
internal fun printArt(src: Bitmap, inks: IntArray, cells: Int): ImageBitmap {
    val side = min(src.width, src.height)
    val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(crop, cells, cells, true)
    val pixels = IntArray(cells * cells)
    small.getPixels(pixels, 0, cells, 0, 0, cells, cells)
    val light = FloatArray(pixels.size)
    var lo = 1f
    var hi = 0f
    for (i in pixels.indices) {
        val p = pixels[i]
        val l = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        light[i] = l
        lo = min(lo, l)
        hi = max(hi, l)
    }
    val span = (hi - lo).coerceAtLeast(0.08f)
    val top = inks.size - 1
    for (y in 0 until cells) {
        val row = BAYER[y and 3]
        for (x in 0 until cells) {
            val i = y * cells + x
            val v = ((light[i] - lo) / span) * top + row[x and 3] - 0.5f
            pixels[i] = inks[v.roundToInt().coerceIn(0, top)]
        }
    }
    return Bitmap.createBitmap(pixels, cells, cells, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** True only in previews: pictures are printed at once, on the spot, so a drawing of the island has them. */
internal val LocalPrintNow = staticCompositionLocalOf { false }

/** [src] through the plate, printed off the main thread; null until it's ready, or with no picture. */
@Composable
internal fun rememberArtPrint(src: Bitmap?, inks: List<Color>, cells: Int): ImageBitmap? {
    if (LocalPrintNow.current) {
        return remember(src, inks, cells) { src?.let { printArt(it, IntArray(inks.size) { i -> inks[i].toArgb() }, cells) } }
    }
    val print by produceState<ImageBitmap?>(null, src, inks, cells) {
        value = if (src == null || cells <= 0) null else withContext(Dispatchers.Default) {
            try {
                printArt(src, IntArray(inks.size) { inks[it].toArgb() }, cells)
            } catch (_: Exception) {
                null
            }
        }
    }
    return print
}

/** The greys a picture is printed in on the black sheets: a pale figure out of the dark. */
internal val GhostInks = listOf(Color(0xFF000000), Color(0xFF1B1B22), Color(0xFF6E6E74), Color(0xFFC4C4C4), Color(0xFFF6F6F2))

/**
 * A window cut to [shape] (given the canvas, it writes its outline into the path): [art] through
 * the plate when there is some, and the drifting sky when there isn't. [ghost] prints it in
 * greys on black, for the black sheets.
 */
@Composable
internal fun PrintWindow(
    art: Bitmap?,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    /** How many dots of picture across: enough that each square of it is one dot of the screen. */
    cells: Int = 148,
    /** A finer screen, for a window too small to hold a picture at the sky's own. */
    fine: Boolean = false,
    shape: DrawScope.(Path) -> Unit,
) {
    val sky = LocalPlateSky.current
    val colors = Eidolon.colors
    val cell = ((sky?.cellPx ?: 4) / (if (fine) 2 else 1)).coerceAtLeast(2)
    val inks = if (ghost) GhostInks else colors.windowSky
    val print = rememberArtPrint(art, inks, cells)
    val screen = remember(cell, ghost, colors) { dotScreen(cell, if (ghost) Color.Black else colors.windowGround) }
    val path = remember { Path() }
    if (print == null && !ghost) UsesSky()
    Canvas(modifier) {
        path.rewind()
        shape(path)
        when {
            print != null -> clipPath(path) {
                // Scaled to cover the window in whole cells, so every square of picture is a dot.
                val bounds = path.getBounds()
                val side = max(bounds.width, bounds.height)
                val per = max(1, ceil(side / (cells.toFloat() * cell)).toInt()) * cell
                val dst = cells * per
                val ox = (bounds.left + (bounds.width - dst) / 2f).roundToInt()
                val oy = (bounds.top + (bounds.height - dst) / 2f).roundToInt()
                drawImage(print, dstOffset = IntOffset(ox, oy), dstSize = IntSize(dst, dst), filterQuality = FilterQuality.None)
                drawRect(screen, topLeft = Offset(ox.toFloat(), oy.toFloat()), size = Size(dst.toFloat(), dst.toFloat()))
            }
            ghost -> clipPath(path) {
                // No picture: a star of light in the dark where the figure would have been, paler toward its heart.
                val b = path.getBounds()
                drawRect(Color.Black, b.topLeft, b.size)
                val c = b.center
                val glow = Path()
                val steps = 9
                for (i in steps downTo 1) {
                    val f = i / steps.toFloat()
                    val tone = 1f - f
                    val grey = androidx.compose.ui.graphics.lerp(Color(0xFF101014), Color(0xFFF6F6F2), tone * tone * 0.9f + tone * 0.1f)
                    drawPath(Star.path(c, b.width / 2f * f, b.height / 2f * f, 0.5f - 0.22f * tone, into = glow), grey)
                }
                drawRect(screen, b.topLeft, b.size)
            }
            sky != null -> drawPlateSky(sky, path)
            else -> drawPath(path, colors.ultramarine)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Paper

/**
 * A sheet of the print's paper under this node: the hard shadow of something pasted down, the
 * sheet in [color], a hairline round it so it has an edge on a page its own colour, and the
 * grain over everything on it.
 */
@Composable
internal fun Modifier.sheet(color: Color = Eidolon.colors.slip, shadow: Dp = 3.dp): Modifier {
    // The grain follows the sheet's own tone: fibres on a pale one, dust on a dark one.
    val dark = color.red * 0.3f + color.green * 0.59f + color.blue * 0.11f < 0.4f
    return drawWithCache {
        val grain = grainBrush(dark)
        val edge = (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.2f else 0.16f)
        val off = shadow.toPx()
        onDrawWithContent {
            if (off > 0f) drawRect(PasteShadow.copy(alpha = if (dark) 0.6f else 0.34f), Offset(off * 0.7f, off), size)
            drawRect(color)
            drawContent()
            drawRect(grain)
            drawRect(edge, style = Stroke(1f))
        }
    }
}

private val grainBrushes = arrayOfNulls<ShaderBrush>(2)

/** The grain as a brush, made once for each of its two tones. */
private fun grainBrush(dark: Boolean): ShaderBrush {
    val i = if (dark) 1 else 0
    return grainBrushes[i] ?: ShaderBrush(ImageShader(Grain.tile(dark), TileMode.Repeated, TileMode.Repeated)).also { grainBrushes[i] = it }
}

/** Grain and edge for a shape drawn by hand (the mount, a wing): call after filling [path]. */
internal fun DrawScope.grainOver(path: Path, dark: Boolean) {
    val grain = grainBrush(dark)
    drawPath(path, grain)
    drawPath(path, (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.22f else 0.18f), style = Stroke(1f))
}

// ---------------------------------------------------------------------------------------------
// Type

/** One line (or [maxLines]) of the grotesque, cut off with an ellipsis. */
@Composable
internal fun IsleText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    face: FontFamily = Grotesk,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    tracking: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    figures: Boolean = false,
    overWindow: Boolean = false,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = face,
            letterSpacing = tracking,
            textAlign = align,
            lineHeight = lineHeight,
            fontFeatureSettings = if (figures) "tnum" else null,
            shadow = if (overWindow) windowTextShadow(1f) else null,
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        maxLines = maxLines,
    )
}

/** Wide small capitals: a poster's credits, a button's word. */
@Composable
internal fun IsleNote(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 9.5.sp, overWindow: Boolean = false) {
    val caps = text.uppercase()
    BasicText(
        caps,
        modifier.semantics { contentDescription = text },
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GroteskWide,
            letterSpacing = 0.95.sp,
            fontFeatureSettings = "tnum, case",
            shadow = if (overWindow) windowTextShadow(1f) else null,
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
    )
}

/**
 * The small readout of a camera's screen and a record sleeve's serial line: monospaced, as
 * typed. "[ 3/ 8]", "FILE: 0142 / 03:42".
 */
@Composable
internal fun Osd(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 10.sp, maxLines: Int = 1, lineHeight: TextUnit = TextUnit.Unspecified) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, lineHeight = lineHeight),
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        maxLines = maxLines,
    )
}

/** Kanji in the mincho: a sheet's title mark. [stacked] sets it down the page, one to a line. */
@Composable
internal fun Kanji(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, stacked: Boolean = false) {
    BasicText(
        if (stacked) text.toList().joinToString("\n") else text,
        modifier.semantics { contentDescription = "" },
        style = TextStyle(color = color, fontSize = size, fontFamily = Mincho, fontWeight = FontWeight.Medium, lineHeight = size * 1.08f),
    )
}

/** Numerals set large and tight, as the poster sets its hour. */
@Composable
internal fun Numerals(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, overWindow: Boolean = false) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = GroteskTight,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.03f * size.value).sp,
            fontFeatureSettings = "tnum",
            lineHeight = size,
            shadow = if (overWindow) windowTextShadow(1f) else null,
        ),
        softWrap = false,
        maxLines = 1,
    )
}

/** Turns what it holds a quarter clockwise, to read down the page's edge, and takes up the turned room. */
internal fun Modifier.downThePage(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            maxWidth = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
            maxHeight = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity,
        ),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        ) { rotationZ = 90f }
    }
}

// ---------------------------------------------------------------------------------------------
// Marks

/**
 * A barcode: bars whose widths come from [seed], so each thing has its own. Up to [progress] of
 * the way along it is printed in [ink]; the rest in [pale], still to come.
 */
internal fun DrawScope.barcode(rect: Rect, seed: Int, progress: Float, ink: Color, pale: Color) {
    val unit = 1.4.dp.toPx()
    var state = seed * 1103515245 + 12345
    var x = rect.left
    val turn = rect.left + rect.width * progress.coerceIn(0f, 1f)
    while (x < rect.right - unit) {
        state = state * 1103515245 + 12345
        val bar = unit * (1 + ((state ushr 16) and 3) % 3)
        state = state * 1103515245 + 12345
        val gap = unit * (1 + ((state ushr 16) and 1))
        val w = min(bar, rect.right - x)
        when {
            x + w <= turn -> drawRect(ink, Offset(x, rect.top), Size(w, rect.height))
            x >= turn -> drawRect(pale, Offset(x, rect.top), Size(w, rect.height))
            else -> {
                drawRect(ink, Offset(x, rect.top), Size(turn - x, rect.height))
                drawRect(pale, Offset(turn, rect.top), Size(x + w - turn, rect.height))
            }
        }
        x += bar + gap
    }
}

/** A printed four-point star. */
@Composable
internal fun PrintedStar(size: Dp, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f, pinch: Float = Star.KEEN) {
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        drawPath(Star.path(center, this.size.minDimension / 2f, pinch = pinch, rotation = rotation, into = path), color)
    }
}

/** The small arrow a poster points its corners with: down and to the right. */
internal fun DrawScope.cornerArrow(topLeft: Offset, size: Float, color: Color, stroke: Float) {
    val a = topLeft
    val b = Offset(topLeft.x + size, topLeft.y + size)
    drawLine(color, a, b, stroke, cap = StrokeCap.Square)
    drawLine(color, Offset(b.x, a.y + size * 0.28f), b, stroke, cap = StrokeCap.Square)
    drawLine(color, Offset(a.x + size * 0.28f, b.y), b, stroke, cap = StrokeCap.Square)
}

// ---------------------------------------------------------------------------------------------
// Buttons

internal enum class Press {
    /** Ruled in ink: the plain way to do a thing. */
    Rule,

    /** A window cut through to the sky: the thing to do. */
    Window,

    /** The one red: ends, declines, stops. */
    Signal,

    /** Solid ink: the answer, on the black sheets. */
    Solid,
}

/** A poster's button: a ruled box, a window, or a block of the one red, its word in wide capitals. */
@Composable
internal fun PlateButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: Press = Press.Rule,
    @DrawableRes icon: Int? = null,
    arrow: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Eidolon.colors
    val sky = LocalPlateSky.current
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val fg = when (kind) {
        Press.Rule -> colors.ink
        Press.Window -> colors.onWindow
        Press.Signal -> Color.White
        Press.Solid -> colors.slip
    }
    val path = remember { Path() }
    if (kind == Press.Window) UsesSky()
    Row(
        modifier
            .squash(source, pill = true)
            .height(42.dp)
            .drawBehind {
                when (kind) {
                    Press.Rule -> drawRect(colors.ink, style = Stroke(1.2.dp.toPx()))
                    Press.Signal -> drawRect(colors.signal)
                    Press.Solid -> drawRect(colors.ink)
                    Press.Window -> {
                        path.rewind()
                        path.addRect(Rect(Offset.Zero, size))
                        if (sky != null) drawPlateSky(sky, path, Offset(-size.width * 0.3f, -sky.size.height * 0.35f)) else drawRect(colors.ultramarine)
                    }
                }
                if (arrow) cornerArrow(Offset(size.width - 13.dp.toPx(), size.height - 13.dp.toPx()), 6.dp.toPx(), fg.copy(alpha = 0.8f), 1.2.dp.toPx())
            }
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                InkFeel.land(view)
                onClick()
            }
            .padding(horizontal = if (arrow) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (arrow) 7.dp else 5.dp, if (arrow) Alignment.Start else Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, fg, 15.dp)
        IsleNote(text, fg, size = if (arrow) 10.sp else 9.sp, overWindow = kind == Press.Window)
    }
}

/** A bare glyph you can press, on a small pasted tab of paper. */
@Composable
internal fun GlyphTab(
    @DrawableRes icon: Int,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Eidolon.colors
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .squash(source)
            .size(size)
            // The one that matters is a block of solid ink; the others are tabs of paper.
            .sheet(if (lit) colors.ink else colors.slipRaised, shadow = 2.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description) {
                InkFeel.split(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (lit) colors.slip else colors.ink, size * 0.46f) }
}

/** Buttons shared out along the foot of a sheet. */
@Composable
internal fun PlateActions(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// ---------------------------------------------------------------------------------------------
// The eye

/** What the eye is drawn with: its star, its lens, and how it is just now. */
internal class EyeLook(
    /** How far open: 1 wide, 0 shut (the star closes top to bottom, like a lid). */
    val lid: Float = 1f,
    /** How far the iris has turned, in degrees: it turns like a record while something plays. */
    val turn: Float = 0f,
    /** Where it's looking: (-1, 0) left, (1, 0) right, (0, 1) down. The pupil can't move, so the light on it does. */
    val gaze: Offset = Offset.Zero,
    /** A fleck of the one red by the pupil: something's waiting for you. */
    val fleck: Boolean = false,
    /** 0..1: how far the red has flooded the iris (a call ringing). */
    val alarm: Float = 0f,
)

/**
 * The star-shaped window with an eye in it: an ultramarine iris, streaked out from a black pupil
 * of radius [lens] (the camera, on the phone). [path] is scratch.
 */
internal fun DrawScope.starEye(
    center: Offset,
    rx: Float,
    ry: Float,
    lens: Float,
    colors: EidolonColors,
    look: EyeLook,
    path: Path,
    pinch: Float = Star.SOFT,
) {
    val lid = look.lid.coerceIn(0f, 1.15f)
    if (lid < 0.02f) {
        // Shut: just the line of the lids.
        drawLine(colors.ink, Offset(center.x - rx, center.y), Offset(center.x + rx, center.y), max(1f, ry * 0.07f), cap = StrokeCap.Round)
        return
    }
    Star.path(center, rx, ry * lid, pinch, into = path)
    val inks = colors.windowSky
    val red = colors.signal
    fun ink(i: Int): Color = if (look.alarm > 0f) androidx.compose.ui.graphics.lerp(inks[i], if (i == 0) Color(0xFF2A0004) else red, look.alarm.coerceIn(0f, 1f) * (if (i >= 3) 0.35f else 0.9f)) else inks[i]
    clipPath(path) {
        drawRect(ink(1), Offset(center.x - rx, center.y - ry), Size(rx * 2f, ry * 2f))
        // The iris: streaks out from the pupil, unevenly, in the plate's lighter and darker inks.
        val n = if (rx > lens * 6f) 54 else 26
        val w = max(1f, rx * 0.04f)
        for (i in 0 until n) {
            val h = ((i * 2654435761L) ushr 8 and 0xFF).toInt() / 255f
            val a = (look.turn + i * 360f / n + (h - 0.5f) * 9f) * (PI.toFloat() / 180f)
            val r0 = lens * 1.12f
            val r1 = lens * (1.7f + 1.9f * h) + rx * 0.12f * h
            val c = when (i % 5) {
                0, 3 -> ink(3)
                1 -> ink(2)
                2 -> ink(0)
                else -> ink(4)
            }
            drawLine(c, Offset(center.x + cos(a) * r0, center.y + sin(a) * r0), Offset(center.x + cos(a) * r1, center.y + sin(a) * r1), w * (0.7f + 0.8f * h), cap = StrokeCap.Round)
        }
        // The dark ring an iris has at its rim, and the pupil.
        drawCircle(ink(0), lens * 1.22f, center, style = Stroke(max(1f, lens * 0.22f)))
        drawCircle(Color.Black, lens * 1.04f, center)
        // The light on it, toward whatever it's looking at.
        val g = Offset(-0.5f + look.gaze.x * 0.9f, -0.62f + look.gaze.y * 1.25f)
        drawCircle(ink(4), max(1.2f, lens * 0.2f), Offset(center.x + g.x * lens * 1.75f, center.y + g.y * lens * 1.75f))
        if (look.fleck) {
            val f = Offset(center.x + lens * 1.5f, center.y + lens * 1.15f)
            drawPath(Star.path(f, lens * 0.62f, pinch = Star.KEEN, rotation = 20f), red)
        }
    }
}
