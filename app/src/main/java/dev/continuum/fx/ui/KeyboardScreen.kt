package dev.continuum.fx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.fx.R
import dev.continuum.fx.keyboard.KeyboardPrefs
import dev.continuum.fx.ui.components.Chip
import dev.continuum.fx.ui.components.Surface
import dev.continuum.fx.ui.theme.LocalPalette
import dev.continuum.fx.ui.theme.Txt

/** Options for the Continuum keyboard, saved ahead of the keyboard itself. */
@Composable
internal fun KeyboardContent(state: UiState, actions: AppActions) {
    val p = LocalPalette.current
    val k = state.keyboard
    Column {
        Txt("Coming next · set it up now", 17.sp, p.sub, maxLines = 2)
        Gap(22.dp)

        Txt("Feel", 21.sp, p.text, bold = true)
        Gap(10.dp)
        OptionCard {
            ToggleRow("Ink presses", "Keys squash under your finger and a drop of ink rises with the letter.", k.inkPress) {
                actions.updateKeyboard { it.copy(inkPress = !it.inkPress) }
            }
            ChoiceRow(
                "Haptics", null,
                listOf(KeyboardPrefs.HAPTICS_OFF to "Off", KeyboardPrefs.HAPTICS_LIGHT to "Light", KeyboardPrefs.HAPTICS_FIRM to "Firm"),
                k.haptics,
            ) { v -> actions.updateKeyboard { it.copy(haptics = v) } }
            ToggleRow("Key sounds", "A soft tap with each key.", k.sounds) {
                actions.updateKeyboard { it.copy(sounds = !it.sounds) }
            }
        }
        Gap(28.dp)

        Txt("Layout", 21.sp, p.text, bold = true)
        Gap(10.dp)
        OptionCard {
            ChoiceRow(
                "Height", null,
                listOf(KeyboardPrefs.HEIGHT_COMPACT to "Compact", KeyboardPrefs.HEIGHT_REGULAR to "Regular", KeyboardPrefs.HEIGHT_TALL to "Tall"),
                k.height,
            ) { v -> actions.updateKeyboard { it.copy(height = v) } }
            ToggleRow("Number row", "Numbers along the top, always there.", k.numberRow) {
                actions.updateKeyboard { it.copy(numberRow = !it.numberRow) }
            }
            ToggleRow("Suggestions", "Word suggestions in a strip above the keys.", k.suggestions) {
                actions.updateKeyboard { it.copy(suggestions = !it.suggestions) }
            }
            ToggleRow("Dictation key", "A microphone key beside the space bar.", k.dictation) {
                actions.updateKeyboard { it.copy(dictation = !it.dictation) }
            }
        }
        Gap(28.dp)

        Txt("Typing", 21.sp, p.text, bold = true)
        Gap(10.dp)
        OptionCard {
            ToggleRow("Space bar cursor", "Slide along the space bar to move through your text.", k.spaceCursor) {
                actions.updateKeyboard { it.copy(spaceCursor = !it.spaceCursor) }
            }
        }
        Gap(28.dp)

        Txt("Look", 21.sp, p.text, bold = true)
        Gap(10.dp)
        OptionCard {
            ChoiceRow(
                "Keyboard colour", "Ink keeps it black whatever the system theme.",
                listOf(KeyboardPrefs.THEME_SYSTEM to "Match system", KeyboardPrefs.THEME_LIGHT to "Light", KeyboardPrefs.THEME_INK to "Ink"),
                k.theme,
            ) { v -> actions.updateKeyboard { it.copy(theme = v) } }
        }
        Gap(18.dp)
        Txt(
            "The keyboard itself comes after the current fixes. These choices are saved and apply as soon as it arrives.",
            15.sp, p.sub,
        )
    }
}

@Composable
private fun OptionCard(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    body: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp)) {
        Txt(title, 17.sp, p.text, maxLines = 1)
        if (body != null) Txt(body, 14.sp, p.sub)
        Gap(10.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                Chip(label, value == selected, onClick = { onPick(value) }, on = Surface.Card)
            }
        }
    }
}

@Composable
internal fun KeyboardDockBar() {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxSize().padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(p.inkTonal), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.ic_keyboard),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(p.onInk),
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt("Keyboard", 18.sp, p.onInk, bold = true, maxLines = 1)
            Txt("Arrives after the fixes", 14.sp, p.onInkSub, maxLines = 1)
        }
    }
}
