package com.obelisk.reroller

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One visual row of text (OCR lines on the same height joined left-to-right). */
data class Row(val text: String, val box: Rect)

/** A summary entry like "(7x) Rainbow Floor Chance +1%". */
data class Entry(val count: Int, val name: String)

object OcrParser {

    fun rows(text: Text): List<Row> {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
        val groups = mutableListOf<MutableList<Text.Line>>()
        for (l in sorted) {
            val b = l.boundingBox!!
            val g = groups.lastOrNull()
            if (g != null) {
                val gb = g.first().boundingBox!!
                if (abs(gb.centerY() - b.centerY()) < max(gb.height(), b.height()) * 0.5) {
                    g += l; continue
                }
            }
            groups += mutableListOf(l)
        }
        return groups.map { g ->
            val s = g.sortedBy { it.boundingBox!!.left }
            val r = Rect(s.first().boundingBox!!)
            s.forEach { r.union(it.boundingBox!!) }
            Row(s.joinToString(" ") { it.text }, r)
        }
    }

    fun allText(text: Text) = text.text.lowercase()

    // "(7x) Name +1%" – tolerates a checkbox glyph before it and OCR slips like "(7X" / "(lx)".
    private val countRe = Regex("""^[^0-9(]{0,4}\(\s*([0-9OoIl|]{1,6})\s*[xX×]\s*\)?\s*(.*)$""")

    fun entries(rows: List<Row>): List<Entry> = rows.mapNotNull { row ->
        val m = countRe.find(row.text.trim()) ?: return@mapNotNull null
        val digits = m.groupValues[1]
            .replace('O', '0').replace('o', '0')
            .replace('I', '1').replace('l', '1').replace('|', '1')
        val n = digits.toIntOrNull() ?: return@mapNotNull null
        val name = m.groupValues[2].substringBefore('+').trim()
        if (name.isEmpty()) null else Entry(n, name)
    }

    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Count for each rule name, best fuzzy match among entries (0 if not found). */
    fun countsFor(entries: List<Entry>, names: List<String>): Map<String, Int> =
        names.associateWith { name ->
            val target = norm(name)
            entries.map { it to similarity(norm(it.name), target) }
                .filter { it.second >= 0.8 }
                .maxByOrNull { it.second }?.first?.count ?: 0
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

    /** Reads the gem counter: the OCR element closest to the calibrated point. */
    fun gemsNear(text: Text, p: Point): Long? {
        val elements = text.textBlocks.flatMap { b -> b.lines.flatMap { it.elements } }
            .filter { it.boundingBox != null }
        val best = elements
            .filter { it.text.any { c -> c.isDigit() } }
            .minByOrNull { e ->
                val b = e.boundingBox!!
                val dx = max(0f, max(b.left - p.x, p.x - b.right))
                val dy = max(0f, max(b.top - p.y, p.y - b.bottom))
                dx * dx + dy * dy
            } ?: return null
        val b = best.boundingBox!!
        val dx = max(0f, max(b.left - p.x, p.x - b.right))
        val dy = max(0f, max(b.top - p.y, p.y - b.bottom))
        if (dx > 250 || dy > 80) return null
        val cleaned = best.text.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        val m = numRe.find(cleaned) ?: return null
        val base = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val exp = m.groupValues[2].toIntOrNull() ?: 0
        val v = base * Math.pow(10.0, exp.toDouble())
        return if (v > Long.MAX_VALUE / 2) Long.MAX_VALUE / 2 else v.toLong()
    }
}
