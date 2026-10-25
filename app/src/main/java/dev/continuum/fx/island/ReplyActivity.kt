package dev.continuum.fx.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.ui.Glyph
import dev.continuum.fx.island.ui.InkColors
import dev.continuum.fx.island.ui.RoundButton
import dev.continuum.fx.ui.theme.Libron
import dev.continuum.fx.ui.theme.Motion
import dev.continuum.fx.ui.theme.Txt
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Replying from the island. The drop that held the message grows into an ink card with
 * a place to type; send, and it's delivered through the app's own Reply button, then the
 * card draws back up into the island.
 */
class ReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pending = IslandRepository.replying
        if (pending == null) {
            finish()
            return
        }
        val (note, action) = pending
        setContent { ReplyCard(note, action) }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @Composable
    private fun ReplyCard(note: NoteInfo, action: NoteAction) {
        val scope = rememberCoroutineScope()
        val grow = remember { Animatable(0f) }
        val focus = remember { FocusRequester() }
        var text by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf(false) }
        var leaving by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            grow.animateTo(1f, Motion.flow())
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(120)
            try {
                focus.requestFocus()
            } catch (_: Exception) {
            }
        }

        fun leave(sent: Boolean) {
            if (leaving) return
            leaving = true
            if (!sent) IslandRepository.cancelReply()
            scope.launch {
                grow.animateTo(0f, tween(220))
                finish()
            }
        }

        fun send() {
            val reply = text.trim()
            if (reply.isEmpty()) return
            if (IslandRepository.sendReply(this@ReplyActivity, note, action, reply)) leave(sent = true) else problem = true
        }

        BackHandler { leave(sent = false) }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f * grow.value.coerceIn(0f, 1f)))
                .pointerInput(Unit) { detectTapGestures { leave(sent = false) } },
        ) {
            val density = LocalDensity.current
            val cardW = min(maxWidth.value - 24f, 380f).dp
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 6.dp)
                    .align(Alignment.TopCenter)
                    .width(cardW)
                    // The card grows out of the island: a pill at the camera that widens and drops.
                    .drawWithContent {
                        val g = grow.value.coerceIn(0f, 1.2f)
                        val pillW = with(density) { 120.dp.toPx() }
                        val pillH = with(density) { 34.dp.toPx() }
                        val w = pillW + (size.width - pillW) * min(g, 1f)
                        val h = pillH + (size.height - pillH) * g
                        val left = (size.width - w) / 2f
                        val cardR = with(density) { 28.dp.toPx() }
                        val r = min(min(w, h) / 2f, pillH / 2f + (cardR - pillH / 2f) * min(g, 1f))
                        val path = Path().apply { addRoundRect(RoundRect(Rect(left, 0f, left + w, h), CornerRadius(r))) }
                        clipPath(path) {
                            drawRect(InkColors.ink)
                            this@drawWithContent.drawContent()
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((grow.value - 0.55f) / 0.45f).coerceIn(0f, 1f) }
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val large = remember(note.key) { note.largeIcon?.asImageBitmap() }
                        if (large != null) {
                            Image(large, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape))
                        } else {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(InkColors.tonal), contentAlignment = Alignment.Center) {
                                Glyph(R.drawable.ic_reply, InkColors.on, 20.dp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(note.appName, 11.sp, InkColors.sub, maxLines = 1)
                            if (note.title.isNotBlank()) Txt(note.title, 16.sp, InkColors.on, bold = true, maxLines = 1)
                            if (note.text.isNotBlank()) Txt(note.text, 14.sp, InkColors.sub, maxLines = 4)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(InkColors.tonal)
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                        ) {
                            if (text.isEmpty()) {
                                Txt(
                                    action.inputs.firstOrNull()?.label?.toString()?.takeIf { it.isNotBlank() }
                                        ?: if (note.title.isNotBlank()) "Reply to ${note.title}" else "Reply",
                                    16.sp, InkColors.sub, maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it; problem = false },
                                textStyle = TextStyle(color = InkColors.on, fontSize = 16.sp, fontFamily = Libron),
                                cursorBrush = SolidColor(InkColors.on),
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val ready = text.isNotBlank()
                        RoundButton(
                            R.drawable.ic_send, "Send",
                            if (ready) InkColors.on else InkColors.tonal,
                            if (ready) InkColors.ink else InkColors.sub,
                            48.dp,
                        ) { send() }
                    }
                    if (problem) {
                        Spacer(Modifier.height(8.dp))
                        Txt("That didn't send. Open ${note.appName} to reply there.", 13.sp, InkColors.end, maxLines = 2)
                    }
                }
            }
        }
    }
}
