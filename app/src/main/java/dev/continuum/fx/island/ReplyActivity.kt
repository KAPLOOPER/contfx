package dev.continuum.fx.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.island.ui.IsleText
import dev.continuum.fx.island.ui.Kanji
import dev.continuum.fx.island.ui.Lines
import dev.continuum.fx.island.ui.LocalPlateSky
import dev.continuum.fx.island.ui.NoteFace
import dev.continuum.fx.island.ui.Osd
import dev.continuum.fx.island.ui.PlateButton
import dev.continuum.fx.island.ui.PosterColumn
import dev.continuum.fx.island.ui.Press
import dev.continuum.fx.island.ui.rememberPlateSky
import dev.continuum.fx.ui.eidolon.Eidolon
import dev.continuum.fx.ui.eidolon.GroteskTight
import dev.continuum.fx.ui.eidolon.LocalEidolonColors
import dev.continuum.fx.ui.eidolon.PaperColors
import dev.continuum.fx.ui.eidolon.VoidColors
import dev.continuum.fx.ui.theme.EidolonFaces
import dev.continuum.fx.ui.theme.LocalFaces
import dev.continuum.fx.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Replying from the island. The message hangs again as a poster with a place to type; send, and
 * it's delivered through the app's own Reply button, then the poster is drawn back up into the eye.
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

        CompositionLocalProvider(
            LocalFaces provides EidolonFaces,
            LocalEidolonColors provides if (isSystemInDarkTheme()) VoidColors else PaperColors,
        ) {
            CompositionLocalProvider(LocalPlateSky provides rememberPlateSky()) {
                val colors = Eidolon.colors
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
                            // It hangs from the eye as every poster does: grown out of the top, swung a little as it lands.
                            .graphicsLayer {
                                val g = grow.value
                                transformOrigin = TransformOrigin(0.5f, -0.2f)
                                scaleX = 0.08f + 0.92f * g
                                scaleY = 0.08f + 0.92f * g
                                rotationZ = (1f - g.coerceIn(0f, 1.2f)) * 4f
                                alpha = (g * 2.2f).coerceIn(0f, 1f)
                            }
                            .pointerInput(Unit) { detectTapGestures { } },
                    ) {
                        PosterColumn {
                            Row(verticalAlignment = Alignment.Top) {
                                Lines("reply", note.appName.lowercase(), modifier = Modifier.weight(1f), soft = 1)
                                Kanji("返信", 17.sp, colors.ink, stacked = true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NoteFace(note, 48.dp)
                                Spacer(Modifier.width(10.dp))
                                if (note.title.isNotBlank()) IsleText(note.title, 22.sp, colors.ink, weight = FontWeight.SemiBold, face = GroteskTight, maxLines = 2, lineHeight = 23.sp)
                            }
                            if (note.text.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Osd(note.text, colors.inkSoft, size = 12.5.sp, maxLines = 4, lineHeight = 17.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 42.dp)
                                        .border(1.2.dp, colors.ink)
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                ) {
                                    if (text.isEmpty()) {
                                        Osd(
                                            action.inputs.firstOrNull()?.label?.toString()?.takeIf { it.isNotBlank() }
                                                ?: if (note.title.isNotBlank()) "reply to ${note.title}" else "reply",
                                            colors.inkFaint, size = 14.sp,
                                        )
                                    }
                                    // What you say back, as typed.
                                    BasicTextField(
                                        value = text,
                                        onValueChange = { text = it; problem = false },
                                        textStyle = TextStyle(color = colors.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp),
                                        cursorBrush = SolidColor(colors.signal),
                                        maxLines = 5,
                                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                PlateButton("Send", Modifier.width(104.dp), if (text.isNotBlank()) Press.Window else Press.Rule, R.drawable.ic_send) { send() }
                            }
                            if (problem) {
                                Spacer(Modifier.height(8.dp))
                                Osd("that didn't send. open ${note.appName} to reply there.", colors.signal, maxLines = 2, lineHeight = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
