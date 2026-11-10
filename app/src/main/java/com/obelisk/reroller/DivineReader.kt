package com.obelisk.reroller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/**
 * Reads the Divine (yellow) rows of the results popup shown after "Open All":
 *
 *   (7x) Rainbow Floor Chance +1%
 *   (4x) Supernova Multipliers +2x
 *   …
 *   Tap anywhere to close
 *
 * Robustness:
 *  - rows are located by their yellow pixels (not by OCR), directly above "Tap … close"
 *  - each row is cropped, binarised (yellow -> black on white), upscaled and OCR'd at
 *    several scales; plus the plain full-screen OCR line => several independent votes
 *  - the relic is identified by fuzzy name match, falling back to the fixed row order
 */
object DivineReader {

    class Result(
        val popupFound: Boolean,
        val closePoint: Point?,
        /** count votes per relic name */
        val votes: Map<String, List<Int>>,
        val rows: Int,
    )

    /** Game order of Divine relics (custom names are appended after these). */
    fun knownNames(ruleNames: List<String>): List<String> =
        (DIVINE_PRESETS + ruleNames).distinctBy { OcrParser.norm(it) }

    private fun isYellow(c: Int, loose: Boolean): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return if (loose) r > 130 && g > r * 0.72 && b < r * 0.62 && g > 100
        else r > 160 && g > r * 0.80 && b < r * 0.55 && g > 130
    }

    private class Band(var top: Int, var bottom: Int) {
        val h get() = bottom - top + 1
    }

    /** Finds horizontal bands of yellow text in [yFrom, yTo). */
    private fun yellowBands(bmp: Bitmap, yFrom: Int, yTo: Int): List<Band> {
        val w = bmp.width
        val row = IntArray(w)
        val minPixels = maxOf(12, w / 90)
        val bands = mutableListOf<Band>()
        var cur: Band? = null
        var gap = 0
        for (y in yFrom until yTo) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var n = 0
            for (c in row) if (isYellow(c, false)) n++
            if (n >= minPixels) {
                if (cur == null) cur = Band(y, y) else cur.bottom = y
                gap = 0
            } else if (cur != null) {
                gap++
                if (gap > 2) { bands += cur; cur = null; gap = 0 }
            }
        }
        cur?.let { bands += it }
        val real = bands.filter { it.h >= 6 }
        if (real.isEmpty()) return real
        // Split bands that merged (e.g. an icon bridging two rows).
        val med = real.map { it.h }.sorted()[real.size / 2]
        return real.flatMap { b ->
            val k = Math.round(b.h.toDouble() / med).toInt()
            if (k <= 1 || b.h < med * 1.7) listOf(b)
            else (0 until k).map { i -> Band(b.top + b.h * i / k, b.top + b.h * (i + 1) / k - 1) }
        }
    }

    private fun xExtent(bmp: Bitmap, b: Band): Pair<Int, Int>? {
        val w = bmp.width
        val cols = IntArray(w)
        val row = IntArray(w)
        for (y in b.top..b.bottom) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if (isYellow(row[x], false)) cols[x]++
        }
        val xs = (0 until w).filter { cols[it] >= 2 }
        if (xs.isEmpty()) return null
        return xs.first() to xs.last()
    }

    private fun binarised(bmp: Bitmap, r: Rect, scale: Int, loose: Boolean): Bitmap {
        val w = r.width(); val h = r.height()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, r.left, r.top, w, h)
        for (i in px.indices) px[i] = if (isYellow(px[i], loose)) Color.BLACK else Color.WHITE
        val small = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(small, w * scale, h * scale, false)
        val pad = 24 * scale
        val out = Bitmap.createBitmap(big.width + pad * 2, big.height + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(big, pad.toFloat(), pad.toFloat(), null) }
        return out
    }

    private val wordRe = Regex("[A-Za-z]{3,}")
    private val countRe = Regex("""([0-9OoIlS|]{1,4})\s*[xX×]""")

    /** Parses "(7x) Rainbow Floor Chance +1%" -> (7, index of best name). */
    fun parse(text: String, names: List<String>): Pair<Int?, Int?> {
        val t = text.replace('\n', ' ')
        val firstWord = wordRe.find(t)?.range?.first ?: t.length
        val count = countRe.find(t.substring(0, firstWord))?.groupValues?.get(1)
            ?.let { OcrParser.digits(it) }?.toIntOrNull()?.takeIf { it in 0..9999 }
        val area = OcrParser.norm(t.substring(firstWord).substringBefore('+'))
        val scores = names.map { OcrParser.windowMatch(area, OcrParser.norm(it)) }
        val best = scores.indices.maxByOrNull { scores[it] }
        val bestScore = best?.let { scores[it] } ?: 0.0
        val second = scores.filterIndexed { i, _ -> i != best }.maxOrNull() ?: 0.0
        val idx = if (best != null && bestScore >= 0.72 && bestScore - second >= 0.08) best else null
        return count to idx
    }

    suspend fun read(
        bmp: Bitmap,
        lines: List<OLine>,
        names: List<String>,
        ocr: suspend (Bitmap) -> Text,
    ): Result {
        val marker = lines.filter {
            val s = it.text.lowercase()
            "close" in s && ("tap" in s || "anywhere" in s)
        }.maxByOrNull { it.box.top }
            ?: return Result(false, null, emptyMap(), 0)
        val close = Point(marker.box.exactCenterX(), marker.box.exactCenterY())

        // Yellow rows directly above the marker (popup bottom), at most 6, contiguous.
        val yTo = marker.box.top
        val yFrom = maxOf(0, yTo - (bmp.height * 0.25).toInt())
        val all = yellowBands(bmp, yFrom, yTo)
        val rows = mutableListOf<Band>()
        for (b in all.asReversed()) {
            val ref = rows.lastOrNull()
            val limit = (ref?.h ?: b.h) * 3
            val gapToPrev = (ref?.top ?: yTo) - b.bottom
            if (gapToPrev > limit) break
            rows += b
            if (rows.size >= 6) break
        }
        rows.reverse()

        // Votes per row: counts and name indices.
        val rowCounts = List(rows.size) { mutableListOf<Int>() }
        val rowNames = List(rows.size) { mutableListOf<Int>() }
        rows.forEachIndexed { i, b ->
            val ext = xExtent(bmp, b) ?: return@forEachIndexed
            val rect = Rect(
                maxOf(0, ext.first - 12), maxOf(0, b.top - 5),
                minOf(bmp.width, ext.second + 12), minOf(bmp.height, b.bottom + 6),
            )
            val texts = mutableListOf<String>()
            for ((scale, loose) in listOf(3 to false, 2 to false, 4 to true)) {
                runCatching { texts += ocr(binarised(bmp, rect, scale, loose)).text }
            }
            // Plain full-screen OCR text on the same row.
            val cy = (b.top + b.bottom) / 2
            texts += lines.filter { cy in it.box.top..it.box.bottom }
                .sortedBy { it.box.left }.joinToString(" ") { it.text }
            texts.forEach { t ->
                val (c, n) = parse(t, names)
                if (c != null) rowCounts[i] += c
                if (n != null) rowNames[i] += n
            }
        }

        // Resolve name per row by majority; fill gaps by game order.
        val resolved = rowNames.map { v -> v.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
            .toMutableList()
        for (i in resolved.indices) {
            if (resolved[i] != null) continue
            val prev = (i - 1 downTo 0).firstNotNullOfOrNull { resolved[it] } ?: -1
            val next = (i + 1 until resolved.size).firstNotNullOfOrNull { resolved[it] } ?: names.size
            val cand = prev + 1
            if (cand < next && cand !in resolved) resolved[i] = cand
        }

        val votes = mutableMapOf<String, MutableList<Int>>()
        resolved.forEachIndexed { i, n ->
            if (n != null) votes.getOrPut(names[n]) { mutableListOf() } += rowCounts[i]
        }
        return Result(true, close, votes, rows.size)
    }

    /** Majority value with ≥2 votes that beats every other value, else null. */
    fun consensus(v: List<Int>?): Int? {
        if (v.isNullOrEmpty()) return null
        val freq = v.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top = freq[0]
        val second = freq.getOrNull(1)?.value ?: 0
        return if (top.value >= 2 && top.value > second) top.key else null
    }
}
