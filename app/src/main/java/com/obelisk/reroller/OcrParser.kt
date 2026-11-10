package com.obelisk.reroller

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
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

    fun digits(s: String) = s.replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1').replace('|', '1').replace('S', '5')

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    /** Best approximate match of [target] anywhere inside [hay] (sliding window), 0..1. */
    fun windowMatch(hay: String, target: String): Double {
        if (hay.isEmpty() || target.isEmpty()) return 0.0
        if (hay.length <= target.length + 2) return similarity(hay, target)
        var best = 0.0
        val n = target.length
        for (len in maxOf(1, n - 2)..(n + 2)) {
            for (i in 0..hay.length - len) {
                val sc = similarity(hay.substring(i, i + len), target)
                if (sc > best) best = sc
            }
        }
        return best
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
