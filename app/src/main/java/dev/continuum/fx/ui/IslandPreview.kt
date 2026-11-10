package dev.continuum.fx.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.continuum.fx.island.CameraSpot
import dev.continuum.fx.island.ui.IslandSurface
import dev.continuum.fx.ui.theme.Motion
import kotlin.math.max

/**
 * The island itself, drawn in the page: the real drops hanging from a strip of black along the top
 * of a small screen, the camera in the middle, 64dp of rest height. The page draws the screen it
 * sits on (the top of a phone, fading out below), so the ink has a screen to hang over, as it does
 * on the phone. It's the same
 * island the overlay draws, with your settings, so samples from Try it play into it before Android
 * has given the overlay its permissions, and opening something here opens it the same way. The
 * screen grows to fit whatever falls out of the bar, and a tap on it away from the ink folds it
 * back up.
 */
@Composable
internal fun IslandPreview(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val taps = remember { mutableIntStateOf(0) }
    var lowest by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth
        val bar = with(density) { 28.dp.toPx() }
        val lens = with(density) { 5.dp.toPx() }
        val spot = remember(w, density) {
            CameraSpot(
                cx = w / 2f,
                cy = bar * 0.48f,
                radius = lens,
                screenWidth = w,
                screenHeight = with(density) { 640.dp.roundToPx() },
                detected = true,
                landscape = false,
                bar = bar,
            )
        }
        val spotState = rememberUpdatedState<CameraSpot?>(spot)
        val target = with(density) { max(64.dp.toPx(), lowest + 14.dp.toPx()).toDp() }
        val height by animateDpAsState(target, Motion.flow(), label = "previewHeight")
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(Unit) { detectTapGestures { taps.intValue += 1 } }
                .semantics { contentDescription = "A preview of the island" },
        ) {
            // Laid out at a phone screen's height whatever the preview's height is just now, so
            // what falls out of the bar is measured as it would be and only cut off while the
            // preview grows to it.
            val screenH = spot.screenHeight
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = screenH, maxHeight = screenH))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                },
            ) {
                IslandSurface(spotState, taps) { rects, _ ->
                    lowest = rects.maxOfOrNull { it.bottom }?.toFloat() ?: 0f
                }
            }
            // The lens the camera's drop sits round, as on the phone.
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(LENS, lens * 0.62f, Offset(spot.cx, spot.cy))
                drawCircle(Color.White.copy(alpha = 0.18f), lens * 0.18f, Offset(spot.cx - lens * 0.2f, spot.cy - lens * 0.2f))
            }
        }
    }
}

private val LENS = Color(0xFF15161A)
