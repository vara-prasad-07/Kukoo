package com.example.kukoo.domain

import java.util.Locale

/**
 * Sound-and-spelling similarity for matching what was heard against text the app already knows
 * (task titles, priority words). Speech recognition produces near-misses constantly — "gim" for
 * "gym", "dec" for "deck" — and exact string matching cannot survive that in a voice product.
 */
object FuzzyText {

    /** 0..100. 100 is identical; anything at or above ~75 is the same word said slightly differently. */
    fun similarity(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0
        if (x == y) return 100
        val distance = levenshtein(x, y)
        val longest = maxOf(x.length, y.length)
        val spelling = ((longest - distance).toDouble() / longest * 100).toInt()
        // Words that sound alike but are spelled differently ("gym"/"jim") score poorly on
        // spelling alone, so the phonetic key carries them.
        val phonetic = if (soundKey(x) == soundKey(y)) 85 else 0
        return maxOf(spelling, phonetic)
    }

    fun similar(a: String, b: String, threshold: Int = 75): Boolean = similarity(a, b) >= threshold

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    /**
     * A deliberately crude phonetic key: drop vowels after the first letter, fold letters that
     * English spells inconsistently, and collapse repeats. "gym" and "jim" both become "jm".
     */
    private fun soundKey(word: String): String {
        if (word.isEmpty()) return ""
        val folded = StringBuilder()
        for ((index, ch) in word.withIndex()) {
            val c = when (ch) {
                'g', 'j' -> 'j'
                'c', 'k', 'q' -> 'k'
                's', 'z' -> 's'
                'f', 'v', 'p' -> 'f'
                'd', 't' -> 't'
                'b' -> 'f'
                'y', 'i' -> 'i'
                'u', 'o' -> 'o'
                else -> ch
            }
            // The first sound is kept even when it is a vowel; later vowels carry little signal.
            if (index == 0 || c !in "aeio") folded.append(c)
        }
        return folded.toString().replace(Regex("(.)\\1+"), "$1")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
