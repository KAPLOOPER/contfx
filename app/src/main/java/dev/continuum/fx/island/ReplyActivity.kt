package dev.continuum.fx.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.ui.Glyph
import dev.continuum.fx.island.ui.Isle
import dev.continuum.fx.island.ui.IsleText
import dev.continuum.fx.island.ui.NoteFace
import dev.continuum.fx.island.ui.SlipHeader
import dev.continuum.fx.island.ui.SlipShape
import dev.continuum.fx.island.ui.TagShape
import dev.continuum.fx.ui.components.squash
import dev.continuum.fx.ui.eidolon.Grotesk
import dev.continuum.fx.ui.eidolon.Star
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Replying from the island. The message's slip hangs again with a place to type; send, and it's
 * delivered through the app's own Reply button, then the slip is drawn back up into the star.
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
                .background(Color.Black.copy(alpha = 0.32f * grow.value.coerceIn(0f, 1f)))
                .pointerInput(Unit) { detectTapGestures { leave(sent = false) } },
        ) {
            val cardW = min(maxWidth.value - 20f, 384f).dp
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .align(Alignment.TopCenter)
                    .width(cardW)
                    // It hangs from the star as every slip does: grown out of the top, swung a little as it lands.
                    .graphicsLayer {
                        val g = grow.value
                        transformOrigin = TransformOrigin(0.5f, -0.2f)
                        scaleX = 0.08f + 0.92f * g
                        scaleY = 0.08f + 0.92f * g
                        rotationZ = (1f - g.coerceIn(0f, 1.2f)) * 4f
                        alpha = (g * 2.2f).coerceIn(0f, 1f)
                    }
                    .background(Isle.slip, SlipShape)
                    .border(1.dp, Isle.rule, SlipShape)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                ) {
                    SlipHeader("Reply", note.appName, mark = Isle.lit)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        NoteFace(note, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            if (note.title.isNotBlank()) IsleText(note.title, 16.sp, Isle.on, weight = FontWeight.SemiBold)
                            if (note.text.isNotBlank()) IsleText(note.text, 14.sp, Isle.soft, maxLines = 4, lineHeight = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .background(Isle.well, TagShape)
                                .border(1.dp, if (text.isEmpty()) Isle.rule else Isle.ruleStrong, TagShape)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                        ) {
                            if (text.isEmpty()) {
                                IsleText(
                                    action.inputs.firstOrNull()?.label?.toString()?.takeIf { it.isNotBlank() }
                                        ?: if (note.title.isNotBlank()) "Reply to ${note.title}" else "Reply",
                                    16.sp, Isle.faint,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it; problem = false },
                                textStyle = TextStyle(color = Isle.on, fontSize = 16.sp, fontFamily = Grotesk),
                                cursorBrush = SolidColor(Isle.lit),
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SendStar(text.isNotBlank()) { send() }
                    }
                    if (problem) {
                        Spacer(Modifier.height(8.dp))
                        IsleText("That didn't send. Open ${note.appName} to reply there.", 13.sp, Isle.signal, maxLines = 2)
                    }
                }
            }
        }
    }

    /** Send: a star, lit once there's something to send, turning a quarter as it lights. */
    @Composable
    private fun SendStar(ready: Boolean, onClick: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val turn by animateFloatAsState(if (ready) 45f else 0f, Motion.flow(), label = "sendTurn")
        val path = remember { Path() }
        Box(
            Modifier
                .squash(source)
                .size(52.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Send", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawPath(Star.path(center, size.minDimension / 2f, pinch = Star.SOFT, rotation = turn, into = path), if (ready) Isle.lit else Isle.well)
            }
            Glyph(R.drawable.ic_send, if (ready) Isle.onLit else Isle.faint, 18.dp)
        }
    }
}
