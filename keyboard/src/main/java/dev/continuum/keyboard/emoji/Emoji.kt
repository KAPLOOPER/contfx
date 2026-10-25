package dev.continuum.keyboard.emoji

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint

/** One emoji, with its skin-tone versions when it has them. */
class EmojiItem(val emoji: String, val label: String, val skins: List<String>)

class EmojiGroup(val id: Int, val items: List<EmojiItem>)

/** Unicode's emoji, grouped, leaving out any this phone can't draw. */
class EmojiData(val groups: List<EmojiGroup>) {
    companion object {
        /** Unicode group numbers in the order they're shown (2, components, is skipped). */
        val ORDER = listOf(0, 1, 3, 4, 5, 6, 7, 8, 9)

        @Volatile
        private var cached: EmojiData? = null

        fun load(context: Context): EmojiData {
            cached?.let { return it }
            val paint = Paint()
            val byGroup = LinkedHashMap<Int, MutableList<EmojiItem>>()
            context.assets.open("emoji/emoji.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 4) continue
                    val group = parts[0].toIntOrNull() ?: continue
                    val emoji = parts[1]
                    if (!paint.hasGlyph(emoji)) continue
                    val skins = if (parts[3].isEmpty()) emptyList() else parts[3].split(',').filter { paint.hasGlyph(it) }
                    byGroup.getOrPut(group) { ArrayList() } += EmojiItem(emoji, parts[2], if (skins.size == 5) skins else emptyList())
                }
            }
            val data = EmojiData(ORDER.mapNotNull { g -> byGroup[g]?.let { EmojiGroup(g, it) } })
            cached = data
            return data
        }
    }
}

/** Which emoji you use, for the Frequent tab, and the skin tone you picked for each. */
class EmojiUsage(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("continuum_emoji", Context.MODE_PRIVATE)

    fun record(emoji: String, base: String) {
        val key = "n:$base"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).putLong("t:$base", System.currentTimeMillis()).apply()
        if (emoji != base) sp.edit().putString("s:$base", emoji).apply()
    }

    /** The version of [base] to type: the skin tone you last chose, or [base]. */
    fun variant(base: String): String = sp.getString("s:$base", null) ?: base

    fun setVariant(base: String, emoji: String) {
        sp.edit().putString("s:$base", emoji).apply()
    }

    /** Most used first, recent use breaking ties. */
    fun frequent(limit: Int = 32): List<String> =
        sp.all.keys.asSequence()
            .filter { it.startsWith("n:") }
            .map { it.substring(2) }
            .sortedWith(compareByDescending<String> { sp.getInt("n:$it", 0) }.thenByDescending { sp.getLong("t:$it", 0) })
            .take(limit)
            .toList()
}
