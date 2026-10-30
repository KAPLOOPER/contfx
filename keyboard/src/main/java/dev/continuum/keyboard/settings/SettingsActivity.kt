package dev.continuum.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.keyboard.BuildConfig
import dev.continuum.keyboard.EngineHost
import dev.continuum.keyboard.Prefs
import dev.continuum.keyboard.ui.Icons
import dev.continuum.keyboard.ui.Libron
import dev.continuum.keyboard.ui.LineIcon
import dev.continuum.keyboard.ui.Motion
import dev.continuum.keyboard.ui.Pill
import dev.continuum.keyboard.ui.Txt

private data class Ground(
    val ground: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    val line: Color,
    val ink: Color,
    val onInk: Color,
)

private val Light = Ground(Color(0xFFEEEEEB), Color(0xFFE0E0DC), Color(0xFF121211), Color(0xFF5C5C58), Color(0xFFCFCFCA), Color(0xFF000000), Color(0xFFF4F4F1))
private val Dark = Ground(Color(0xFF161615), Color(0xFF262624), Color(0xFFEDEDEA), Color(0xFFA3A39E), Color(0xFF3A3A37), Color(0xFFEDEDEA), Color(0xFF121211))

/** Continuum Keyboard's settings, and getting it switched on. */
class SettingsActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(EXTRA_SECTION)
        setContent { Screen(section) }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tick++
    }

    private fun enabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    /** The keyboard's builds on GitHub, in the browser (the keyboard itself never goes online). */
    private fun openReleases() {
        val url = "https://github.com/${BuildConfig.UPDATE_REPO}/releases?q=%22Continuum+Keyboard%22&expanded=true"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "No browser to open it with", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** The autofill service's app name, or null when none is chosen. */
    private fun autofillServiceName(): String? = try {
        val component = getSystemService(android.view.autofill.AutofillManager::class.java)?.autofillServiceComponentName
        component?.let { c ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
            } catch (_: Exception) {
                c.packageName
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Android's own place for choosing the password manager, wherever this version keeps it. */
    private fun openAutofillSettings() {
        val tries = buildList {
            // Android 14 and later: Passwords, passkeys and accounts.
            if (Build.VERSION.SDK_INT >= 34) add(Intent("android.settings.CREDENTIAL_PROVIDER"))
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in tries) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun selected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    @Composable
    private fun Screen(section: String?) {
        val g = if (isSystemInDarkTheme()) Dark else Light
        val prefs = remember { Prefs(this) }
        val engine = remember { EngineHost.get(this) }
        val list = rememberLazyListState()
        val sections = listOf("setup", "look", "typing", "swipe", "clipboard", "words", "about")
        LaunchedEffect(section) {
            val i = sections.indexOf(section)
            if (i > 0) list.scrollToItem(i + 1)
        }
        // Re-checked whenever you come back from Android's keyboard settings.
        val seen = tick
        val isOn = remember(seen) { enabled() }
        val isPicked = remember(seen) { selected() }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(g.ground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            state = list,
        ) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                    Txt("Keyboard", 34.sp, g.text, bold = true)
                    Txt(
                        when {
                            !isOn -> "Switch it on to start typing."
                            !isPicked -> "On, but another keyboard is in use."
                            else -> "In use · build ${BuildConfig.BUILD_NUMBER}"
                        },
                        16.sp, g.sub,
                    )
                }
            }
            item {
                if (!isOn || !isPicked) Card(g) {
                    Step(g, 1, isOn, "Switch it on", "Turn on Continuum Keyboard in Android's keyboard list.") {
                        Pill("Open keyboard list", g.ink, g.onInk, { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
                    }
                    Step(g, 2, isPicked, "Make it your keyboard", "Pick it as the keyboard you type with.") {
                        Pill("Choose keyboard", g.ink, g.onInk, {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                        })
                    }
                }
                TryIt(g)
            }
            item {
                Heading(g, "Look")
                Card(g) {
                    var look by remember { mutableStateOf(prefs.look) }
                    Choice(
                        g, "Colour",
                        listOf(Prefs.LOOK_INK to "Ink") + (if (Build.VERSION.SDK_INT >= 31) listOf(Prefs.LOOK_TINT to "Tint") else emptyList()),
                        look,
                    ) { look = it; prefs.look = it }
                    var height by remember { mutableStateOf(prefs.keyHeight) }
                    Choice(
                        g, "Key height",
                        listOf(Prefs.HEIGHT_COMPACT to "Compact", Prefs.HEIGHT_NORMAL to "Normal", Prefs.HEIGHT_TALL to "Tall"),
                        height,
                    ) { height = it; prefs.keyHeight = it }
                    Toggle(g, "Number row", "Numbers above the letters", prefs.numberRow) { prefs.numberRow = it }
                    Toggle(g, "Number hints", "Show the number on each top-row letter", prefs.numberHints) { prefs.numberHints = it }
                    Toggle(g, "Letter drops", "Letters rise out of the key as you press", prefs.balloons) { prefs.balloons = it }
                }
            }
            item {
                Heading(g, "Typing")
                Card(g) {
                    Toggle(g, "Autocorrect", "Fix typos when you press space. Delete straight after to undo.", prefs.autocorrect) { prefs.autocorrect = it }
                    Toggle(g, "Capitals", "Start sentences with a capital", prefs.autoCaps) { prefs.autoCaps = it }
                    Toggle(g, "Double space for full stop", null, prefs.doubleSpace) { prefs.doubleSpace = it }
                    Toggle(g, "Vibrate on keypress", null, prefs.vibrate) { prefs.vibrate = it }
                    Toggle(g, "Sound on keypress", null, prefs.sound) { prefs.sound = it }
                }
            }
            item {
                Heading(g, "Swiping")
                Card(g) {
                    Toggle(g, "Swipe typing", "Slide across the letters to type a word", prefs.swipe) { prefs.swipe = it }
                    Toggle(g, "Ink trail", "Show where your finger went", prefs.swipeTrail) { prefs.swipeTrail = it }
                    Toggle(g, "Swipe to delete words", "Drag left from delete to select whole words, lift to delete", prefs.deleteSwipe) { prefs.deleteSwipe = it }
                    Toggle(g, "Slide on space", "Move the cursor by sliding along the space bar", prefs.spaceCursor) { prefs.spaceCursor = it }
                }
            }
            item {
                Heading(g, "Clipboard")
                Card(g) {
                    var on by remember { mutableStateOf(prefs.clipboardHistory) }
                    Toggle(g, "Clipboard history", "Keep what you copy, on this phone only. Passwords are never kept.", on) { on = it; prefs.clipboardHistory = it }
                    if (on) {
                        var keep by remember { mutableStateOf(prefs.clipboardKeep) }
                        Choice(
                            g, "Keep for",
                            listOf("1h" to "1 hour", "1d" to "1 day", "3d" to "3 days", "1w" to "1 week", "1m" to "1 month", "forever" to "Forever"),
                            keep,
                        ) { keep = it; prefs.clipboardKeep = it }
                        Txt("Pinned clips stay until you unpin them.", 14.sp, g.sub, Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                        Toggle(g, "Paste suggestion", "Offer what you just copied in the strip", prefs.clipboardChip) { prefs.clipboardChip = it }
                    }
                }
            }
            item {
                Heading(g, "Passwords")
                Card(g) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        Toggle(
                            g, "Logins in the strip",
                            "When you tap a login field, your password manager's logins appear as chips above the keys. Off, it shows its own pop-up instead.",
                            prefs.passwordChips,
                        ) { prefs.passwordChips = it }
                    }
                    val manager = remember(tick) { autofillServiceName() }
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Txt("Password manager", 17.sp, g.text, bold = true)
                        Txt(
                            if (manager != null) "$manager fills your logins."
                            else "None chosen yet. Pick yours (Keyguard, Bitwarden, Proton Pass and the like) as Android's autofill service.",
                            14.sp, g.sub,
                        )
                        if (Build.VERSION.SDK_INT >= 30) {
                            Spacer(Modifier.height(6.dp))
                            Txt(
                                "For chips in the strip, the password manager's inline suggestions need to be on. In Keyguard: Settings, Autofill, Inline suggestions (on unless you've turned it off).",
                                13.sp, g.sub,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Pill(if (manager != null) "Change password manager" else "Choose a password manager", g.surface, g.text, { openAutofillSettings() })
                    }
                }
            }
            item {
                Heading(g, "Words")
                Card(g) {
                    var count by remember { mutableIntStateOf(engine.learned.wordCount) }
                    var confirm by remember { mutableStateOf(false) }
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Txt("Learned words", 17.sp, g.text, bold = true)
                        Txt(
                            "Continuum Keyboard learns the words you use and how you put them together, so suggestions get better. " +
                                "It stays on this phone. Long-press a suggestion to stop it coming up. ${if (count == 1) "1 new word" else "$count new words"} so far.",
                            14.sp, g.sub,
                        )
                        Spacer(Modifier.height(12.dp))
                        Pill(if (confirm) "Tap again to forget everything" else "Forget learned words", g.surface, g.text, {
                            if (confirm) {
                                engine.forgetEverything()
                                count = 0
                                confirm = false
                            } else confirm = true
                        }, icon = Icons.TRASH)
                    }
                }
            }
            item {
                Heading(g, "About")
                Card(g) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Txt("Continuum Keyboard · build ${BuildConfig.BUILD_NUMBER}", 17.sp, g.text, bold = true)
                        Txt(
                            "Continuum's own keyboard. It has no internet access: your typing, learned words and clipboard stay on your phone.",
                            14.sp, g.sub,
                        )
                        Spacer(Modifier.height(12.dp))
                        // No internet here, so the keyboard can't look for updates itself: this opens
                        // the releases in your browser, and Continuum FX's Keyboard tab installs them.
                        Pill("Check for updates on GitHub", g.surface, g.text, { openReleases() })
                        Spacer(Modifier.height(6.dp))
                        Txt(
                            "Opens the keyboard's builds in your browser: anything above build ${BuildConfig.BUILD_NUMBER} is newer. " +
                                "Continuum FX's Keyboard tab also checks for them, and installs them over this one.",
                            13.sp, g.sub,
                        )
                        Spacer(Modifier.height(10.dp))
                        Txt(
                            "Word frequencies from wordfreq (CC BY-SA 4.0). Spellings from SCOWL. Word pairs from the Google Books Ngram data via SymSpell (CC BY 3.0). " +
                                "Emoji data from Emojibase (MIT). Text recognition by ML Kit. Typeface: Libron (OFL).",
                            13.sp, g.sub,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    @Composable
    private fun TryIt(g: Ground) {
        var text by remember { mutableStateOf("") }
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(g.surface)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if (text.isEmpty()) Txt("Try it here", 17.sp, g.sub)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = Libron),
                cursorBrush = SolidColor(g.text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    companion object {
        /** Opens settings at a section: look, typing, swipe, clipboard, words or about. */
        const val EXTRA_SECTION = "section"
    }
}

@Composable
private fun Heading(g: Ground, title: String) {
    Txt(title, 21.sp, g.text, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp), bold = true)
}

@Composable
private fun Card(g: Ground, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(g.surface)
            .padding(vertical = 6.dp),
    ) { content() }
}

@Composable
private fun Step(g: Ground, number: Int, done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (done) Color(0xFF97C459) else g.ground),
            contentAlignment = Alignment.Center,
        ) {
            if (done) LineIcon(Icons.CHECK, Color(0xFF173404), 18.dp) else Txt("$number", 15.sp, g.text, bold = true)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Txt(title, 17.sp, g.text, bold = true)
            Txt(body, 14.sp, g.sub)
            if (!done) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

@Composable
private fun Toggle(g: Ground, title: String, body: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { on = !on; onChange(on) } }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 17.sp, g.text)
            if (body != null) Txt(body, 14.sp, g.sub)
        }
        Spacer(Modifier.width(12.dp))
        Switch(g, on)
    }
}

/** A flat switch: the knob is a drop of ground that slides along the ink. */
@Composable
private fun Switch(g: Ground, on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, Motion.flow(), label = "knob")
    val track by animateColorAsState(if (on) g.ink else g.line, label = "track")
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(50)).background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = (4 + 20 * x).dp)
                .size(24.dp)
                .graphicsLayer { scaleX = 1f + 0.15f * (1f - kotlin.math.abs(x - 0.5f) * 2f); scaleY = 1f }
                .clip(CircleShape)
                .background(if (on) g.onInk else g.ground),
        )
    }
}

@Composable
private fun Choice(g: Ground, title: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Txt(title, 17.sp, g.text)
        Spacer(Modifier.height(8.dp))
        // Wraps onto as many rows as it needs.
        val rows = options.chunked(3)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (key, label) ->
                        val on = key == value
                        Pill(label, if (on) g.ink else g.ground, if (on) g.onInk else g.text, { onPick(key) }, height = 40.dp)
                    }
                }
            }
        }
    }
}
