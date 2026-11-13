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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.continuum.keyboard.BuildConfig
import dev.continuum.keyboard.EngineHost
import dev.continuum.keyboard.Prefs
import dev.continuum.keyboard.R
import dev.continuum.keyboard.ui.Libron
import kotlinx.coroutines.flow.first

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
            // These chips use classic Autofill. Credential Manager/passkey settings alone may
            // not select an Autofill service. The package identifies the caller, not a vault to
            // read; Android presents the service picker and keeps control of the user's choice.
            add(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:$packageName")))
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
        val g = if (isSystemInDarkTheme()) DarkGround else LightGround
        CompositionLocalProvider(LocalGround provides g) { Page(section) }
    }

    /**
     * One page, laid out as a page of Continuum Kit: the title and its drop, the hero (what state
     * the keyboard is in, and a place to try it), then the ledger, a numbered rule for each group,
     * and About as the page's end matter. The Kit's Keyboard page opens it at a group ([section]):
     * that group's rule lands just under the folded title.
     */
    @Composable
    private fun Page(section: String?) {
        val g = LocalGround.current
        val prefs = remember { Prefs(this) }
        val engine = remember { EngineHost.get(this) }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // Re-checked whenever you come back from Android's keyboard settings.
        val seen = tick
        val isOn = remember(seen) { enabled() }
        val isPicked = remember(seen) { selected() }

        // Where each group's rule sits down the page, where the page's content ends, and how
        // much room is left under the last group so the one asked for can reach the top.
        val tops = remember { HashMap<String, Int>() }
        var end by remember { mutableIntStateOf(-1) }
        var viewport by remember { mutableIntStateOf(0) }
        var tail by remember { mutableIntStateOf(0) }
        fun Modifier.marks(key: String) = onPlaced { tops[key] = it.positionInParent().y.toInt() }
        LaunchedEffect(section) {
            val key = section?.let { SECTIONS[it] } ?: return@LaunchedEffect
            snapshotFlow { Triple(tops[key], end, viewport) }.first { (top, e, v) -> top != null && e > 0 && v > 0 }
            val air = with(density) { LANDING_AIR.roundToPx() }
            val top = (tops.getValue(key) - air).coerceAtLeast(0)
            // The last groups are shorter than the screen: give the page room after its end so
            // the group asked for still comes to the top, rather than landing halfway down.
            tail = (viewport - (end - top)).coerceAtLeast(0)
            snapshotFlow { scroll.maxValue }.first { it >= top }
            scroll.scrollTo(top)
        }

        // Read down the page and the title folds out of the way, as the Kit's pivot does.
        val foldAt = with(density) { 56.dp.toPx() }
        val openAt = with(density) { 12.dp.toPx() }
        var folded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            snapshotFlow { scroll.value }.collect { y ->
                if (!folded && y > foldAt) folded = true
                if (folded && y < openAt) folded = false
            }
        }
        val fold by animateFloatAsState(if (folded) 1f else 0f, Motion.flow(), label = "titleFold")

        Column(
            Modifier
                .fillMaxSize()
                .background(g.ground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            PageTitle("keyboard", { fold }, Modifier.padding(top = 14.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it.height }) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        // On a tablet or an open foldable, a comfortable reading width, from the
                        // title's edge, as the Kit's pages keep.
                        .wrapContentWidth(Alignment.Start)
                        .widthIn(max = CONTENT_MAX + 48.dp)
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp),
                ) {
                    Hero(isOn, isPicked)
                    var n = 0
                    if (!isOn || !isPicked) {
                        Section(++n, "Set it up", modifier = Modifier.marks("setup"))
                        SetupRow(
                            "Switch it on", "Turn on Continuum Keyboard in Android's keyboard list.", isOn,
                        ) {
                            WordButton("Open keyboard list", R.drawable.settings_ic_keyboard, { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, primary = true)
                        }
                        SetupRow("Make it your keyboard", "Pick it as the keyboard you type with.", isPicked) {
                            WordButton("Choose keyboard", R.drawable.settings_ic_keyboard, {
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                            }, primary = isOn)
                        }
                    }

                    Section(++n, "Look", modifier = Modifier.marks("look"))
                    var look by remember { mutableStateOf(prefs.look) }
                    FieldLabel("Colour")
                    Choices(
                        listOf(Prefs.LOOK_INK to "Ink") + (if (Build.VERSION.SDK_INT >= 31) listOf(Prefs.LOOK_TINT to "Tint") else emptyList()),
                        look,
                    ) { look = it; prefs.look = it }
                    var height by remember { mutableStateOf(prefs.keyHeight) }
                    FieldLabel("Key height")
                    Choices(
                        listOf(Prefs.HEIGHT_COMPACT to "Compact", Prefs.HEIGHT_NORMAL to "Normal", Prefs.HEIGHT_TALL to "Tall"),
                        height,
                    ) { height = it; prefs.keyHeight = it }
                    Gap(4.dp)
                    Toggle("Number row", "Numbers above the letters", prefs.numberRow) { prefs.numberRow = it }
                    Toggle("Number hints", "Show the number on each top-row letter", prefs.numberHints) { prefs.numberHints = it }

                    Section(++n, "Typing", modifier = Modifier.marks("typing"))
                    Toggle("Autocorrect", "Fix typos when you press space. Delete straight after to undo.", prefs.autocorrect) { prefs.autocorrect = it }
                    Toggle("Capitals", "Start sentences with a capital", prefs.autoCaps) { prefs.autoCaps = it }
                    Toggle("Double space for full stop", null, prefs.doubleSpace) { prefs.doubleSpace = it }
                    Toggle("Vibrate on keypress", null, prefs.vibrate) { prefs.vibrate = it }
                    Toggle("Sound on keypress", null, prefs.sound) { prefs.sound = it }

                    Section(++n, "Swiping", modifier = Modifier.marks("swipe"))
                    Toggle("Swipe typing", "Slide across the letters to type a word", prefs.swipe) { prefs.swipe = it }
                    Toggle("Ink trail", "Show where your finger went", prefs.swipeTrail) { prefs.swipeTrail = it }
                    Toggle("Swipe to delete words", "Drag left from delete to select whole words, lift to delete", prefs.deleteSwipe) { prefs.deleteSwipe = it }
                    Toggle("Slide on space", "Move the cursor by sliding along the space bar", prefs.spaceCursor) { prefs.spaceCursor = it }

                    Section(++n, "Clipboard", modifier = Modifier.marks("clipboard"))
                    var history by remember { mutableStateOf(prefs.clipboardHistory) }
                    SwitchRow("Clipboard history", "Keep what you copy, on this phone only. Passwords are never kept.", history) {
                        history = !history
                        prefs.clipboardHistory = history
                    }
                    Reveal(history) {
                        var keep by remember { mutableStateOf(prefs.clipboardKeep) }
                        FieldLabel("Keep for")
                        // Six lengths, set out three to a line (fewer at large type), one set: the ink
                        // runs to whichever you choose, across lines too.
                        val lengths = listOf("1h" to "1 hour", "1d" to "1 day", "3d" to "3 days", "1w" to "1 week", "1m" to "1 month", "forever" to "Forever")
                        Choices(lengths, keep, perLine = 3) { keep = it; prefs.clipboardKeep = it }
                        Txt("Pinned clips stay until you unpin them.", 14.sp, g.sub, Modifier.padding(start = INK_RAIL, top = 2.dp, bottom = 4.dp))
                        Toggle("Paste suggestion", "Offer what you just copied in the strip", prefs.clipboardChip) { prefs.clipboardChip = it }
                    }

                    Section(++n, "Passwords", modifier = Modifier.marks("passwords"))
                    if (Build.VERSION.SDK_INT >= 30) {
                        Toggle(
                            "Logins in the strip",
                            "When you tap a login field, your password manager's logins appear as chips above the keys. Off, they're left to the password manager, which may show its own pop-up.",
                            prefs.passwordChips,
                        ) { prefs.passwordChips = it }
                    }
                    Toggle(
                        "One-time codes",
                        "Sign-in codes from your messages and emails, offered in the strip as they arrive. They come from Continuum Kit on this phone; the keyboard still never goes online.",
                        prefs.codes,
                    ) { prefs.codes = it }
                    val manager = remember(tick) { autofillServiceName() }
                    InfoRow(
                        "Password manager",
                        if (manager != null) "$manager fills your logins."
                        else "None chosen yet. Pick yours (Keyguard, Bitwarden, Proton Pass and the like) as Android's autofill service.",
                    ) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Txt(
                                "For chips in the strip, choose your password manager as Android's autofill service (choosing it for passkeys alone isn't enough) and keep its inline suggestions on. In Keyguard: Settings, Autofill, Inline suggestions. After changing either, leave the login field and come back to it.",
                                // A paragraph of its own under the caption, About's 10dp apart.
                                14.sp, g.sub, Modifier.padding(top = 10.dp),
                            )
                        }
                        Gap(4.dp)
                        WordButton(
                            if (manager != null) "Change password manager" else "Choose a password manager",
                            R.drawable.settings_ic_key, { openAutofillSettings() }, primary = manager == null,
                        )
                    }

                    Section(++n, "Words", modifier = Modifier.marks("words"))
                    var count by remember { mutableIntStateOf(engine.learned.wordCount) }
                    var confirm by remember { mutableStateOf(false) }
                    InfoRow(
                        "Learned words",
                        "Continuum Keyboard learns the words you use and how you put them together, so suggestions get better. " +
                            "It stays on this phone. Long-press a suggestion to stop it coming up. ${if (count == 1) "1 new word" else "$count new words"} so far.",
                    ) {
                        Gap(4.dp)
                        WordButton(if (confirm) "Tap again to forget everything" else "Forget learned words", R.drawable.settings_ic_trash, {
                            if (confirm) {
                                engine.forgetEverything()
                                count = 0
                                confirm = false
                            } else confirm = true
                        }, primary = confirm)
                    }

                    // The keyboard's colophon: its build, where its updates are, and whose work it
                    // stands on. End matter, so its rule has no number.
                    Section(null, "Continuum Keyboard", trail = "Build ${BuildConfig.BUILD_NUMBER}", modifier = Modifier.marks("about"))
                    Column(Modifier.padding(start = INK_RAIL, top = 4.dp)) {
                        Txt(
                            "Continuum's own keyboard. It has no internet access: your typing, learned words and clipboard stay on your phone.",
                            14.sp, g.sub,
                        )
                        Gap(4.dp)
                        // No internet here, so the keyboard can't look for updates itself: this opens
                        // the releases in your browser, and Continuum Kit's Keyboard tab installs them.
                        WordButton("Check for updates on GitHub", R.drawable.settings_ic_refresh, { openReleases() })
                        Txt(
                            "Opens the keyboard's builds in your browser: anything above build ${BuildConfig.BUILD_NUMBER} is newer. " +
                                "Continuum Kit's Keyboard tab also checks for them, and installs them over this one.",
                            14.sp, g.sub,
                        )
                        Gap(10.dp)
                        Txt(
                            "Word frequencies from wordfreq (CC BY-SA 4.0). Spellings from SCOWL. Word pairs from the Google Books Ngram data via SymSpell (CC BY 3.0). " +
                                "Emoji data from Emojibase (MIT). Text recognition by ML Kit. Typefaces: Libron (OFL) and Inter (OFL).",
                            13.sp, g.quiet,
                        )
                    }
                    Spacer(Modifier.height(32.dp).onPlaced { end = it.positionInParent().y.toInt() + it.size.height })
                    // Room for the group you were sent to to reach the top, when it's one of the last.
                    if (tail > 0) Spacer(Modifier.height(with(density) { tail.toDp() }))
                }
                // The page dissolves into the ground under the title, as the Kit's do, so a line is
                // never cut in half by its edge.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .graphicsLayer { alpha = (scroll.value / 10.dp.toPx()).coerceIn(0f, 1f) }
                        .background(
                            Brush.verticalGradient(
                                0f to g.ground,
                                0.35f to g.ground.copy(alpha = 0.85f),
                                0.7f to g.ground.copy(alpha = 0.3f),
                                1f to g.ground.copy(alpha = 0f),
                            ),
                        ),
                )
            }
        }
    }

    /**
     * The top of a page of its own, laid out as the Kit's Lab is: the title above is the Kit page
     * it hangs from ("keyboard"); the kicker names this page; the line says how the keyboard
     * stands; and at the line's end, where a Kit page has its switch, is the way back. Then a
     * place to try it, as the Lab has its graph.
     */
    @Composable
    private fun Hero(isOn: Boolean, isPicked: Boolean) {
        val g = LocalGround.current
        Kicker("Settings")
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroLine(
                when {
                    !isOn -> "Not switched on yet."
                    !isPicked -> "On, but not in use."
                    else -> "In use."
                },
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // Back to wherever you came from (the Kit's Keyboard page, or the keyboard). Its words
            // end on the margin: a button keeps 18dp of room after it, which the margin provides.
            Box(Modifier.offset(x = 18.dp)) { WordButton("Done", R.drawable.settings_ic_check, { finish() }) }
        }
        Gap(14.dp)
        TryIt(g)
        Gap(20.dp)
    }

    /** A step of getting the keyboard going: what it is, done or not, and the move under it. */
    @Composable
    private fun SetupRow(label: String, caption: String, done: Boolean, action: @Composable () -> Unit) {
        val g = LocalGround.current
        InfoRow(label, caption, trailing = {
            // A drop beside "On" once it's done; "Needs you" in the one colour that means it.
            if (done) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(g.text))
                Spacer(Modifier.width(6.dp))
            }
            Note(if (done) "On" else "Needs you", if (done) g.sub else g.danger)
        }) {
            if (!done) {
                Gap(4.dp)
                action()
            }
        }
    }

    /** Somewhere to try the keyboard: words on a hairline, as the Kit's fields are. */
    @Composable
    private fun TryIt(g: Ground) {
        var text by remember { mutableStateOf("") }
        Box(
            Modifier
                .padding(start = INK_RAIL)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .drawBehind {
                    val y = size.height - 1.dp.toPx()
                    drawLine(g.quiet, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Txt("Try it here", 17.sp, g.sub)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = Libron),
                cursorBrush = SolidColor(g.text),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Try it here" },
            )
        }
    }

    companion object {
        /** Opens settings at a section: setup, look, typing, swipe, clipboard, passwords, words or about. */
        const val EXTRA_SECTION = "section"

        /** The sections the Kit (or anyone) can open this at, by the name they're asked for. */
        private val SECTIONS = listOf("setup", "look", "typing", "swipe", "clipboard", "passwords", "words", "about").associateWith { it }

        /** How far above a group's rule the page stops when it's opened at that group. */
        private val LANDING_AIR = 14.dp

        /** The widest the page grows on large screens: the Kit's reading width. */
        private val CONTENT_MAX = 600.dp
    }
}

@Composable
private fun Gap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

/** A setting that's a switch: its own state, kept as the page shows it, handed back on each change. */
@Composable
private fun Toggle(title: String, body: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    SwitchRow(title, body, on) {
        on = !on
        onChange(on)
    }
}

/** A setting chosen from a few: one row of the Kit's flowing choices. */
@Composable
private fun Choices(options: List<Pair<String, String>>, value: String, perLine: Int = Int.MAX_VALUE, onPick: (String) -> Unit) {
    ChoiceRow(Modifier.padding(start = INK_RAIL), perLine = perLine) {
        options.forEach { (key, label) -> Chip(label, key == value) { onPick(key) } }
    }
}
