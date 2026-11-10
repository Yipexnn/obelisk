package com.obelisk.reroller

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One OCR line with its words. */
data class OLine(val text: String, val box: Rect, val words: List<Pair<String, Rect>>)

object OcrParser {

    /** Flattens ML Kit output, dropping anything inside [exclude] (our own floating panel). */
    fun lines(text: Text, exclude: Rect?): List<OLine> =
        text.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null }
            .filter { l -> exclude == null || !Rect.intersects(exclude, l.boundingBox!!) }
            .map { l ->
                OLine(
                    l.text,
                    l.boundingBox!!,
                    l.elements.filter { it.boundingBox != null }.map { it.text to it.boundingBox!! },
                )
            }

    fun allText(lines: List<OLine>) = lines.joinToString("\n") { it.text }.lowercase()

    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Relic name part of a line: "Supernova Multipliers +2x" -> "supernovamultipliers". */
    private fun nameOf(s: String) = norm(s.substringBefore('+'))

    private val countRe = Regex("""^\s*([0-9OoIl|]{1,3})\s*(?:/\s*[0-9OoIl|]{1,3})?\s*$""")

    private fun digits(s: String) = s.replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1').replace('|', '1')

    /** Parses "7/7", "1/7", "3". Returns null if the line isn't a count. */
    private fun parseCount(raw: String): Int? {
        val t = raw.trim()
        val m = countRe.find(t) ?: return null
        val first = digits(m.groupValues[1])
        // A "7/7" whose slash was read as "1" ("717"): keep the first digit.
        if (!t.contains('/') && first.length == 3 && first[1] == '1') return first[0].digitToIntOrNull()
        return first.toIntOrNull()
    }

    private fun bestNameLine(lines: List<OLine>, name: String): OLine? {
        val target = norm(name)
        return lines.map { it to similarity(nameOf(it.text), target) }
            .filter { it.second >= 0.8 }
            .maxByOrNull { it.second }?.first
    }

    /**
     * Reads the Divine list shown after opening relics. Each slot has the name in the middle and
     * the count ("7/7", "1/7" or "3") in the top-right box, slightly above the name's centre.
     * Returns null for names not on screen (slot not found yet -> treat as 0).
     */
    fun divineCounts(lines: List<OLine>, names: List<String>): Map<String, Int?> =
        names.associateWith { name ->
            val nl = bestNameLine(lines, name) ?: return@associateWith null
            val b = nl.box
            val h = max(b.height(), 20)
            lines.asSequence()
                .filter { it !== nl && it.box.left >= b.right - h }
                .filter { it.box.centerY() >= b.top - h * 1.5 && it.box.centerY() <= b.centerY() + h * 0.3 }
                .mapNotNull { l -> parseCount(l.text)?.let { it to l } }
                .minByOrNull { abs(it.second.box.centerY() - b.top) }
                ?.first ?: 0
        }

    /** True if the Divine list seems to be on screen at all. */
    fun divineVisible(lines: List<OLine>, names: List<String>): Boolean {
        val all = allText(lines)
        if ("not found yet" in all || "divine relics" in all) return true
        return (names + DIVINE_PRESETS).any { bestNameLine(lines, it) != null }
    }

    // ------------------------------------------------------------ Summary popup

    private val summaryCountRe = Regex("""[0-9OoIl|]+\s*[xX×]\s*[)\]}]""")
    private val prefixCountRe = Regex("""([0-9OoIlS|]{1,6})\s*[xX×]""")

    /** True if the Summary popup ("Loot from", "(123x) …" rows) is on screen. */
    fun summaryVisible(lines: List<OLine>): Boolean {
        val all = allText(lines)
        if ("loot from" in all || "tap anywhere" in all) return true
        return lines.count { summaryCountRe.containsMatchIn(it.text) } >= 5
    }

    /**
     * Best approximate match of [target] anywhere inside [hay] (sliding window).
     * Returns (score 0..1, end index of the match in hay).
     */
    private fun windowMatch(hay: String, target: String): Pair<Double, Int> {
        if (hay.isEmpty() || target.isEmpty()) return 0.0 to 0
        var best = 0.0 to 0
        val n = target.length
        for (len in maxOf(1, n - 2)..(n + 2)) {
            if (len > hay.length) {
                val sc = similarity(hay, target)
                if (sc > best.first) best = sc to hay.length
                break
            }
            for (i in 0..hay.length - len) {
                val sc = similarity(hay.substring(i, i + len), target)
                if (sc > best.first) best = sc to i
            }
        }
        return best
    }

    /**
     * Reads Summary rows like "(4x) Supernova Multipliers +2x".
     * Name is matched fuzzily anywhere in the line; the count is the "Nx" before it, or on a
     * separate OCR line to the left on the same row. Missing relics count as 0.
     */
    fun summaryCounts(lines: List<OLine>, names: List<String>): Map<String, Int> =
        names.associateWith { name ->
            val target = norm(name)
            val hit = lines.map { l ->
                val nameArea = l.text.substringBefore('+')
                l to windowMatch(norm(nameArea), target).first
            }.filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first ?: return@associateWith 0

            fun countIn(s: String): Int? = prefixCountRe.find(s)?.groupValues?.get(1)
                ?.let { digits(it).replace('S', '5') }?.toIntOrNull()

            // Only look before the first word, so "Fishing Tick 5x" isn't taken as the count.
            val firstWord = Regex("[A-Za-z]{3,}").find(hit.text)?.range?.first ?: hit.text.length
            countIn(hit.text.substring(0, firstWord)) ?: run {
                val b = hit.box
                lines.filter {
                    it !== hit && it.box.right <= b.left + b.height() &&
                        abs(it.box.centerY() - b.centerY()) < b.height() * 0.6
                }.maxByOrNull { it.box.right }?.let { countIn(it.text) }
            } ?: 0
        }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private val numRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:[eE]\s*(\d+))?""")

    private fun dist2(b: Rect, p: Point): Float {
        val dx = max(0f, max(b.left - p.x, p.x - b.right))
        val dy = max(0f, max(b.top - p.y, p.y - b.bottom))
        return dx * dx + dy * dy
    }

    /** Reads the gem counter: the number closest to the calibrated point. */
    fun gemsNear(lines: List<OLine>, p: Point): Long? {
        val best = lines.flatMap { it.words }
            .filter { w -> w.first.any { it.isDigit() } }
            .minByOrNull { dist2(it.second, p) } ?: return null
        if (dist2(best.second, p) > 250f * 250f) return null
        val m = numRe.find(digits(best.first)) ?: return null
        val base = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val exp = m.groupValues[2].toIntOrNull() ?: 0
        val v = base * Math.pow(10.0, exp.toDouble())
        return if (v > Long.MAX_VALUE / 2) Long.MAX_VALUE / 2 else v.toLong()
    }
}
