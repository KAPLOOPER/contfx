package dev.continuum.keyboard.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Moving around text without fighting your thumb: arrows (hold to keep going), jumps by
 * word and to either end of the line, Select to grow a selection with the same arrows,
 * and the clipboard and undo close by.
 */
@Composable
internal fun EditPanel(state: PanelState, actions: PanelActions) {
    val p = state.palette
    val label = p.label.toColor()
    val sub = p.sub.toColor()
    val key = p.key.toColor()
    val fn = p.fn.toColor()
    val selecting = state.selecting
    val sel = state.hasSelection
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The pad: arrows round Select, with word and line jumps in the corners.
            Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                PadRow {
                    EditKey(Icons.WORD_LEFT, fn, label, state, repeat = true) { actions.edit(EditAction.WordLeft) }
                    EditKey(Icons.UP, key, label, state, repeat = true) { actions.edit(EditAction.Up) }
                    EditKey(Icons.WORD_RIGHT, fn, label, state, repeat = true) { actions.edit(EditAction.WordRight) }
                }
                PadRow {
                    EditKey(Icons.LEFT, key, label, state, repeat = true) { actions.edit(EditAction.Left) }
                    EditKey(
                        null, if (selecting) p.bright.toColor() else fn, if (selecting) p.onBright.toColor() else label, state,
                        text = "Select",
                    ) { state.selecting = !state.selecting }
                    EditKey(Icons.RIGHT, key, label, state, repeat = true) { actions.edit(EditAction.Right) }
                }
                PadRow {
                    EditKey(Icons.LINE_START, fn, label, state) { actions.edit(EditAction.LineStart) }
                    EditKey(Icons.DOWN, key, label, state, repeat = true) { actions.edit(EditAction.Down) }
                    EditKey(Icons.LINE_END, fn, label, state) { actions.edit(EditAction.LineEnd) }
                }
            }
            // The clipboard, beside it.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                PadRow {
                    EditKey(Icons.SELECT_ALL, fn, label, state, text = "Select all") {
                        actions.edit(EditAction.SelectAll)
                    }
                }
                PadRow {
                    EditKey(Icons.COPY, fn, if (sel) label else sub, state, text = "Copy") { actions.edit(EditAction.Copy) }
                    EditKey(Icons.CUT, fn, if (sel) label else sub, state, text = "Cut") { actions.edit(EditAction.Cut) }
                }
                PadRow {
                    EditKey(Icons.PASTE, fn, label, state, text = "Paste") { actions.edit(EditAction.Paste) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("ABC", fn, label, actions::backToKeys)
            Spacer(Modifier.weight(1f))
            RepeatBubble(Icons.UNDO, fn, label, state, repeat = false) { actions.edit(EditAction.Undo) }
            Spacer(Modifier.width(6.dp))
            RepeatBubble(Icons.REDO, fn, label, state, repeat = false) { actions.edit(EditAction.Redo) }
            Spacer(Modifier.width(6.dp))
            RepeatBubble(Icons.DELETE, fn, label, state, repeat = true) { again -> actions.delete(again) }
        }
    }
}

/** A row of the pad: shares the panel's height with its neighbours, up to a comfortable 56dp. */
@Composable
private fun ColumnScope.PadRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 56.dp).fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * One edit key: a pill with an icon, a label or both. Squashes wide like the keyboard's
 * pills; with [repeat] it keeps acting while held, quicker the longer you hold.
 */
@Composable
private fun RowScope.EditKey(
    icon: String?,
    background: Color,
    tint: Color,
    state: PanelState,
    text: String? = null,
    repeat: Boolean = false,
    onAct: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val sx by animateFloatAsState(if (pressed) 1.05f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "editX")
    val sy by animateFloatAsState(if (pressed) 0.86f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "editY")
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { scaleX = sx; scaleY = sy }
            .clip(RoundedCornerShape(50))
            .background(background)
            .holdToRepeat(state, repeat, { onAct() }) { pressed = it },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) LineIcon(icon, tint, 20.dp)
        if (icon != null && text != null) Box(Modifier.size(6.dp))
        if (text != null) Txt(text, 15.sp, tint, maxLines = 1)
    }
}

/** A round icon button that can repeat while held (told [onAct] whether it's a repeat). */
@Composable
internal fun RepeatBubble(d: String, background: Color, tint: Color, state: PanelState, repeat: Boolean, onAct: (again: Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, if (pressed) Motion.snap() else Motion.flow(), label = "bubble")
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .holdToRepeat(state, repeat, onAct) { pressed = it },
        contentAlignment = Alignment.Center,
    ) { LineIcon(d, tint, 22.dp) }
}

/**
 * With [repeat], acts on touch down (so the cursor moves the moment you touch) and again and
 * again while held: a pause first, then steadily faster, like a held hardware key. Without
 * it, acts when you lift, so a brushed Paste or Undo can be slid off and abandoned.
 */
@Composable
private fun Modifier.holdToRepeat(state: PanelState, repeat: Boolean, onAct: (again: Boolean) -> Unit, onPressed: (Boolean) -> Unit): Modifier {
    val view = LocalView.current
    val act by rememberUpdatedState(onAct)
    val vibrate by rememberUpdatedState(state.vibrate)
    return pointerInput(repeat) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                onPressed(true)
                if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (repeat) act(false)
                val job = if (!repeat) null else launch {
                    delay(380)
                    var wait = 90L
                    while (true) {
                        act(true)
                        if (vibrate) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        delay(wait)
                        wait = (wait * 0.88f).toLong().coerceAtLeast(28L)
                    }
                }
                val up = waitForUpOrCancellation()
                job?.cancel()
                onPressed(false)
                if (!repeat && up != null) act(false)
            }
        }
    }
}
