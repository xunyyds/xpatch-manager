package io.xpatch.ui.util

import android.icu.text.Transliterator
import android.util.LruCache

object PinyinIndex {

    private const val TRANSLITERATOR_ID = "Any-Latin; Latin-ASCII"

    private val cache = LruCache<String, String>(512)

    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance(TRANSLITERATOR_ID) }.getOrNull()
    }

    fun sortKey(label: String): String {
        if (label.isBlank()) return ""
        cache.get(label)?.let { return it }
        val key = transliterate(label)
        cache.put(label, key)
        return key
    }

    fun key(label: String): String {
        val sort = sortKey(label)
        val first = sort.firstOrNull() ?: return "#"
        return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
    }

    private fun transliterate(label: String): String {
        val text = label.trim()
        if (text.isEmpty()) return ""
        val latin = transliterator?.transliterate(text) ?: text
        val lowered = latin.lowercase()
        val first = lowered.firstOrNull { it.isLetterOrDigit() } ?: return ""
        return if (first.isDigit()) "" else lowered
    }
}
