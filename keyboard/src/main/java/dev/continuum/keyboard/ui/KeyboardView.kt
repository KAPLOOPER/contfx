package dev.continuum.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import dev.continuum.keyboard.R
import dev.continuum.keyboard.layout.Key
import dev.continuum.keyboard.layout.KeyType
import dev.continuum.keyboard.layout.Layout
import dev.continuum.keyboard.layout.Layouts
import dev.continuum.keyboard.layout.PlacedKey
import dev.continuum.keyboard.layout.Row
import dev.continuum.keyboard.layout.Sheet
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The strip above the keys. */
sealed class Strip {
    /** The keyboard's tools: clipboard, emoji, scan text, settings. */
    object Tools : Strip()

    /** Suggestions or predictions. */
    data class Words(val words: List<String>, val commitIndex: Int, val typedIndex: Int) : Strip()

    /** Something just copied, ready to paste. */
    data class Paste(val preview: String, val image: Boolean) : Strip()

    /** Left clear for autofill chips. */
    object Blank : Strip()
}

enum class Tool { Clipboard, Emoji, Edit, Scan, Settings, Hide }

/**
 * Continuum Keyboard's keys, drawn as one sheet of ink. Pressed letters rise out of their
 * key as a droplet and fall back into it; long presses grow a bar of accents out of the
 * key; swipes leave an ink trail that's drawn back in; delete stretches into a tongue as
 * you drag it across words.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String, x: Float, y: Float)
        fun onDelete(repeat: Boolean)
        /** Delete-swipe has reached [words] words; [done] when the finger lifts. */
        fun onDeleteWords(words: Int, done: Boolean)
        fun onSpace()
        fun onEnter()
        fun onShift()
        fun onShiftLock()
        fun onSheet(sheet: Sheet)
        fun onSwipe(xs: FloatArray, ys: FloatArray)
        /** The cursor slid [steps] characters (negative is left). */
        fun onCursor(steps: Int)
        fun onSpaceLongPress()
        fun onSuggestion(index: Int)
        fun onSuggestionLongPress(index: Int)
        fun onTool(tool: Tool)
        /** The round button at the start of the strip: tools, or back to words. */
        fun onHub()
        fun onPaste()
        fun onEmoji()
        fun onLayout(layout: Layout)
        fun onGeometry()
    }

    // ---- What the keyboard shows, set by the input method. ----

    var palette: KeyPalette = KeyPalette.Ink
        set(value) { field = value; invalidate() }

    var heightScale = 1f
        set(value) { if (field != value) { field = value; requestLayout() } }
    var numberRow = false
    var balloonsOn = true
    var swipeOn = true
    var trailOn = true
    var deleteSwipeOn = true
    var spaceCursorOn = true
    var numberHints = false
    var vibrate = true

    /** 0 off, 1 next letter, 2 locked. */
    var shift = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    private var enterLabel = ""
    private var enterBright = false

    private var strip: Strip = Strip.Tools
    private var activeTool: Tool? = null

    /** Height of the navigation bar the keyboard sits above. */
    var bottomInset = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private var sheet = Sheet.Letters
    private var rows: List<Row> = emptyList()
    private var lettersRows: List<Row> = emptyList()
    var layout: Layout? = null
        private set

    // ---- Geometry, in pixels. ----

    private val density = resources.displayMetrics.density
    private var u = 1f
    private var v = 1f
    var massTop = 0f
        private set
    private var stripTop = 0f
    private var stripH = 0f
    var keysTop = 0f
        private set
    var keysBottom = 0f
        private set
    private var keysHeight = 0f

    /** Where the strip's suggestion area is, for autofill chips laid over it. */
    val stripArea = RectF()
    private val hubRect = RectF()
    private val slotRects = Array(3) { RectF() }
    private val toolRects = Tool.values().associateWith { RectF() }
    private var toolGap = 10f
    private val pasteRect = RectF()

    // ---- Paint. ----

    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.libron_regular) ?: Typeface.DEFAULT
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.libron_bold) ?: Typeface.DEFAULT_BOLD
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = regular }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val massPath = Path()

    private val gooNode: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("ink") else null
    private var gooBlur = -1f

    // ---- Motion. ----

    private var press = Array(0) { Spring() }
    private var appear = Array(0) { Spring(1f) }
    private val stripPress = HashMap<Int, Spring>()
    private val stripAppear = Spring(1f)
    private val balloons = ArrayList<Balloon>()
    private var popup: Popup? = null
    private val tongueLeft = Spring()
    private var tongueKey: RectF? = null
    private var tongueWords = 0
    private var tongueHeld = false
    private var spaceSliding = false
    private var lastFrame = 0L
    private var keysHidden = false

    private class Balloon(val keyRect: RectF, val label: String) {
        val rise = Spring(0f)
        var held = true
    }

    private class Popup(
        val keyRect: RectF,
        val items: List<String>,
        val bar: RectF,
        val itemWidth: Float,
        var selected: Int,
    ) {
        val open = Spring(0f)
    }

    // ---- Touch. ----

    private enum class Mode { Tap, Gesture, DeleteSwipe, SpaceSlide, Popup, SheetSlide, Strip, Done }

    private inner class Touch(val id: Int, var key: PlacedKey?, val x0: Float, val y0: Float) {
        var x = x0
        var y = y0
        var mode = Mode.Tap
        val downAt = SystemClock.uptimeMillis()
        var keyIndex = -1
        var stripTarget = -1
        var spaceSteps = 0
        var deleteWords = 0
        var returnSheet: Sheet? = null
        var xs = FloatArray(64)
        var ys = FloatArray(64)
        var n = 0
        var balloon: Balloon? = null
        var repeated = false
        var longPress: Runnable? = null
        var repeat: Runnable? = null

        fun addPoint(px: Float, py: Float) {
            if (n == xs.size) {
                xs = xs.copyOf(n * 2)
                ys = ys.copyOf(n * 2)
            }
            xs[n] = px; ys[n] = py; n++
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()

    /** The trail of the current or last swipe, in screen order. */
    private var trailXs = FloatArray(0)
    private var trailYs = FloatArray(0)
    private var trailStart = 0
    private var trailEnd = 0
    private var trailLive = false

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_name)
    }

    // ================================================================ Setup

    fun setSheet(sheet: Sheet, rows: List<Row>, letters: List<Row>, animate: Boolean) {
        val changed = sheet != this.sheet || this.rows.size != rows.size
        val taller = letters.size != lettersRows.size
        this.sheet = sheet
        this.rows = rows
        this.lettersRows = letters
        // The keyboard's height follows the letters (with or without a number row).
        if (taller) requestLayout()
        relayout()
        if (animate && changed) popIn(quick = true)
        invalidate()
    }

    fun setEnter(label: String, bright: Boolean) {
        if (label != enterLabel || bright != enterBright) {
            enterLabel = label
            enterBright = bright
            invalidate()
        }
    }

    fun setStrip(s: Strip) {
        if (s == strip) return
        val kindChanged = s::class != strip::class
        strip = s
        if (kindChanged || s is Strip.Words) {
            stripAppear.snap(0.4f)
            stripAppear.to(1f, SpringSpec.Flow)
        }
        frame()
    }

    /** Opens or closes a panel: the keys pop out to make room, or back in. */
    fun setPanel(tool: Tool?) {
        activeTool = tool
        val hide = tool != null
        if (hide != keysHidden) {
            keysHidden = hide
            val l = layout ?: return
            l.keys.forEachIndexed { i, k ->
                val d = (k.row * 30 + (k.cx / max(1f, width.toFloat())) * 80).toFloat() / 1000f
                if (hide) appear[i].to(0f, SpringSpec.Snap, delay = d * 0.5f) else appear[i].to(1f, SpringSpec.Flow, delay = d)
            }
        }
        frame()
    }

    /** Keys pop in row by row, like the keyboard is filling with ink. */
    fun popIn(quick: Boolean = false) {
        val l = layout ?: return
        val w = max(1f, width.toFloat())
        l.keys.forEachIndexed { i, k ->
            val ms = if (quick) k.row * 22 + (k.cx / w) * 60 else 90 + k.row * 45 + (k.cx / w) * 120
            appear[i].snap(0f)
            appear[i].to(if (keysHidden) 0f else 1f, SpringSpec.Flow, delay = ms / 1000f)
        }
        frame()
    }

    private fun relayout() {
        if (width == 0 || rows.isEmpty()) return
        val w = width.toFloat()
        val pitch = 56f * v * heightScale
        val keyH = 46f * v * heightScale
        val full = Layouts.place(Sheet.Letters, lettersRows.ifEmpty { rows }, w, pitch, keyH, keysTop)
        keysHeight = full.height
        var l = if (sheet == Sheet.Letters && rows === lettersRows) full else Layouts.place(sheet, rows, w, pitch, keyH, keysTop)
        if (abs(l.height - keysHeight) > 1f) {
            // Sheets with fewer rows stretch to the same height, so the keyboard never jumps.
            val gapV = pitch - keyH
            val n = rows.size
            val p2 = (keysHeight + gapV) / n
            l = Layouts.place(sheet, rows, w, p2, p2 - gapV, keysTop)
        }
        layout = l
        press = Array(l.keys.size) { Spring() }
        appear = Array(l.keys.size) { Spring(if (keysHidden) 0f else 1f) }
        listener.onLayout(l)
    }

    private fun computeGeometry(w: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        u = w / Key.WIDTH
        v = min(u, 1.12f * density) * (if (landscape) 0.78f else 1f)
        massTop = 16f * v
        stripTop = massTop + 8f * v
        stripH = 44f * v
        keysTop = stripTop + stripH + 8f * v

        val side = 8f * u
        val d = stripH
        hubRect.set(side, stripTop, side + d, stripTop + d)
        stripArea.set(hubRect.right + 6f * u, stripTop, w - side, stripTop + d)
        val gap = 4f * u
        val slotW = (stripArea.width() - 2 * gap) / 3f
        for (i in 0 until 3) {
            val l = stripArea.left + i * (slotW + gap)
            slotRects[i].set(l, stripTop, l + slotW, stripTop + d)
        }
        pasteRect.set(stripArea)
        // Tools follow the hub; hide (or close) is at the far end. On a narrow screen the
        // gaps tighten first, then the tools shrink a touch, so they never crowd the hide button.
        val hideLeft = w - side - d
        val room = hideLeft - hubRect.right
        val n = STRIP_TOOLS.size
        var gap = 10f * u
        var td = d
        if (n * td + (n + 1) * gap > room) {
            gap = max(6f * u, (room - n * td) / (n + 1))
            if (n * td + (n + 1) * gap > room) td = max(d * 0.7f, (room - (n + 1) * gap) / n)
        }
        toolGap = gap
        var x = hubRect.right + gap
        val inset = (d - td) / 2f
        for (t in STRIP_TOOLS) {
            toolRects[t]!!.set(x, stripTop + inset, x + td, stripTop + inset + td)
            x += td + gap
        }
        toolRects[Tool.Hide]!!.set(hideLeft, stripTop, w - side, stripTop + d)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        computeGeometry(w)
        val pitch = 56f * v * heightScale
        val keyH = 46f * v * heightScale
        val rowsForHeight = lettersRows.ifEmpty { rows }
        val h = if (rowsForHeight.isEmpty()) 0f else Layouts.place(Sheet.Letters, rowsForHeight, w.toFloat(), pitch, keyH, 0f).height
        keysBottom = keysTop + h
        val total = keysBottom + 10f * v + bottomInset
        setMeasuredDimension(w, total.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGeometry(w)
        relayout()
        listener.onGeometry()
    }

    // ================================================================ Drawing

    private fun frame() {
        postInvalidateOnAnimation()
    }

    private fun stepAll(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.034f)
        lastFrame = now
        var moving = false
        for (s in press) if (s.step(dt)) moving = true
        for (s in appear) if (s.step(dt)) moving = true
        for (s in stripPress.values) if (s.step(dt)) moving = true
        if (stripAppear.step(dt)) moving = true
        val it = balloons.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.rise.step(dt)) moving = true
            if (!b.held && b.rise.value <= 0.01f && b.rise.target == 0f) it.remove()
        }
        popup?.let { if (it.open.step(dt)) moving = true }
        if (tongueLeft.step(dt)) moving = true
        if (!tongueHeld && tongueKey != null && !tongueLeft.moving) tongueKey = null
        if (!trailLive && trailEnd > trailStart) {
            // The trail is drawn back in, tail first.
            val left = trailEnd - trailStart
            trailStart += max(2, (left * 0.22f).toInt())
            if (trailStart >= trailEnd) { trailStart = 0; trailEnd = 0 }
            moving = true
        }
        if (trailLive) moving = true
        return moving
    }

    override fun onDraw(canvas: Canvas) {
        val moving = stepAll()
        val w = width.toFloat()
        val h = height.toFloat()
        val p = palette

        // The ink.
        fill.color = p.mass
        val r = 28f * v
        massPath.reset()
        massPath.addRoundRect(0f, massTop, w, h + r, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        canvas.drawPath(massPath, fill)

        drawStrip(canvas)
        drawKeys(canvas)
        drawGoo(canvas)
        drawOverGoo(canvas)
        drawTrail(canvas)

        if (moving) frame() else lastFrame = 0L
    }

    private fun drawKeys(canvas: Canvas) {
        val l = layout ?: return
        val p = palette
        for ((i, k) in l.keys.withIndex()) {
            val a = appear.getOrNull(i)?.value ?: 1f
            if (a <= 0.01f) continue
            val pr = press.getOrNull(i)?.value ?: 0f
            val key = k.key
            var sx = 0.5f + 0.5f * a
            var sy = sx
            if (key.isFunction || isWide(k)) {
                if (k.w > 1.6f * Key.LETTER * u) { sx *= 1f + 0.05f * pr; sy *= 1f - 0.14f * pr } else { sx *= 1f - 0.14f * pr; sy *= 1f - 0.14f * pr }
            }
            val bright = (key.type == KeyType.Shift && shift > 0) || (key.type == KeyType.Enter && enterBright)
            val sliding = key.type == KeyType.Space && spaceSliding
            val cap = when {
                bright -> p.bright
                sliding -> p.preview
                key.type == KeyType.Text || key.type == KeyType.Space -> p.key
                else -> p.fn
            }
            val alpha = (a.coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            canvas.scale(sx, sy, k.cx, k.cy)
            fill.color = cap
            fill.alpha = alpha
            val radius = min(if (k.w < 40f * u) 16f * u else 23f * u, k.h / 2f)
            rect.set(k.x, k.y, k.right, k.bottom)
            canvas.drawRoundRect(rect, radius, radius, fill)
            drawLabel(canvas, k, if (bright) p.onBright else p.label, alpha)
            canvas.restore()
        }
    }

    private fun isWide(k: PlacedKey) = k.key.type == KeyType.Text && k.w > 1.6f * Key.LETTER * u

    private fun drawLabel(canvas: Canvas, k: PlacedKey, color: Int, alpha: Int) {
        val key = k.key
        val p = palette
        icon.color = color
        icon.alpha = alpha
        when (key.type) {
            // Locked caps keeps a bar under the arrow, so it never reads as a one-letter shift.
            KeyType.Shift -> Icons.draw(canvas, if (shift == 2) Icons.CAPS_LOCK else Icons.SHIFT, k.cx, k.cy, 22f * v, icon, fill = shift > 0)
            KeyType.Delete -> if (tongueKey == null) Icons.draw(canvas, Icons.DELETE, k.cx, k.cy, 24f * v, icon)
            KeyType.Enter -> if (enterLabel.isEmpty()) Icons.draw(canvas, Icons.RETURN, k.cx, k.cy, 22f * v, icon)
            else drawText(canvas, enterLabel, k.cx, k.cy, 17f * v, if (enterBright) color else p.sub, alpha, k.w - 8f * u)
            KeyType.Space -> if (spaceSliding) {
                icon.color = p.label
                Icons.draw(canvas, Icons.LEFT, k.cx - 18f * v, k.cy, 18f * v, icon)
                Icons.draw(canvas, Icons.RIGHT, k.cx + 18f * v, k.cy, 18f * v, icon)
            } else drawText(canvas, "space", k.cx, k.cy, 16f * v, p.sub, alpha, k.w)
            KeyType.Sheet -> drawText(canvas, key.label, k.cx, k.cy, 16f * v, color, alpha, k.w)
            KeyType.Emoji -> Icons.draw(canvas, Icons.EMOJI, k.cx, k.cy, 22f * v, icon)
            KeyType.Text -> {
                val label = if (key.isLetter && shift > 0) key.label.uppercase() else key.label
                val size = when {
                    key.isLetter -> 23f * v
                    sheet == Sheet.Numbers || sheet == Sheet.Phone -> 26f * v
                    else -> 22f * v
                }
                drawText(canvas, label, k.cx, k.cy, size, color, alpha, k.w)
                // Small hints: the digit on top-row letters, the emoji on the comma.
                if (numberHints && key.isLetter && key.popup.firstOrNull()?.firstOrNull()?.isDigit() == true) {
                    drawText(canvas, key.popup.first(), k.right - 7f * u, k.y + 9f * v, 10f * v, p.sub, alpha, k.w)
                }
                if (key.popup.firstOrNull() == Layouts.EMOJI) {
                    icon.color = p.sub
                    Icons.draw(canvas, Icons.EMOJI, k.right - 8f * u, k.y + 9f * v, 9f * v, icon)
                }
            }
        }
    }

    private fun drawText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, alpha: Int, maxW: Float, face: Typeface = regular) {
        text.typeface = face
        text.textSize = size
        var width = text.measureText(s)
        var shown = s
        if (width > maxW && maxW > 0f) {
            text.textSize = max(size * 0.72f, size * maxW / width)
            width = text.measureText(s)
            if (width > maxW) {
                var cut = s.length
                while (cut > 1 && text.measureText(s.substring(0, cut) + "…") > maxW) cut--
                shown = s.substring(0, cut) + "…"
            }
        }
        text.color = color
        text.alpha = alpha
        val fm = text.fontMetrics
        canvas.drawText(shown, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawStrip(canvas: Canvas) {
        val p = palette
        val a = stripAppear.value.coerceIn(0f, 1f)
        val tools = strip is Strip.Tools || activeTool != null

        // The hub: tools, or back to words. Lit while the tools are showing.
        run {
            val pr = stripPress[STRIP_HUB]?.value ?: 0f
            val s = 1f - 0.14f * pr
            val lit = tools && activeTool == null
            canvas.save()
            canvas.scale(s, s, hubRect.centerX(), hubRect.centerY())
            fill.color = if (lit) p.bright else p.fn
            fill.alpha = 255
            canvas.drawCircle(hubRect.centerX(), hubRect.centerY(), hubRect.height() / 2f, fill)
            icon.color = if (lit) p.onBright else p.label
            icon.alpha = 255
            Icons.draw(canvas, Icons.HUB, hubRect.centerX(), hubRect.centerY(), 18f * v, icon)
            canvas.restore()
        }

        if (tools) {
            for ((t, r) in toolRects) {
                val active = activeTool == t
                val pr = stripPress[STRIP_TOOL + t.ordinal]?.value ?: 0f
                val s = 1f - 0.14f * pr
                canvas.save()
                canvas.scale(s * (0.85f + 0.15f * a), s * (0.85f + 0.15f * a), r.centerX(), r.centerY())
                fill.color = if (active) p.bright else p.fn
                fill.alpha = (255 * a).toInt()
                canvas.drawCircle(r.centerX(), r.centerY(), r.height() / 2f, fill)
                icon.color = if (active) p.onBright else p.label
                icon.alpha = (255 * a).toInt()
                val d = when (t) {
                    Tool.Clipboard -> Icons.CLIPBOARD
                    Tool.Emoji -> Icons.EMOJI
                    Tool.Edit -> Icons.EDIT
                    Tool.Scan -> Icons.SCAN
                    Tool.Settings -> Icons.SETTINGS
                    Tool.Hide -> if (activeTool != null) Icons.CLOSE else Icons.HIDE
                }
                Icons.draw(canvas, d, r.centerX(), r.centerY(), 22f * v * min(1f, r.height() / stripH), icon)
                canvas.restore()
            }
            return
        }

        when (val s = strip) {
            is Strip.Words -> {
                for (i in 0 until min(3, s.words.size)) {
                    val r = slotRects[i]
                    val pr = stripPress[i]?.value ?: 0f
                    if (pr > 0.01f) {
                        fill.color = p.fn
                        fill.alpha = (255 * pr.coerceIn(0f, 1f)).toInt()
                        val sc = 0.4f + 0.6f * pr
                        canvas.save()
                        canvas.scale(sc, sc, r.centerX(), r.centerY())
                        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                        canvas.restore()
                    }
                    val word = if (i == s.typedIndex && s.typedIndex != s.commitIndex) "“${s.words[i]}”" else s.words[i]
                    val stagger = (a - i * 0.08f).coerceIn(0f, 1f)
                    val alpha = (255 * stagger).toInt()
                    canvas.save()
                    val sc = 0.85f + 0.15f * stagger
                    canvas.scale(sc, sc, r.centerX(), r.centerY())
                    drawText(
                        canvas, word, r.centerX(), r.centerY(), 17f * v,
                        if (i == s.typedIndex && s.typedIndex != s.commitIndex) p.sub else p.label,
                        alpha, r.width() - 12f * u, if (i == s.commitIndex && s.commitIndex != s.typedIndex) bold else regular,
                    )
                    canvas.restore()
                }
            }
            is Strip.Paste -> {
                val pr = stripPress[STRIP_PASTE]?.value ?: 0f
                val sc = (1f - 0.05f * pr) * (0.9f + 0.1f * a)
                canvas.save()
                canvas.scale(sc, sc, pasteRect.centerX(), pasteRect.centerY())
                fill.color = p.fn
                fill.alpha = (255 * a).toInt()
                canvas.drawRoundRect(pasteRect, pasteRect.height() / 2f, pasteRect.height() / 2f, fill)
                icon.color = p.label
                icon.alpha = (255 * a).toInt()
                Icons.draw(canvas, Icons.PASTE, pasteRect.left + 26f * u, pasteRect.centerY(), 20f * v, icon)
                text.textAlign = Paint.Align.LEFT
                text.typeface = regular
                text.textSize = 16f * v
                text.color = p.label
                text.alpha = (255 * a).toInt()
                val maxW = pasteRect.width() - 60f * u
                var shown = if (s.image) "Paste image" else s.preview.replace('\n', ' ').trim()
                if (text.measureText(shown) > maxW) {
                    var cut = shown.length
                    while (cut > 1 && text.measureText(shown.substring(0, cut) + "…") > maxW) cut--
                    shown = shown.substring(0, cut) + "…"
                }
                val fm = text.fontMetrics
                canvas.drawText(shown, pasteRect.left + 44f * u, pasteRect.centerY() - (fm.ascent + fm.descent) / 2f, text)
                text.textAlign = Paint.Align.CENTER
                canvas.restore()
            }
            else -> Unit
        }
    }

    /** Everything liquid: balloons, the accent bar and the delete tongue, fused by the goo. */
    private fun drawGoo(canvas: Canvas) {
        val hasInk = balloons.isNotEmpty() || popup != null || tongueKey != null
        if (!hasInk) return
        val node = gooNode
        if (node != null && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 31) {
            node.setPosition(0, 0, width, height)
            val blur = 4.5f * v
            if (blur != gooBlur) {
                gooBlur = blur
                node.setRenderEffect(gooEffect(blur))
            }
            val rc = node.beginRecording(width, height)
            drawInk(rc)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            drawInk(canvas)
        }
    }

    private fun drawInk(c: Canvas) {
        val p = palette
        fill.color = p.preview
        fill.alpha = 255
        for (b in balloons) {
            val t = b.rise.value
            if (t <= 0.005f) continue
            val k = b.keyRect
            // The cap: the key itself turns to ink while it's held.
            fill.alpha = (255 * (t * 1.6f).coerceIn(0f, 1f)).toInt()
            val r = min(16f * u, k.height() / 2f)
            c.drawRoundRect(k, r, r, fill)
            fill.alpha = 255
            // The neck, rising from the key.
            val nw = 18f * u
            val neckH = 20f * v * t
            rect.set(k.centerX() - nw / 2f, k.top + 6f * v - neckH, k.centerX() + nw / 2f, k.top + 6f * v)
            c.drawRect(rect, fill)
            balloonRect(b, rect)
            val rr = 24f * u * (0.3f + 0.7f * t)
            c.drawRoundRect(rect, rr, rr, fill)
        }
        popup?.let { pop ->
            val t = pop.open.value.coerceIn(0f, 1.2f)
            val k = pop.keyRect
            val r = min(16f * u, k.height() / 2f)
            c.drawRoundRect(k, r, r, fill)
            val nw = 18f * u
            rect.set(k.centerX() - nw / 2f, pop.bar.bottom - 6f * v, k.centerX() + nw / 2f, k.top + 6f * v)
            c.drawRect(rect, fill)
            popupRect(pop, t, rect)
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        }
        tongueKey?.let { k ->
            val left = min(tongueLeft.value, k.left)
            rect.set(left, k.top, k.right, k.bottom)
            val r = min(16f * u, k.height() / 2f)
            c.drawRoundRect(rect, r, r, fill)
        }
    }

    /** Labels that ride on the ink, drawn crisp above it. */
    private fun drawOverGoo(canvas: Canvas) {
        val p = palette
        for (b in balloons) {
            val t = b.rise.value
            if (t <= 0.05f) continue
            balloonRect(b, rect)
            drawText(canvas, b.label, rect.centerX(), rect.centerY() - 2f * v, 32f * v, p.label, (255 * t.coerceIn(0f, 1f)).toInt(), rect.width())
        }
        popup?.let { pop ->
            val t = pop.open.value
            if (t < 0.2f) return@let
            popupRect(pop, t.coerceIn(0f, 1f), rect)
            val alpha = (255 * ((t - 0.2f) / 0.8f).coerceIn(0f, 1f)).toInt()
            // Items spread with the bar as it grows out of the key.
            val itemW = rect.width() / pop.items.size
            for ((i, item) in pop.items.withIndex()) {
                val cx = rect.left + itemW * (i + 0.5f)
                val cy = rect.centerY()
                if (i == pop.selected) {
                    fill.color = p.bright
                    fill.alpha = alpha
                    val half = min(itemW, rect.height()) / 2f - 3f * v
                    canvas.drawRoundRect(cx - itemW / 2f + 3f * v, cy - half, cx + itemW / 2f - 3f * v, cy + half, half, half, fill)
                }
                val color = if (i == pop.selected) p.onBright else p.label
                if (item == Layouts.EMOJI) {
                    icon.color = color
                    icon.alpha = alpha
                    Icons.draw(canvas, Icons.EMOJI, cx, cy, 24f * v, icon)
                } else {
                    val shown = if (shift > 0 && item.length == 1 && item[0].isLetter()) item.uppercase() else item
                    drawText(canvas, shown, cx, cy, if (item.length > 1) 17f * v else 24f * v, color, alpha, itemW)
                }
            }
        }
        tongueKey?.let { k ->
            val left = min(tongueLeft.value, k.left)
            icon.color = p.label
            icon.alpha = 255
            Icons.draw(canvas, Icons.DELETE, left + 22f * u, k.centerY(), 22f * v, icon)
            if (tongueWords > 0 && k.right - left > 110f * u) {
                drawText(canvas, if (tongueWords == 1) "1 word" else "$tongueWords words", (left + k.right) / 2f + 10f * u, k.centerY(), 15f * v, p.label, 255, k.right - left - 60f * u)
            }
        }
    }

    private fun balloonRect(b: Balloon, out: RectF) {
        val t = b.rise.value
        val k = b.keyRect
        val bw = 52f * u
        val bh = 60f * v
        val cx = k.centerX().coerceIn(4f * u + bw / 2f, width - 4f * u - bw / 2f)
        val bottom = k.top - 6f * v + 44f * v * (1f - t)
        val s = 0.3f + 0.7f * t
        out.set(cx - bw * s / 2f, bottom - bh * s, cx + bw * s / 2f, bottom)
    }

    private fun popupRect(pop: Popup, t: Float, out: RectF) {
        val s = 0.3f + 0.7f * t
        val bar = pop.bar
        val ax = pop.keyRect.centerX()
        val ay = bar.bottom
        out.set(ax + (bar.left - ax) * s, ay - bar.height() * s, ax + (bar.right - ax) * s, ay)
    }

    private fun drawTrail(canvas: Canvas) {
        if (!trailOn || trailEnd - trailStart < 2) return
        val n = trailEnd - trailStart
        trail.color = palette.trail
        val head = 7f * v
        for (i in trailStart + 1 until trailEnd) {
            val f = (i - trailStart).toFloat() / n
            trail.strokeWidth = 1f * v + (head - 1f * v) * f
            trail.alpha = (60 + 180 * f).toInt().coerceAtMost(255)
            canvas.drawLine(trailXs[i - 1], trailYs[i - 1], trailXs[i], trailYs[i], trail)
        }
    }

    // ================================================================ Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    // Swipes keep every point the screen reported, not just the latest.
                    if (t.mode == Mode.Gesture) {
                        for (h in 0 until e.historySize) addTrail(t, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    }
                    move(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                touches.remove(e.getPointerId(i))?.let { up(it, e.getX(i), e.getY(i)) }
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.values.toList().forEach { cancel(it) }
                touches.clear()
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // Rolling over: a new finger lands while another still holds a key, so that key types now.
        for (other in touches.values) {
            if (other.mode == Mode.Tap && other.key?.key?.type == KeyType.Text) {
                fire(other)
                release(other)
                other.mode = Mode.Done
            }
        }

        if (y < keysTop - 4f * v) {
            val t = Touch(id, null, x, y)
            t.mode = Mode.Strip
            t.stripTarget = stripTargetAt(x, y)
            if (t.stripTarget != -1) {
                pressStrip(t.stripTarget, true)
                if (t.stripTarget in 0..2) {
                    val target = t.stripTarget
                    t.longPress = Runnable {
                        t.mode = Mode.Done
                        pressStrip(target, false)
                        listener.onSuggestionLongPress(target)
                    }.also { postDelayed(it, LONG_PRESS) }
                }
            }
            touches[id] = t
            return
        }
        if (keysHidden) return
        val l = layout ?: return
        val k = l.keyAt(x, y) ?: return
        val t = Touch(id, k, x, y)
        t.keyIndex = l.keys.indexOf(k)
        t.addPoint(x, y)
        touches[id] = t
        feedback(k.key)
        pressKey(t, true)

        when (k.key.type) {
            KeyType.Delete -> {
                // With delete-swipe on, a tap deletes as it lifts, so a swipe never takes a letter first.
                if (!deleteSwipeOn) listener.onDelete(false)
                val rep = object : Runnable {
                    var count = 0
                    override fun run() {
                        if (touches[id] !== t || t.mode != Mode.Tap) return
                        count++
                        // The first repeat is the delete the tap was holding back.
                        listener.onDelete(t.repeated || !deleteSwipeOn)
                        t.repeated = true
                        postDelayed(this, if (count > 12) 35L else 55L)
                    }
                }
                t.repeat = rep
                postDelayed(rep, 400L)
            }
            KeyType.Space -> t.longPress = Runnable {
                if (t.mode == Mode.Tap) {
                    t.mode = Mode.Done
                    pressKey(t, false)
                    listener.onSpaceLongPress()
                }
            }.also { postDelayed(it, LONG_PRESS + 150L) }
            KeyType.Shift -> {
                // Shift acts as it goes down, so holding it while tapping a letter types a capital.
                listener.onShift()
                t.longPress = Runnable {
                    if (t.mode == Mode.Tap) {
                        t.mode = Mode.Done
                        listener.onShiftLock()
                    }
                }.also { postDelayed(it, LONG_PRESS) }
            }
            KeyType.Text -> if (k.key.popup.isNotEmpty()) {
                t.longPress = Runnable { if (t.mode == Mode.Tap && touches[id] === t) openPopup(t) }.also { postDelayed(it, LONG_PRESS) }
            }
            else -> Unit
        }
    }

    private fun move(t: Touch, x: Float, y: Float) {
        t.x = x; t.y = y
        val l = layout ?: return
        val k = t.key
        val keyW = Key.LETTER * u
        when (t.mode) {
            Mode.Tap -> {
                if (k == null) return
                val dx = x - t.x0
                val dy = y - t.y0
                when {
                    k.key.type == KeyType.Delete && deleteSwipeOn && -dx > 0.4f * keyW -> {
                        cancelTimers(t)
                        t.mode = Mode.DeleteSwipe
                        startTongue(k)
                        updateDeleteSwipe(t)
                    }
                    k.key.type == KeyType.Space && spaceCursorOn && abs(dx) > 0.5f * keyW -> {
                        cancelTimers(t)
                        t.mode = Mode.SpaceSlide
                        spaceSliding = true
                        updateSpaceSlide(t)
                    }
                    k.key.type == KeyType.Sheet && (abs(dx) > 0.6f * keyW || abs(dy) > 0.6f * k.h) -> {
                        // Slide off 123 onto a symbol to type it and come straight back.
                        val target = k.key.sheet ?: return
                        t.returnSheet = sheet
                        t.mode = Mode.SheetSlide
                        pressKey(t, false)
                        listener.onSheet(target)
                        t.key = layout?.keyAt(x, y)
                    }
                    k.key.isLetter && swipeOn && touches.size == 1 && hypot(dx, dy) > 0.45f * keyW -> {
                        cancelTimers(t)
                        t.mode = Mode.Gesture
                        releaseBalloon(t, quick = true)
                        pressKey(t, false)
                        startTrail(t)
                    }
                    !swipeOn || k.key.type != KeyType.Text -> {
                        // Sliding onto a neighbouring key moves the press with you.
                        val now = l.keyAt(x, y)
                        if (now != null && now !== k && k.key.type == KeyType.Text && now.key.type == KeyType.Text) {
                            cancelTimers(t)
                            pressKey(t, false)
                            t.key = now
                            t.keyIndex = l.keys.indexOf(now)
                            pressKey(t, true)
                            if (now.key.popup.isNotEmpty()) {
                                t.longPress = Runnable { if (t.mode == Mode.Tap) openPopup(t) }.also { postDelayed(it, LONG_PRESS) }
                            }
                        }
                    }
                }
            }
            Mode.Gesture -> addTrail(t, x, y)
            Mode.DeleteSwipe -> updateDeleteSwipe(t)
            Mode.SpaceSlide -> updateSpaceSlide(t)
            Mode.Popup -> popup?.let { pop ->
                val i = ((x - pop.bar.left) / pop.itemWidth).toInt().coerceIn(0, pop.items.size - 1)
                if (i != pop.selected) {
                    pop.selected = i
                    if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    frame()
                }
            }
            Mode.SheetSlide -> t.key = layout?.keyAt(x, y)
            else -> Unit
        }
    }

    private fun up(t: Touch, x: Float, y: Float) {
        cancelTimers(t)
        when (t.mode) {
            Mode.Tap -> {
                fire(t)
                release(t)
            }
            Mode.Gesture -> {
                addTrail(t, x, y)
                trailLive = false
                val xs = t.xs.copyOf(t.n)
                val ys = t.ys.copyOf(t.n)
                listener.onSwipe(xs, ys)
                frame()
            }
            Mode.DeleteSwipe -> {
                listener.onDeleteWords(t.deleteWords, true)
                endTongue()
                release(t)
            }
            Mode.SpaceSlide -> {
                spaceSliding = false
                release(t)
            }
            Mode.Popup -> {
                popup?.let { pop ->
                    val item = pop.items[pop.selected]
                    if (item == Layouts.EMOJI) listener.onEmoji() else listener.onText(item, Float.NaN, Float.NaN)
                }
                closePopup()
                release(t)
            }
            Mode.SheetSlide -> {
                val k = t.key
                if (k != null && k.key.type == KeyType.Text) {
                    listener.onText(k.key.text, Float.NaN, Float.NaN)
                    t.returnSheet?.let { listener.onSheet(it) }
                }
            }
            Mode.Strip -> {
                val target = t.stripTarget
                if (target != -1) {
                    pressStrip(target, false)
                    if (stripTargetAt(x, y) == target) fireStrip(target)
                }
            }
            Mode.Done -> release(t)
        }
        frame()
    }

    private fun cancel(t: Touch) {
        cancelTimers(t)
        if (t.mode == Mode.DeleteSwipe) {
            listener.onDeleteWords(0, true)
            endTongue()
        }
        if (t.mode == Mode.Popup) closePopup()
        if (t.mode == Mode.Strip && t.stripTarget != -1) pressStrip(t.stripTarget, false)
        spaceSliding = false
        trailLive = false
        release(t)
    }

    private fun cancelTimers(t: Touch) {
        t.longPress?.let { removeCallbacks(it) }
        t.repeat?.let { removeCallbacks(it) }
        t.longPress = null
        t.repeat = null
    }

    /** What a tap on [t]'s key does. */
    private fun fire(t: Touch) {
        val k = t.key ?: return
        when (k.key.type) {
            KeyType.Text -> listener.onText(k.key.text, t.x, t.y)
            KeyType.Space -> listener.onSpace()
            KeyType.Enter -> listener.onEnter()
            KeyType.Shift -> Unit
            KeyType.Sheet -> k.key.sheet?.let { listener.onSheet(it) }
            KeyType.Emoji -> listener.onEmoji()
            KeyType.Delete -> if (deleteSwipeOn && !t.repeated) listener.onDelete(false)
        }
    }

    private fun feedback(key: Key) {
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        sound?.invoke(key)
    }

    /** Plays the key sound, when switched on. Set by the input method. */
    var sound: ((Key) -> Unit)? = null

    private fun pressKey(t: Touch, down: Boolean) {
        val k = t.key ?: return
        val i = layout?.keys?.indexOf(k) ?: -1
        if (k.key.type == KeyType.Text && !isWide(k)) {
            if (down && balloonsOn) {
                val b = Balloon(RectF(k.x, k.y, k.right, k.bottom), labelFor(k.key))
                b.rise.to(1f, SpringSpec.Snap)
                balloons += b
                t.balloon = b
            } else if (!down) releaseBalloon(t, quick = false)
        } else if (i >= 0 && i < press.size) {
            press[i].to(if (down) 1f else 0f, if (down) SpringSpec.Snap else SpringSpec.Flow)
        }
        frame()
    }

    private fun labelFor(key: Key) = if (key.isLetter && shift > 0) key.label.uppercase() else key.label

    private fun releaseBalloon(t: Touch, quick: Boolean) {
        t.balloon?.let {
            it.held = false
            it.rise.to(0f, if (quick) SpringSpec.Snap else SpringSpec.Flow)
        }
        t.balloon = null
    }

    private fun release(t: Touch) {
        releaseBalloon(t, quick = false)
        val k = t.key
        if (k != null) {
            val i = layout?.keys?.indexOf(k) ?: -1
            if (i >= 0 && i < press.size) press[i].to(0f, SpringSpec.Flow)
        }
        frame()
    }

    // ---- Long-press accents ----

    private fun openPopup(t: Touch) {
        val k = t.key ?: return
        val items = k.key.popup
        if (items.isEmpty()) return
        releaseBalloon(t, quick = true)
        text.typeface = regular
        text.textSize = 17f * v
        val itemW = max(40f * u, items.maxOf { if (it == Layouts.EMOJI) 0f else text.measureText(it) } + 18f * u)
        val barW = itemW * items.size
        val barH = 52f * v
        val left = (k.cx - itemW / 2f).coerceIn(6f * u, max(6f * u, width - 6f * u - barW))
        val bottom = k.y - 10f * v
        val bar = RectF(left, bottom - barH, left + barW, bottom)
        val selected = ((k.cx - left) / itemW).toInt().coerceIn(0, items.size - 1)
        val pop = Popup(RectF(k.x, k.y, k.right, k.bottom), items, bar, itemW, selected)
        pop.open.to(1f, SpringSpec.Snap)
        popup = pop
        t.mode = Mode.Popup
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        frame()
    }

    private fun closePopup() {
        popup = null
        frame()
    }

    // ---- Delete swipe ----

    private fun startTongue(k: PlacedKey) {
        tongueKey = RectF(k.x, k.y, k.right, k.bottom)
        tongueLeft.snap(k.x)
        tongueHeld = true
        tongueWords = 0
    }

    private fun updateDeleteSwipe(t: Touch) {
        val k = tongueKey ?: return
        tongueLeft.to(min(k.left, max(6f * u, t.x - 20f * u)), SpringSpec.Snap)
        val step = 0.75f * Key.LETTER * u
        val words = max(0, ((t.x0 - t.x) / step).toInt())
        if (words != t.deleteWords) {
            t.deleteWords = words
            tongueWords = words
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener.onDeleteWords(words, false)
        }
        frame()
    }

    private fun endTongue() {
        tongueHeld = false
        tongueWords = 0
        tongueKey?.let { tongueLeft.to(it.left, SpringSpec.Flow) }
        frame()
    }

    // ---- Space slide ----

    private fun updateSpaceSlide(t: Touch) {
        val step = 0.32f * Key.LETTER * u
        val steps = ((t.x - t.x0) / step).toInt()
        if (steps != t.spaceSteps) {
            val delta = steps - t.spaceSteps
            t.spaceSteps = steps
            if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener.onCursor(delta)
        }
        frame()
    }

    // ---- Swipe trail ----

    private fun startTrail(t: Touch) {
        trailLive = true
        trailStart = 0
        trailEnd = 0
        trailXs = FloatArray(512)
        trailYs = FloatArray(512)
        for (i in 0 until t.n) pushTrail(t.xs[i], t.ys[i])
        frame()
    }

    private fun addTrail(t: Touch, x: Float, y: Float) {
        if (t.n > 0 && abs(t.xs[t.n - 1] - x) < 0.5f && abs(t.ys[t.n - 1] - y) < 0.5f) return
        t.addPoint(x, y)
        pushTrail(x, y)
    }

    private fun pushTrail(x: Float, y: Float) {
        if (trailEnd == trailXs.size) {
            // Keep the most recent stretch; the tail has faded anyway.
            val keep = trailXs.size / 2
            System.arraycopy(trailXs, trailEnd - keep, trailXs, 0, keep)
            System.arraycopy(trailYs, trailEnd - keep, trailYs, 0, keep)
            trailStart = max(0, trailStart - (trailEnd - keep))
            trailEnd = keep
        }
        trailXs[trailEnd] = x
        trailYs[trailEnd] = y
        trailEnd++
        // Only the last stretch of the trail is shown.
        if (trailEnd - trailStart > 90) trailStart = trailEnd - 90
    }

    // ---- Strip ----

    private fun stripTargetAt(x: Float, y: Float): Int {
        if (y < massTop || y > keysTop) return -1
        val pad = 5f * u
        if (x <= hubRect.right + pad) return STRIP_HUB
        if (strip is Strip.Tools || activeTool != null) {
            val reach = min(pad, toolGap / 2f)
            for ((t, r) in toolRects) if (x >= r.left - reach && x <= r.right + reach) return STRIP_TOOL + t.ordinal
            return -1
        }
        return when (val s = strip) {
            is Strip.Words -> {
                for (i in 0 until min(3, s.words.size)) if (x >= slotRects[i].left - 2f && x <= slotRects[i].right + 2f) return i
                -1
            }
            is Strip.Paste -> STRIP_PASTE
            else -> -1
        }
    }

    private fun pressStrip(target: Int, down: Boolean) {
        val s = stripPress.getOrPut(target) { Spring() }
        s.to(if (down) 1f else 0f, if (down) SpringSpec.Snap else SpringSpec.Flow)
        if (down && vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        frame()
    }

    private fun fireStrip(target: Int) {
        when {
            target in 0..2 -> listener.onSuggestion(target)
            target == STRIP_HUB -> listener.onHub()
            target == STRIP_PASTE -> listener.onPaste()
            target >= STRIP_TOOL -> listener.onTool(Tool.values()[target - STRIP_TOOL])
        }
    }

    // ================================================================ Goo

    private fun gooEffect(blur: Float): RenderEffect {
        val threshold = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 20f, -9f * 255f,
            ),
        )
        val b = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)
        val sharpen = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(threshold))
        return RenderEffect.createChainEffect(sharpen, b)
    }

    companion object {
        private const val LONG_PRESS = 360L
        private const val STRIP_HUB = 10
        private const val STRIP_PASTE = 11
        private const val STRIP_TOOL = 20
        /** The tools in the strip, in order after the hub. */
        private val STRIP_TOOLS = listOf(Tool.Clipboard, Tool.Emoji, Tool.Edit, Tool.Scan, Tool.Settings)
    }
}
