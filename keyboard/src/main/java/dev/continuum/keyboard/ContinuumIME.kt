package dev.continuum.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.continuum.keyboard.clipboard.Clip
import dev.continuum.keyboard.clipboard.ClipboardHistory
import dev.continuum.keyboard.emoji.EmojiData
import dev.continuum.keyboard.emoji.EmojiUsage
import dev.continuum.keyboard.engine.Caps
import dev.continuum.keyboard.engine.KeyMap
import dev.continuum.keyboard.engine.Suggester
import dev.continuum.keyboard.engine.Suggestions
import dev.continuum.keyboard.engine.TextRules
import dev.continuum.keyboard.engine.Typed
import dev.continuum.keyboard.layout.FieldMode
import dev.continuum.keyboard.layout.Key
import dev.continuum.keyboard.layout.KeyType
import dev.continuum.keyboard.layout.Layout
import dev.continuum.keyboard.layout.Layouts
import dev.continuum.keyboard.layout.Sheet
import dev.continuum.keyboard.scan.ScanResult
import dev.continuum.keyboard.scan.ScanTextActivity
import dev.continuum.keyboard.settings.SettingsActivity
import dev.continuum.keyboard.ui.KeyPalette
import dev.continuum.keyboard.ui.KeyboardView
import dev.continuum.keyboard.ui.PanelActions
import dev.continuum.keyboard.ui.PanelState
import dev.continuum.keyboard.ui.Panels
import dev.continuum.keyboard.ui.Strip
import dev.continuum.keyboard.ui.Tool
import java.io.File
import java.text.BreakIterator
import java.util.concurrent.Executors

/**
 * Continuum Keyboard. Owns what's being typed: the word in progress, corrections and their
 * undo, spacing around punctuation, shift and caps, swipes, delete-swipe, the panels,
 * autofill chips and text from Scan text.
 */
class ContinuumIME : InputMethodService(), KeyboardView.Listener, PanelActions,
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    // ---- Lifecycle, so Compose can run inside the keyboard window. ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var prefs: Prefs
    private lateinit var engine: EngineHost
    private lateinit var clips: ClipboardHistory
    private lateinit var emojiUsage: EmojiUsage
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private var root: FrameLayout? = null
    private var keyboard: KeyboardView? = null
    private var panelView: ComposeView? = null
    private var autofillBar: HorizontalScrollView? = null
    private var autofillRow: LinearLayout? = null
    private val panelState = PanelState()

    // ---- The field. ----
    private var mode = FieldMode.Text
    private var sheet = Sheet.Letters
    private var suggestionsOn = false
    private var autocorrectOn = false
    private var learningOn = false
    private var capsField = false
    private var enterAction = EditorInfo.IME_ACTION_NONE
    private var enterLabel = ""

    // ---- The word in progress. ----
    private val composing = StringBuilder()
    private var touchX = FloatArray(48)
    private var touchY = FloatArray(48)
    private var composingFromSwipe = false
    private var swipeWords: List<String> = emptyList()
    /** A space is owed before the next word (after a swipe). */
    private var phantomSpace = false
    /** The space before the cursor was added by picking a suggestion. */
    private var autoSpaced = false
    /** Restored by undoing a correction: commit it exactly as typed. */
    private var noCorrect = false
    private var current: Suggestions = Suggestions.None
    private var stripWords: List<String> = emptyList()

    private class Correction(val typed: String, val word: String, val separator: String)
    private var lastCorrection: Correction? = null

    // ---- Keys. ----
    /** 0 off, 1 next letter, 2 locked. */
    private var shift = 0
    private var shiftAuto = false
    private var lastShiftTap = 0L
    private var lastSpace = 0L
    private var keyMap: KeyMap? = null

    // ---- Strip and panels. ----
    private enum class StripChoice { Auto, Tools, Words }
    private var stripChoice = StripChoice.Auto
    private var panel: Tool? = null
    private var autofillShowing = false
    private var chipUntil = 0L
    private var recomposeAfterDelete = false
    private var deleteAnchor = -1
    private var deleteBefore = ""
    private var selStart = -1
    private var selEnd = -1

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }
    private var stopClipListening: (() -> Unit)? = null

    // ================================================================ Lifecycle

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        prefs = Prefs(this)
        engine = EngineHost.get(this)
        clips = ClipboardHistory.get(this)
        emojiUsage = EmojiUsage(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener(clipListener)
        stopClipListening = clips.listen { panelState.clips = clips.clips }
        panelState.clips = clips.clips
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipListener)
        stopClipListening?.invoke()
        engine.save()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { d ->
            d.setViewTreeLifecycleOwner(this)
            d.setViewTreeViewModelStoreOwner(this)
            d.setViewTreeSavedStateRegistryOwner(this)
        }
        val frame = FrameLayout(this)
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeViewModelStoreOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)

        val kv = KeyboardView(this, this)
        kv.sound = { key -> playSound(key) }
        frame.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val panels = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Panels(panelState, this@ContinuumIME) }
            visibility = View.GONE
        }
        frame.addView(panels, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            visibility = View.GONE
        }
        frame.addView(bar, FrameLayout.LayoutParams(0, 0))

        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            kv.bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            insets
        }

        root = frame
        keyboard = kv
        panelView = panels
        autofillBar = bar
        autofillRow = row
        applyLook()
        return frame
    }

    private fun applyLook() {
        val kv = keyboard ?: return
        val palette = KeyPalette.from(this, prefs)
        kv.palette = palette
        panelState.palette = palette
        kv.heightScale = prefs.heightScale
        kv.numberRow = prefs.numberRow
        kv.balloonsOn = prefs.balloons
        kv.swipeOn = prefs.swipe
        kv.trailOn = prefs.swipeTrail
        kv.deleteSwipeOn = prefs.deleteSwipe
        kv.spaceCursorOn = prefs.spaceCursor
        kv.numberHints = prefs.numberHints
        kv.vibrate = prefs.vibrate
        panelState.historyOn = prefs.clipboardHistory
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = false
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyLook()
        readField(info)
        resetComposing()
        lastCorrection = null
        autoSpaced = false
        phantomSpace = false
        stripChoice = StripChoice.Auto
        closePanel(animate = false)
        clearAutofill()
        clips.expire(prefs.clipboardKeepMillis)
        sheet = when (mode) {
            FieldMode.Number -> Sheet.Numbers
            FieldMode.Phone -> Sheet.Phone
            else -> Sheet.Letters
        }
        shift = 0
        shiftAuto = false
        showSheet(animate = false)
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        updateShift()
        updateEnter()
        refreshStrip()
        if (!restarting) keyboard?.popIn()

        // Text from Scan text goes in once the field you came from is back.
        ScanResult.take()?.let { text ->
            main.postDelayed({ currentInputConnection?.commitText(text, 1); afterEdit() }, 150)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        resetComposing()
        closePanel(animate = false)
        clearAutofill()
        engine.save()
        super.onFinishInputView(finishingInput)
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val frame = root ?: return
        val kv = keyboard ?: return
        if (!frame.isShown) return
        val loc = IntArray(2)
        frame.getLocationInWindow(loc)
        val top = loc[1] + kv.massTop.toInt()
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    private fun readField(info: EditorInfo) {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val flags = type and InputType.TYPE_MASK_FLAGS
        mode = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldMode.Number
            InputType.TYPE_CLASS_PHONE -> FieldMode.Phone
            else -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldMode.Email
                InputType.TYPE_TEXT_VARIATION_URI -> FieldMode.Url
                else -> FieldMode.Text
            }
        }
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val plainText = cls == InputType.TYPE_CLASS_TEXT && !password
        suggestionsOn = plainText && (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        autocorrectOn = suggestionsOn && prefs.autocorrect && mode == FieldMode.Text && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        )
        learningOn = suggestionsOn && mode == FieldMode.Text &&
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        capsField = plainText && prefs.autoCaps

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        enterAction = if (noAction) EditorInfo.IME_ACTION_NONE else action
        enterLabel = when {
            noAction -> ""
            else -> when (action) {
                EditorInfo.IME_ACTION_GO -> "go"
                EditorInfo.IME_ACTION_SEARCH -> "search"
                EditorInfo.IME_ACTION_SEND -> "send"
                EditorInfo.IME_ACTION_NEXT -> "next"
                EditorInfo.IME_ACTION_DONE -> "done"
                EditorInfo.IME_ACTION_PREVIOUS -> "back"
                else -> ""
            }
        }
        if (enterLabel.isEmpty()) enterAction = EditorInfo.IME_ACTION_NONE
    }

    private fun showSheet(animate: Boolean) {
        val kv = keyboard ?: return
        val numberRow = prefs.numberRow && mode != FieldMode.Number && mode != FieldMode.Phone
        val rows = Layouts.rows(sheet, mode, enterLabel, numberRow)
        val letters = Layouts.rows(Sheet.Letters, mode, enterLabel, numberRow)
        kv.setSheet(sheet, rows, letters, animate)
    }

    // ================================================================ Keyboard callbacks

    override fun onLayout(layout: Layout) {
        if (layout.sheet == Sheet.Letters) {
            keyMap = layout.keyMap()
            engine.setKeys(keyMap)
        }
    }

    override fun onGeometry() {
        positionOverlays()
    }

    private fun positionOverlays() {
        val kv = keyboard ?: return
        panelView?.let { pv ->
            val lp = pv.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = kv.keysTop.toInt()
            lp.height = (kv.keysBottom - kv.keysTop).toInt() + (4 * resources.displayMetrics.density).toInt()
            pv.layoutParams = lp
        }
        autofillBar?.let { bar ->
            val a = kv.stripArea
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = a.left.toInt()
            lp.topMargin = a.top.toInt()
            lp.width = a.width().toInt()
            lp.height = a.height().toInt()
            bar.layoutParams = lp
        }
    }

    override fun onText(text: String, x: Float, y: Float) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        val c = text.singleOrNull()
        val wordChar = c != null && (c.isLetter() || (c == '\'' || c.isDigit()) && composing.isNotEmpty())
        if (suggestionsOn && wordChar) {
            typeLetter(ic, shifted(text), x, y)
        } else if (c != null && c.isLetterOrDigit()) {
            ic.commitText(shifted(text), 1)
        } else {
            typeSymbol(ic, text)
        }
        if (text.length == 1 && text[0].isLetter() && shift == 1) {
            shift = 0
            shiftAuto = false
            keyboard?.shift = 0
        }
        afterEdit()
    }

    private fun shifted(text: String) = if (shift > 0 && text.length == 1 && text[0].isLetter()) text.uppercase() else text

    private fun typeLetter(ic: InputConnection, text: String, x: Float, y: Float) {
        if (phantomSpace || composingFromSwipe) {
            // The swiped word stands; this starts the next one.
            commitComposing(ic, correct = false, separator = " ")
        }
        autoSpaced = false
        val i = composing.length
        if (i >= touchX.size) {
            touchX = touchX.copyOf(i * 2)
            touchY = touchY.copyOf(i * 2)
        }
        composing.append(text)
        touchX[i] = x
        touchY[i] = y
        ic.setComposingText(composing.toString(), 1)
    }

    private fun typeSymbol(ic: InputConnection, text: String) {
        val c = text.singleOrNull()
        if (composing.isNotEmpty()) {
            val triggers = c != null && c in ".,!?;:)]}\"…"
            commitComposing(ic, correct = triggers && !composingFromSwipe, separator = "")
        }
        if (c != null && TextRules.hugsWordBefore(c) && autoSpaced && endsWithSpace(ic)) {
            // "word |" + "." becomes "word. |".
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(if (TextRules.wantsSpaceAfter(c)) "$text " else text, 1)
            ic.endBatchEdit()
            autoSpaced = TextRules.wantsSpaceAfter(c)
        } else {
            ic.commitText(text, 1)
            autoSpaced = false
        }
        phantomSpace = false
    }

    private fun endsWithSpace(ic: InputConnection): Boolean = ic.getTextBeforeCursor(1, 0)?.toString() == " "

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        lastCorrection = null
        when {
            composing.isNotEmpty() -> commitComposing(ic, correct = !composingFromSwipe, separator = " ")
            autoSpaced && endsWithSpace(ic) -> {
                // The space is already there; a second tap soon after makes a full stop.
                autoSpaced = false
                lastSpace = now
                afterEdit()
                return
            }
            prefs.doubleSpace && suggestionsOn && now - lastSpace < 600 && doubleSpaceable(ic) -> {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpace = 0
                afterEdit()
                return
            }
            else -> ic.commitText(" ", 1)
        }
        phantomSpace = false
        lastSpace = now
        afterEdit()
    }

    private fun doubleSpaceable(ic: InputConnection): Boolean {
        val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        return b.length == 2 && b[1] == ' ' && (b[0].isLetterOrDigit() || b[0] in "\"')")
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(ic, correct = !composingFromSwipe, separator = "")
        lastCorrection = null
        autoSpaced = false
        phantomSpace = false
        if (enterAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(enterAction)
        } else {
            ic.commitText("\n", 1)
        }
        afterEdit()
    }

    override fun onDelete(repeat: Boolean) {
        val ic = currentInputConnection ?: return
        val undo = lastCorrection
        lastCorrection = null
        autoSpaced = false
        if (!repeat && undo != null && composing.isEmpty()) {
            val len = undo.word.length + undo.separator.length
            val before = ic.getTextBeforeCursor(len, 0)?.toString()
            if (before == undo.word + undo.separator) {
                // Undo the correction: back to exactly what was typed, and keep it that way.
                ic.beginBatchEdit()
                ic.deleteSurroundingText(len, 0)
                composing.append(undo.typed)
                forgetTouches(composing.length)
                ic.setComposingText(composing.toString(), 1)
                ic.endBatchEdit()
                noCorrect = true
                if (learningOn) engine.later { engine.learned.insist(undo.typed) }
                afterEdit()
                return
            }
        }
        if (composing.isNotEmpty()) {
            if (composingFromSwipe) {
                // A swiped word goes in one go.
                ic.setComposingText("", 1)
                resetComposing()
            } else {
                composing.setLength(composing.length - lastGrapheme(composing))
                if (composing.isEmpty()) {
                    ic.setComposingText("", 1)
                    resetComposing()
                } else ic.setComposingText(composing.toString(), 1)
            }
            afterEdit()
            return
        }
        phantomSpace = false
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(24, 0)
            if (before.isNullOrEmpty()) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else {
                ic.deleteSurroundingText(lastGrapheme(before), 0)
                if (!repeat && suggestionsOn) recomposeAfterDelete = true
            }
        }
        afterEdit()
    }

    private fun lastGrapheme(s: CharSequence): Int {
        if (s.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        val str = s.toString()
        it.setText(str)
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) 1 else end - start
    }

    override fun onDeleteWords(words: Int, done: Boolean) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            resetComposing()
        }
        if (deleteAnchor < 0) {
            // Remember where the swipe started and the text before it; the selection grows from there.
            deleteAnchor = cursorPosition(ic) ?: return
            deleteBefore = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        }
        val anchor = deleteAnchor
        if (!done) {
            val n = TextRules.charsForWords(deleteBefore, words)
            ic.setSelection(anchor - n, anchor)
        } else {
            if (words > 0) {
                ic.commitText("", 1)
                lastCorrection = null
            } else {
                ic.setSelection(anchor, anchor)
            }
            deleteAnchor = -1
            afterEdit()
        }
    }

    private fun cursorPosition(ic: InputConnection): Int? {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et != null) return et.startOffset + et.selectionEnd
        return selEnd.takeIf { it >= 0 }
    }

    override fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && !shiftAuto && now - lastShiftTap < 350 -> 2
            shift == 0 -> 1
            else -> 0
        }
        shiftAuto = false
        lastShiftTap = now
        keyboard?.shift = shift
    }

    override fun onShiftLock() {
        shift = 2
        shiftAuto = false
        keyboard?.shift = 2
    }

    override fun onSheet(sheet: Sheet) {
        this.sheet = sheet
        showSheet(animate = true)
        if (sheet == Sheet.Letters) updateShift()
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray) {
        val keys = keyMap ?: return
        if (!suggestionsOn) return
        val ic = currentInputConnection ?: return
        val caps = when (shift) {
            2 -> Caps.All
            1 -> Caps.First
            else -> Caps.Lower
        }
        val context = contextWords(ic)
        val prev = context.lastOrNull()
        engine.ask(EngineHost.SWIPE, { s ->
            val decoder = engine.swipe ?: return@ask emptyList<String>()
            decoder.decode(
                xs, ys, keys,
                extra = s.learned.words(s.main),
                context = { w -> if (prev != null) s.context(prev, w) else 0f },
                boost = { w -> s.learned.boost(w) },
            ).map { Suggester.recase(it.word, caps) }
        }) { words -> if (words.isNotEmpty()) insertSwipe(words) }
    }

    private fun insertSwipe(words: List<String>) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitComposing(ic, correct = !composingFromSwipe, separator = " ")
        } else {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'") ic.commitText(" ", 1)
        }
        val word = words.first()
        composing.append(word)
        forgetTouches(word.length)
        composingFromSwipe = true
        swipeWords = words
        phantomSpace = true
        ic.setComposingText(word, 1)
        ic.endBatchEdit()
        if (shift == 1) {
            shift = 0
            shiftAuto = false
            keyboard?.shift = 0
        }
        afterEdit()
    }

    override fun onCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            ic.finishComposingText()
            resetComposing()
        }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onSpaceLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val word = stripWords.getOrNull(index) ?: return
        lastCorrection = null
        if (composing.isNotEmpty()) {
            if (composingFromSwipe) {
                // Swap the swiped word for another reading; the space is still owed.
                composing.setLength(0)
                composing.append(word)
                swipeWords = listOf(word) + swipeWords.filter { it != word }
                ic.setComposingText(word, 1)
                afterEdit()
                return
            }
            val typed = composing.toString()
            if (index == current.typedIndex && word == typed && learningOn) engine.later { engine.learned.insist(typed) }
            val context = contextWords(ic)
            ic.beginBatchEdit()
            ic.setComposingText(word, 1)
            ic.finishComposingText()
            ic.commitText(" ", 1)
            ic.endBatchEdit()
            learn(word, context.lastOrNull())
            resetComposing()
        } else {
            val context = contextWords(ic)
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            ic.beginBatchEdit()
            if (before.isNotEmpty() && !before[0].isWhitespace()) ic.commitText(" ", 1)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
            learn(word, context.lastOrNull())
        }
        autoSpaced = true
        phantomSpace = false
        if (shift == 1) {
            shift = 0
            shiftAuto = false
            keyboard?.shift = 0
        }
        afterEdit()
    }

    override fun onSuggestionLongPress(index: Int) {
        val word = stripWords.getOrNull(index) ?: return
        if (!learningOn) return
        engine.later { engine.learned.forget(word) }
        Toast.makeText(this, "“$word” won't be suggested again", Toast.LENGTH_SHORT).show()
        main.postDelayed({ refreshStrip() }, 50)
    }

    override fun onHub() {
        if (panel != null) {
            closePanel(animate = true)
            return
        }
        val showingTools = stripIsTools()
        stripChoice = if (showingTools) StripChoice.Words else StripChoice.Tools
        refreshStrip()
    }

    override fun onTool(tool: Tool) {
        when (tool) {
            Tool.Clipboard, Tool.Emoji -> if (panel == tool) closePanel(animate = true) else openPanel(tool)
            Tool.Scan -> {
                closePanel(animate = false)
                startSafely(Intent(this, ScanTextActivity::class.java))
            }
            Tool.Settings -> openSettings()
            Tool.Hide -> if (panel != null) closePanel(animate = true) else requestHideSelf(0)
        }
    }

    override fun onPaste() {
        val clip = clips.lastAdded ?: return
        chipUntil = 0
        clips.forgetChip()
        pasteClip(clip)
    }

    override fun onEmoji() {
        openPanel(Tool.Emoji)
    }

    // ================================================================ Composing

    private fun commitComposing(ic: InputConnection, correct: Boolean, separator: String) {
        val typed = composing.toString()
        var word = typed
        if (correct && autocorrectOn && !noCorrect) {
            val s = if (current.typed == typed) current else suggestNow(typed)
            s?.correction?.let { word = it }
        }
        val context = contextWords(ic)
        ic.beginBatchEdit()
        if (word != typed) ic.setComposingText(word, 1)
        ic.finishComposingText()
        if (separator.isNotEmpty()) ic.commitText(separator, 1)
        ic.endBatchEdit()
        lastCorrection = if (word != typed) Correction(typed, word, separator) else null
        learn(word, context.lastOrNull())
        resetComposing()
        phantomSpace = false
    }

    private fun suggestNow(typed: String): Suggestions? {
        val t = Typed(typed, touchX.copyOf(typed.length), touchY.copyOf(typed.length))
        val context = currentInputConnection?.let { contextWords(it) } ?: emptyList()
        return engine.now { it.suggest(t, context, Suggester.capsOf(typed), autocorrectOn) }
    }

    private fun learn(word: String, previous: String?) {
        if (!learningOn || word.isEmpty() || word.any { it.isDigit() }) return
        val sentenceStart = previous == null
        // A capital only because it starts a sentence isn't part of the word.
        val form = if (sentenceStart && word.length > 1 && word[0].isUpperCase() && word.substring(1).all { !it.isUpperCase() }) {
            val lower = word.lowercase()
            val known = engine.suggester?.main?.bestForm(word)
            known ?: lower
        } else word
        engine.later { engine.learned.use(form, previous) }
    }

    /** The letters of the word in progress weren't tapped here, so there are no touches to go on. */
    private fun forgetTouches(length: Int) {
        if (touchX.size < length) {
            touchX = FloatArray(length * 2)
            touchY = FloatArray(length * 2)
        }
        touchX.fill(Float.NaN)
        touchY.fill(Float.NaN)
    }

    private fun resetComposing() {
        composing.setLength(0)
        composingFromSwipe = false
        swipeWords = emptyList()
        noCorrect = false
        current = Suggestions.None
    }

    /** Words before the one in progress, in this sentence. */
    private fun contextWords(ic: InputConnection): List<String> {
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: return emptyList()
        val trimmed = if (composing.isNotEmpty() && before.endsWith(composing)) before.dropLast(composing.length) else before
        return TextRules.contextWords(trimmed)
    }

    // ================================================================ After every edit

    private fun afterEdit() {
        updateShift()
        updateEnter()
        refreshStrip()
        if (autofillShowing && composing.isNotEmpty()) clearAutofill()
    }

    private fun updateShift() {
        if (shift == 2 || sheet != Sheet.Letters) return
        val ic = currentInputConnection ?: return
        if (!capsField || composing.isNotEmpty()) {
            if (shiftAuto) { shift = 0; shiftAuto = false; keyboard?.shift = 0 }
            return
        }
        val caps = ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
        if (caps && shift == 0) {
            shift = 1
            shiftAuto = true
        } else if (!caps && shiftAuto) {
            shift = 0
            shiftAuto = false
        }
        keyboard?.shift = shift
    }

    private fun updateEnter() {
        val ic = currentInputConnection
        val hasText = composing.isNotEmpty() ||
            !ic?.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
            !ic?.getTextAfterCursor(1, 0).isNullOrEmpty()
        keyboard?.setEnter(enterLabel, enterLabel.isNotEmpty() && hasText)
    }

    private fun stripIsTools(): Boolean = panel != null || !suggestionsOn || when (stripChoice) {
        StripChoice.Tools -> true
        StripChoice.Words -> false
        StripChoice.Auto -> composing.isEmpty() && fieldEmpty() && !chipActive()
    }

    private fun fieldEmpty(): Boolean {
        val ic = currentInputConnection ?: return true
        return ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    private fun chipActive(): Boolean =
        prefs.clipboardChip && clips.lastAdded != null && SystemClock.uptimeMillis() < chipUntil

    private fun refreshStrip() {
        val kv = keyboard ?: return
        if (composing.isNotEmpty()) stripChoice = StripChoice.Auto
        if (autofillShowing) {
            kv.setStrip(Strip.Blank)
            return
        }
        if (stripIsTools()) {
            engine.cancel(EngineHost.SUGGEST)
            kv.setStrip(Strip.Tools)
            return
        }
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            if (composingFromSwipe) {
                val w = swipeWords
                val words = listOfNotNull(w.getOrNull(1), w.firstOrNull(), w.getOrNull(2))
                stripWords = words
                kv.setStrip(Strip.Words(words, words.indexOf(w.first()), -1))
                return
            }
            val text = composing.toString()
            val typed = Typed(text, touchX.copyOf(text.length), touchY.copyOf(text.length))
            val context = contextWords(ic)
            val caps = Suggester.capsOf(text)
            val correct = autocorrectOn && !noCorrect
            engine.ask(EngineHost.SUGGEST, { s -> s.suggest(typed, context, caps, correct) }) { r ->
                if (r.typed != composing.toString()) return@ask
                current = r
                stripWords = r.words
                keyboard?.setStrip(Strip.Words(r.words, r.commitIndex, r.typedIndex))
            }
            return
        }
        if (chipActive()) {
            val clip = clips.lastAdded!!
            kv.setStrip(Strip.Paste(clip.text.orEmpty(), clip.image != null))
            return
        }
        val before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val context = TextRules.contextWords(before)
        val start = TextRules.atSentenceStart(before)
        if (before.isNotEmpty() && !before.last().isWhitespace() && TextRules.isWordChar(before.last())) {
            // The cursor sits against a word: nothing sensible to predict.
            stripWords = emptyList()
            kv.setStrip(Strip.Words(emptyList(), -1, -1))
            return
        }
        engine.ask(EngineHost.SUGGEST, { s -> s.predict(context, start) }) { words ->
            if (composing.isNotEmpty()) return@ask
            stripWords = words
            keyboard?.setStrip(if (words.isEmpty()) Strip.Tools else Strip.Words(words, -1, -1))
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        val ic = currentInputConnection ?: return
        if (deleteAnchor >= 0) return
        if (composing.isNotEmpty() && (candidatesStart == -1 || newSelStart != newSelEnd || newSelEnd != candidatesEnd)) {
            // You moved the cursor away from the word in progress.
            ic.finishComposingText()
            resetComposing()
            phantomSpace = false
            autoSpaced = false
            lastCorrection = null
            afterEdit()
            return
        }
        if (recomposeAfterDelete && composing.isEmpty() && newSelStart == newSelEnd) {
            recomposeAfterDelete = false
            val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
            val word = TextRules.wordBefore(before)
            if (word.isNotEmpty() && word.all { it.isLetter() || it == '\'' } && (after.isEmpty() || !TextRules.isWordChar(after[0]))) {
                // Back inside a word: pick it up again so its suggestions return.
                ic.setComposingRegion(newSelStart - word.length, newSelStart)
                composing.append(word)
                forgetTouches(word.length)
            }
            afterEdit()
            return
        }
        if (composing.isEmpty() && oldSelStart != newSelStart && candidatesStart == -1) {
            updateShift()
            updateEnter()
        }
    }

    // ================================================================ Panels

    private fun openPanel(tool: Tool) {
        val ic = currentInputConnection
        if (ic != null && composing.isNotEmpty()) commitComposing(ic, correct = false, separator = "")
        panel = tool
        clearAutofill()
        if (tool == Tool.Emoji) {
            panelState.frequent = emojiUsage.frequent()
            if (panelState.emoji == null) background.execute {
                val data = EmojiData.load(this)
                main.post { panelState.emoji = data }
            }
        }
        if (tool == Tool.Clipboard) panelState.clips = clips.clips
        keyboard?.setPanel(tool)
        keyboard?.setStrip(Strip.Tools)
        positionOverlays()
        main.postDelayed({
            if (panel == tool) {
                panelState.panel = tool
                panelView?.visibility = View.VISIBLE
            }
        }, 90)
    }

    private fun closePanel(animate: Boolean) {
        if (panel == null && panelState.panel == null) return
        panel = null
        panelState.panel = null
        panelView?.visibility = View.GONE
        keyboard?.setPanel(null)
        if (!animate) keyboard?.popIn(quick = true)
        stripChoice = StripChoice.Auto
        refreshStrip()
    }

    override fun typeEmoji(emoji: String, base: String) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(ic, correct = false, separator = "")
        ic.commitText(emoji, 1)
        emojiUsage.record(emoji, base)
        autoSpaced = false
        phantomSpace = false
        updateEnter()
    }

    override fun variantOf(base: String): String = emojiUsage.variant(base)

    override fun chooseVariant(base: String, emoji: String) {
        emojiUsage.setVariant(base, emoji)
        panelState.variantsVersion++
    }

    override fun pasteClip(clip: Clip) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(ic, correct = false, separator = "")
        if (clip.image != null) {
            val info = currentInputEditorInfo
            val mime = clip.mime ?: "image/png"
            val accepted = info != null && EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
            val uri = clips.uriFor(clip)
            if (!accepted || uri == null || info == null) {
                Toast.makeText(this, "This app can't take images here", Toast.LENGTH_SHORT).show()
                return
            }
            val content = InputContentInfoCompat(uri, ClipDescription("Copied image", arrayOf(mime)), null)
            InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } else {
            ic.commitText(clip.text.orEmpty(), 1)
        }
        autoSpaced = false
        afterEdit()
    }

    override fun pinClip(clip: Clip, pinned: Boolean) = clips.pin(clip.id, pinned)
    override fun removeClip(clip: Clip) = clips.remove(clip.id)
    override fun clearClips() = clips.clearUnpinned()
    override fun clipFile(clip: Clip): File? = clips.fileFor(clip)
    override fun backToKeys() = closePanel(animate = true)
    override fun delete() = onDelete(false)

    override fun openSettings() {
        closePanel(animate = false)
        startSafely(Intent(this, SettingsActivity::class.java))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    // ================================================================ Clipboard

    private fun onClipboardChanged() {
        if (!prefs.clipboardHistory) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clips.capture(cm)
        if (prefs.clipboardChip) {
            chipUntil = SystemClock.uptimeMillis() + 60_000
            main.postDelayed({ if (composing.isEmpty() && panel == null) refreshStrip() }, 300)
        }
    }

    // ================================================================ Autofill

    @RequiresApi(30)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? =
        Autofill.request(this, panelState.palette)

    @RequiresApi(30)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val row = autofillRow ?: return false
        val list = response.inlineSuggestions
        row.removeAllViews()
        if (list.isEmpty()) {
            clearAutofill()
            return true
        }
        val height = (keyboard?.stripArea?.height() ?: (44 * resources.displayMetrics.density)).toInt()
        list.forEach { s ->
            s.inflate(this, Size(ViewGroup.LayoutParams.WRAP_CONTENT, height), mainExecutor) { view ->
                if (view != null) {
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height)
                    lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
                    row.addView(view, lp)
                }
            }
        }
        autofillShowing = true
        positionOverlays()
        autofillBar?.visibility = View.VISIBLE
        refreshStrip()
        return true
    }

    private fun clearAutofill() {
        if (!autofillShowing && autofillBar?.visibility != View.VISIBLE) return
        autofillShowing = false
        autofillRow?.removeAllViews()
        autofillBar?.visibility = View.GONE
        refreshStrip()
    }

    // ================================================================ Sound

    private fun playSound(key: Key) {
        if (!prefs.sound) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val fx = when (key.type) {
            KeyType.Delete -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.Enter -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        am.playSoundEffect(fx, -1f)
    }
}
