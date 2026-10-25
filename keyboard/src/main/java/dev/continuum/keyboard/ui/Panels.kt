package dev.continuum.keyboard.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.keyboard.clipboard.Clip
import dev.continuum.keyboard.emoji.EmojiData
import dev.continuum.keyboard.emoji.EmojiItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the panels show; the input method keeps it current. */
class PanelState {
    var panel by mutableStateOf<Tool?>(null)
    var palette by mutableStateOf(KeyPalette.Ink)
    var clips by mutableStateOf<List<Clip>>(emptyList())
    var historyOn by mutableStateOf(true)
    var emoji by mutableStateOf<EmojiData?>(null)
    var frequent by mutableStateOf<List<String>>(emptyList())
    /** Bumped when a skin tone changes, so cells redraw. */
    var variantsVersion by mutableStateOf(0)
    /** The edit panel's Select is on: arrows grow the selection instead of moving the cursor. */
    var selecting by mutableStateOf(false)
    /** Something is selected in the field, so Copy and Cut have something to work on. */
    var hasSelection by mutableStateOf(false)
    var vibrate by mutableStateOf(true)
}

/** What the edit panel can do to the field. */
enum class EditAction { Left, Right, Up, Down, WordLeft, WordRight, LineStart, LineEnd, SelectAll, Copy, Cut, Paste, Undo, Redo }

interface PanelActions {
    fun typeEmoji(emoji: String, base: String)
    fun variantOf(base: String): String
    fun chooseVariant(base: String, emoji: String)
    fun pasteClip(clip: Clip)
    fun pinClip(clip: Clip, pinned: Boolean)
    fun removeClip(clip: Clip)
    fun clearClips()
    fun clipFile(clip: Clip): File?
    fun backToKeys()
    /** Deletes back one character; [repeat] while delete is held. */
    fun delete(repeat: Boolean)
    fun openSettings()
    fun edit(action: EditAction)
}

@Composable
fun Panels(state: PanelState, actions: PanelActions) {
    val panel = state.panel ?: return
    val shown = remember { Animatable(0f) }
    LaunchedEffect(panel) {
        shown.snapTo(0f)
        shown.animateTo(1f, Motion.flow())
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = shown.value.coerceIn(0f, 1.1f)
                alpha = t.coerceIn(0f, 1f)
                scaleX = 0.94f + 0.06f * t
                scaleY = 0.94f + 0.06f * t
            },
    ) {
        when (panel) {
            Tool.Emoji -> EmojiPanel(state, actions)
            Tool.Clipboard -> ClipboardPanel(state, actions)
            Tool.Edit -> EditPanel(state, actions)
            else -> Unit
        }
    }
}

// ------------------------------------------------------------------ Emoji

private const val FREQUENT = -1

private fun groupName(id: Int) = when (id) {
    FREQUENT -> "Frequent"
    0 -> "Smileys"
    1 -> "People"
    3 -> "Animals and nature"
    4 -> "Food and drink"
    5 -> "Travel and places"
    6 -> "Activities"
    7 -> "Objects"
    8 -> "Symbols"
    9 -> "Flags"
    else -> ""
}

private fun groupIcon(id: Int) = when (id) {
    FREQUENT -> Icons.RECENT
    0 -> Icons.EMOJI
    1 -> Icons.PEOPLE
    3 -> Icons.NATURE
    4 -> Icons.FOOD
    5 -> Icons.TRAVEL
    6 -> Icons.ACTIVITY
    7 -> Icons.OBJECTS
    8 -> Icons.SYMBOLS
    else -> Icons.FLAGS
}

private class Section(val id: Int, val items: List<EmojiItem>)

@Composable
private fun EmojiPanel(state: PanelState, actions: PanelActions) {
    val p = state.palette
    val data = state.emoji
    val label = p.label.toColor()
    val sub = p.sub.toColor()
    val sections = remember(data, state.frequent) {
        if (data == null) emptyList() else {
            val byEmoji = HashMap<String, EmojiItem>()
            data.groups.forEach { g -> g.items.forEach { byEmoji[it.emoji] = it } }
            val freq = state.frequent.map { byEmoji[it] ?: EmojiItem(it, it, emptyList()) }
            (if (freq.isEmpty()) emptyList() else listOf(Section(FREQUENT, freq))) + data.groups.map { Section(it.id, it.items) }
        }
    }
    // Where each section's header sits in the grid.
    val starts = remember(sections) {
        var i = 0
        sections.map { s -> i.also { i += 1 + s.items.size } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val active by remember(starts) {
        derivedStateOf {
            val first = grid.firstVisibleItemIndex
            starts.indexOfLast { it <= first }.coerceAtLeast(0)
        }
    }
    var picking by remember { mutableStateOf<EmojiItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (data == null) {
                Txt("Getting emoji ready…", 15.sp, sub, Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    state = grid,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { s ->
                        item(key = "h${s.id}", span = { GridItemSpan(maxLineSpan) }) {
                            Txt(groupName(s.id), 13.sp, sub, Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
                        }
                        items(s.items.size, key = { "${s.id}:${s.items[it].emoji}" }) { i ->
                            val item = s.items[i]
                            EmojiCell(item, state.variantsVersion, actions) { picking = item }
                        }
                    }
                }
            }
            picking?.let { item ->
                // Skin tones grow out of the top of the panel.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(item) { detectTapGestures { picking = null } },
                ) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(p.preview.toColor())
                            .padding(horizontal = 6.dp),
                    ) {
                        (listOf(item.emoji) + item.skins).forEach { e ->
                            Box(
                                Modifier
                                    .size(50.dp)
                                    .pointerInput(e) {
                                        detectTapGestures {
                                            actions.chooseVariant(item.emoji, e)
                                            actions.typeEmoji(e, item.emoji)
                                            picking = null
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { BasicText(e, style = TextStyle(fontSize = 28.sp)) }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("ABC", p.fn.toColor(), label, actions::backToKeys)
            Spacer(Modifier.width(6.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sections.size) { i ->
                    val on = i == active
                    IconBubble(
                        groupIcon(sections[i].id),
                        if (on) p.bright.toColor() else androidx.compose.ui.graphics.Color.Transparent,
                        if (on) p.onBright.toColor() else sub,
                        onClick = { scope.launch { grid.scrollToItem(starts[i]) } },
                        size = 38.dp,
                        iconSize = 20.dp,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            RepeatBubble(Icons.DELETE, p.fn.toColor(), label, state, repeat = true) { again -> actions.delete(again) }
        }
    }
}

@Composable
private fun EmojiCell(item: EmojiItem, version: Int, actions: PanelActions, onPick: () -> Unit) {
    val shown = remember(item.emoji, version) { actions.variantOf(item.emoji) }
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                val s = if (pressed) 0.82f else 1f
                scaleX = s; scaleY = s
            }
            .pointerInput(item.emoji) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { actions.typeEmoji(actions.variantOf(item.emoji), item.emoji) },
                    onLongPress = { if (item.skins.isNotEmpty()) onPick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) { BasicText(shown, style = TextStyle(fontSize = 27.sp)) }
}

// ------------------------------------------------------------------ Clipboard

@Composable
private fun ClipboardPanel(state: PanelState, actions: PanelActions) {
    val p = state.palette
    val label = p.label.toColor()
    val sub = p.sub.toColor()
    val clips = remember(state.clips) { state.clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time }) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                clips.isEmpty() && !state.historyOn -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt("Clipboard history is off", 17.sp, label, bold = true)
                    Spacer(Modifier.height(4.dp))
                    Txt("Turn it on to keep what you copy here.", 15.sp, sub)
                    Spacer(Modifier.height(14.dp))
                    Pill("Open settings", p.bright.toColor(), p.onBright.toColor(), actions::openSettings, icon = Icons.SETTINGS)
                }
                clips.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt("Nothing copied yet", 17.sp, label, bold = true)
                    Spacer(Modifier.height(4.dp))
                    Txt("Things you copy show up here. Pin the ones to keep.", 15.sp, sub)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(clips, key = { it.id }) { clip -> ClipCard(clip, p, actions) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("ABC", p.fn.toColor(), label, actions::backToKeys)
            Spacer(Modifier.weight(1f))
            if (clips.any { !it.pinned }) Pill("Clear", p.fn.toColor(), label, actions::clearClips, icon = Icons.TRASH)
        }
    }
}

@Composable
private fun ClipCard(clip: Clip, p: KeyPalette, actions: PanelActions) {
    val label = p.label.toColor()
    val sub = p.sub.toColor()
    var pressed by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, clip.id) {
        if (clip.image != null) {
            value = withContext(Dispatchers.IO) {
                val f = actions.clipFile(clip) ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outHeight / sample > 360) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (pressed) 0.97f else 1f
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(22.dp))
            .background(p.fn.toColor())
            .pointerInput(clip.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { actions.pasteClip(clip) },
                )
            }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            val img = image
            when {
                img != null -> Image(
                    img, contentDescription = "Copied image",
                    modifier = Modifier.heightIn(max = 120.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
                clip.image != null -> Txt("Image", 15.sp, label)
                else -> Txt(clip.text.orEmpty(), 15.sp, label, maxLines = 3)
            }
            Spacer(Modifier.height(4.dp))
            Txt((if (clip.pinned) "Pinned · " else "") + age(clip.time), 12.sp, sub, maxLines = 1)
        }
        IconBubble(
            Icons.PIN,
            if (clip.pinned) p.bright.toColor() else p.key.toColor(),
            if (clip.pinned) p.onBright.toColor() else label,
            onClick = { actions.pinClip(clip, !clip.pinned) },
            size = 38.dp, iconSize = 18.dp,
        )
        Spacer(Modifier.width(6.dp))
        IconBubble(Icons.TRASH, p.key.toColor(), label, onClick = { actions.removeClip(clip) }, size = 38.dp, iconSize = 18.dp)
    }
}

private fun age(time: Long): String {
    val s = (System.currentTimeMillis() - time) / 1000
    return when {
        s < 60 -> "Just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 2 * 86400 -> "Yesterday"
        else -> "${s / 86400} days ago"
    }
}
