package dev.continuum.keyboard.layout

import dev.continuum.keyboard.engine.KeyMap

/** What a key does. */
enum class KeyType { Text, Shift, Delete, Sheet, Space, Enter, Emoji }

/** The sets of keys the keyboard can show. */
enum class Sheet { Letters, Symbols, More, Numbers, Phone }

/** The kind of field being typed into, which changes a few keys. */
enum class FieldMode { Text, Email, Url, Number, Phone }

/**
 * One key. Widths are in design units, where the keyboard is 390 units wide.
 */
data class Key(
    val type: KeyType,
    /** What it types (letters in lower case). */
    val text: String = "",
    /** What it shows, when that isn't [text]. */
    val label: String = text,
    val width: Float = LETTER,
    /** Characters offered on a long press. */
    val popup: List<String> = emptyList(),
    /** Where a [KeyType.Sheet] key goes. */
    val sheet: Sheet? = null,
) {
    val isLetter: Boolean get() = type == KeyType.Text && text.length == 1 && text[0].isLetter()

    /** Function keys get the darker cap and a press squash instead of a balloon. */
    val isFunction: Boolean get() = type != KeyType.Text

    companion object {
        const val LETTER = 33f
        const val GAP = 5.6f
        const val WIDTH = 390f
        const val SIDE = 4.8f
    }
}

/** How a row is laid out: centred with keys pinned to each edge, or stretched to fill. */
enum class RowKind { Centred, Fill }

class Row(val kind: RowKind, val keys: List<Key>, val short: Boolean = false)

/** A key placed on screen, in pixels. */
class PlacedKey(val key: Key, val row: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
    val right: Float get() = x + w
    val bottom: Float get() = y + h
}

/** A whole sheet placed on screen. */
class Layout(val sheet: Sheet, val keys: List<PlacedKey>, val width: Float, val height: Float, val keyHeight: Float) {

    private val rows: List<List<PlacedKey>> = keys.groupBy { it.row }.toSortedMap().values.toList()

    /** The key under (x, y): nearest in its row, so gaps never swallow a tap. */
    fun keyAt(x: Float, y: Float): PlacedKey? {
        if (rows.isEmpty()) return null
        var row = rows.first()
        for (r in rows) {
            val top = r.first().y
            if (y >= top - (r.first().h * 0.12f)) row = r
        }
        var best: PlacedKey? = null
        var bestD = Float.MAX_VALUE
        for (k in row) {
            val d = when {
                x < k.x -> k.x - x
                x > k.right -> x - k.right
                else -> 0f
            }
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** The letters' centres, for corrections and swipes. */
    fun keyMap(): KeyMap? {
        val letters = keys.filter { it.key.isLetter && it.key.text[0] in 'a'..'z' }
        if (letters.size < 20) return null
        val m = HashMap<Char, FloatArray>()
        for (k in letters) m[k.key.text[0]] = floatArrayOf(k.cx, k.cy)
        val w = letters.first().w + gapOf(letters)
        val h = rows.getOrNull(1)?.let { r -> r.first().y - rows[0].first().y } ?: keyHeight
        return KeyMap(m, w, h)
    }

    private fun gapOf(letters: List<PlacedKey>): Float {
        val row = letters.filter { it.row == letters.first().row }.sortedBy { it.x }
        return if (row.size > 1) row[1].x - row[0].right else 0f
    }
}

object Layouts {

    private val ACCENTS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        "g" to listOf("ğ"),
    )

    private val SYMBOL_POPUPS = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴"),
        "0" to listOf("°", "ⁿ", "∅"),
        "-" to listOf("–", "—", "_", "·"),
        "/" to listOf("\\"),
        "$" to listOf("€", "£", "¥", "₹", "¢", "₩"),
        "&" to listOf("§"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "'" to listOf("‘", "’", "‚", "‹", "›"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "." to listOf("…"),
        "%" to listOf("‰"),
        "*" to listOf("†", "‡", "★"),
        "=" to listOf("≠", "≈", "±"),
        "<" to listOf("≤", "«", "‹"),
        ">" to listOf("≥", "»", "›"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "#" to listOf("№"),
        "+" to listOf("±"),
    )

    /** What a long press on the full stop offers. */
    val PERIOD_POPUP = listOf("?", "!", ",", "'", "\"", ":", ";", "-", "(", ")", "@", "&", "/", "…")
    private val URL_POPUP = listOf(".com", ".org", ".net", ".co.uk", ".io", "/", ":")

    private fun text(t: String, width: Float = Key.LETTER, popup: List<String>? = null) =
        Key(KeyType.Text, t, width = width, popup = popup ?: SYMBOL_POPUPS[t] ?: emptyList())

    private fun letter(c: Char, digit: Char?): Key {
        val accents = ACCENTS[c.toString()] ?: emptyList()
        val popup = if (digit != null) listOf(digit.toString()) + accents else accents
        return Key(KeyType.Text, c.toString(), popup = popup)
    }

    private fun bottomRow(sheet: Sheet, mode: FieldMode, enter: String): Row {
        val toggle = if (sheet == Sheet.Letters) Key(KeyType.Sheet, label = "123", width = 52f, sheet = Sheet.Symbols)
        else Key(KeyType.Sheet, label = "ABC", width = 52f, sheet = Sheet.Letters)
        val second = when (mode) {
            FieldMode.Email -> text("@", 36f)
            FieldMode.Url -> text("/", 36f)
            // The comma doubles as the way into emoji on a long press.
            else -> Key(KeyType.Text, ",", width = 36f, popup = listOf(EMOJI))
        }
        val period = when (mode) {
            FieldMode.Url -> text(".", 36f, URL_POPUP)
            else -> text(".", 36f, PERIOD_POPUP)
        }
        return Row(
            RowKind.Fill,
            listOf(
                toggle,
                second,
                Key(KeyType.Space, " ", label = "space", width = 152.4f),
                period,
                Key(KeyType.Enter, "\n", label = enter, width = 81.6f),
            ),
        )
    }

    /** Marks the emoji entry in a popup. */
    const val EMOJI = "\u0000emoji"

    fun rows(sheet: Sheet, mode: FieldMode, enter: String, numberRow: Boolean): List<Row> = when (sheet) {
        Sheet.Letters -> buildList {
            if (numberRow) add(Row(RowKind.Centred, "1234567890".map { text(it.toString()) }, short = true))
            val digits = "1234567890"
            add(Row(RowKind.Centred, "qwertyuiop".mapIndexed { i, c -> letter(c, if (numberRow) null else digits[i]) }))
            add(Row(RowKind.Centred, "asdfghjkl".map { letter(it, null) }))
            add(
                Row(
                    RowKind.Centred,
                    listOf(Key(KeyType.Shift, label = "shift", width = 48f)) +
                        "zxcvbnm".map { letter(it, null) } +
                        Key(KeyType.Delete, label = "delete", width = 48f),
                ),
            )
            add(bottomRow(sheet, mode, enter))
        }
        Sheet.Symbols -> listOf(
            Row(RowKind.Centred, "1234567890".map { text(it.toString()) }),
            Row(RowKind.Centred, listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { text(it) }),
            Row(
                RowKind.Centred,
                listOf(Key(KeyType.Sheet, label = "#+=", width = 48f, sheet = Sheet.More)) +
                    listOf(".", ",", "?", "!", "'").map { text(it, 50.16f) } +
                    Key(KeyType.Delete, label = "delete", width = 48f),
            ),
            bottomRow(sheet, mode, enter),
        )
        Sheet.More -> listOf(
            Row(RowKind.Centred, listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { text(it) }),
            Row(RowKind.Centred, listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { text(it) }),
            Row(
                RowKind.Centred,
                listOf(Key(KeyType.Sheet, label = "123", width = 48f, sheet = Sheet.Symbols)) +
                    listOf(".", ",", "?", "!", "'").map { text(it, 50.16f) } +
                    Key(KeyType.Delete, label = "delete", width = 48f),
            ),
            bottomRow(sheet, mode, enter),
        )
        Sheet.Numbers -> {
            val w = 90f
            listOf(
                Row(RowKind.Fill, listOf(text("1", w), text("2", w), text("3", w), text("-", w, listOf("+", "/", ":", "−")))),
                Row(RowKind.Fill, listOf(text("4", w), text("5", w), text("6", w), Key(KeyType.Space, " ", label = "space", width = w))),
                Row(RowKind.Fill, listOf(text("7", w), text("8", w), text("9", w), Key(KeyType.Delete, label = "delete", width = w))),
                Row(RowKind.Fill, listOf(text(",", w, listOf("/", ":")), text("0", w), text(".", w), Key(KeyType.Enter, "\n", label = enter, width = w))),
            )
        }
        Sheet.Phone -> {
            val w = 90f
            listOf(
                Row(RowKind.Fill, listOf(text("1", w), text("2", w), text("3", w), text("+", w, listOf("(", ")", "-", ".", ",")))),
                Row(RowKind.Fill, listOf(text("4", w), text("5", w), text("6", w), Key(KeyType.Space, " ", label = "space", width = w))),
                Row(RowKind.Fill, listOf(text("7", w), text("8", w), text("9", w), Key(KeyType.Delete, label = "delete", width = w))),
                Row(RowKind.Fill, listOf(text("*", w), text("0", w, listOf("+")), text("#", w), Key(KeyType.Enter, "\n", label = enter, width = w))),
            )
        }
    }

    /**
     * Places [rows] in a keyboard [width] pixels wide. [unit] is pixels per design unit
     * across, [rowPitch] the distance between rows and [keyHeight] each key's height.
     */
    fun place(sheet: Sheet, rows: List<Row>, width: Float, rowPitch: Float, keyHeight: Float, top: Float = 0f): Layout {
        val u = width / Key.WIDTH
        val gap = Key.GAP * u
        val side = Key.SIDE * u
        val placed = ArrayList<PlacedKey>()
        var y = top
        rows.forEachIndexed { r, row ->
            val h = if (row.short) keyHeight * 0.82f else keyHeight
            when (row.kind) {
                RowKind.Fill -> {
                    val total = row.keys.sumOf { it.width.toDouble() }.toFloat()
                    val avail = width - 2 * side - gap * (row.keys.size - 1)
                    var x = side
                    for (k in row.keys) {
                        val w = avail * k.width / total
                        placed += PlacedKey(k, r, x, y, w, h)
                        x += w + gap
                    }
                }
                RowKind.Centred -> {
                    val left = row.keys.takeWhile { it.isFunction }
                    val right = row.keys.takeLastWhile { it.isFunction }
                    val middle = row.keys.subList(left.size, row.keys.size - right.size)
                    var x = side
                    for (k in left) {
                        placed += PlacedKey(k, r, x, y, k.width * u, h)
                        x += k.width * u + gap
                    }
                    val midWidth = middle.sumOf { it.width.toDouble() }.toFloat() * u + gap * (middle.size - 1)
                    var mx = (width - midWidth) / 2f
                    for (k in middle) {
                        placed += PlacedKey(k, r, mx, y, k.width * u, h)
                        mx += k.width * u + gap
                    }
                    var rx = width - side
                    for (k in right.reversed()) {
                        rx -= k.width * u
                        placed += PlacedKey(k, r, rx, y, k.width * u, h)
                        rx -= gap
                    }
                }
            }
            y += if (row.short) rowPitch * 0.82f else rowPitch
        }
        val height = y - (rowPitch - keyHeight) - top
        return Layout(sheet, placed, width, height, keyHeight)
    }
}
