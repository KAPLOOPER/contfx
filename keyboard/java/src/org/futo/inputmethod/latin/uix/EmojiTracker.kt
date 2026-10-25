package org.futo.inputmethod.latin.uix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.uix.actions.skinTones
import org.futo.inputmethod.latin.uix.actions.stripVariationSelector16


val lastUsedEmoji = stringPreferencesKey("last_used_emoji")
const val EmojiLimit = 32

// Continuum Keyboard: how often each emoji is used, for the Frequently used row.
private val emojiUseCounts = stringPreferencesKey("continuum_emoji_use_counts")
private const val EmojiCountsKept = 64
const val FrequentEmojiLimit = 24

object EmojiTracker {
    // Temporary for #2316 to fix recently used emojis
    private val patchEmoji = { em: String ->
        if (skinTones.filter { it.isNotEmpty() }.any { it in em })
            em.stripVariationSelector16()
        else em
    }

    suspend fun Context.useEmoji(emoji: String) {
        if(isDeviceLocked) return

        dataStore.edit { ds ->
            val combined = emoji + "<|>" + (ds[lastUsedEmoji] ?: "")
            ds[lastUsedEmoji] = combined.split("<|>").map(patchEmoji)
                .distinct().take(EmojiLimit).joinToString("<|>")

            val counts = decodeCounts(ds[emojiUseCounts] ?: "").toMutableMap()
            val key = patchEmoji(emoji)
            counts[key] = (counts[key] ?: 0) + 1
            ds[emojiUseCounts] = counts.entries
                .sortedByDescending { it.value }
                .take(EmojiCountsKept)
                .joinToString("<|>") { "${it.key}\t${it.value}" }
        }
    }

    private fun decodeCounts(raw: String): Map<String, Int> =
        raw.split("<|>").mapNotNull { entry ->
            val parts = entry.split("\t")
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && parts[0].isNotBlank() && count != null) parts[0] to count else null
        }.toMap()

    /** Emojis used at least twice, most used first. */
    suspend fun Context.getFrequentEmojis(): List<String> {
        if(isDeviceLocked) return listOf()

        return decodeCounts(getSetting(emojiUseCounts, ""))
            .filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(FrequentEmojiLimit)
    }

    suspend fun Context.getRecentEmojis(): List<String> {
        if(isDeviceLocked) return listOf()

        return getSetting(lastUsedEmoji, "")
            .split("<|>")
            .filter { it.isNotBlank() }
            .distinct()
    }

    suspend fun Context.resetRecentEmojis() {
        if(isDeviceLocked) return

        setSetting(lastUsedEmoji, "")
        setSetting(emojiUseCounts, "")
    }
}