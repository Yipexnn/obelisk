package com.obelisk.reroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Rule(val enabled: Boolean, val name: String, val min: Int)

data class Point(val x: Float, val y: Float)

/** Calibration targets, in the order the user taps them. */
enum class Spot(val label: String, val hint: String) {
    REROLL("Reroll", "Tap the REROLL button"),
    GEM("💎 200", "Tap the 💎200 refund button (Reroll panel must be open)"),
    OPEN_ALL("Open All", "Tap the OPEN ALL button"),
    GEM_COUNTER("Gem counter", "Tap your 💎 gem counter (top right number)"),
}

val DIVINE_PRESETS = listOf(
    "Rainbow Floor Chance",
    "Supernova Multipliers",
    "Rainbow Floor Multi",
    "Fishing Tick 5x Chance",
)

data class Config(
    val rules: List<Rule>,
    val matchAll: Boolean,
    val maxRerolls: Int,
    val minGems: Long,
    val tapDelay: Long,
    val confirmDelay: Long,
    val openAllDelay: Long,
    val summaryDelay: Long,
    val points: Map<Spot, Point>,
) {
    val activeRules get() = rules.filter { it.enabled && it.name.isNotBlank() && it.min > 0 }
    val isCalibrated get() = Spot.entries.all { points.containsKey(it) }

    fun save(ctx: Context) {
        val arr = JSONArray()
        rules.forEach {
            arr.put(JSONObject().put("e", it.enabled).put("n", it.name).put("m", it.min))
        }
        val e = prefs(ctx).edit()
            .putString("rules", arr.toString())
            .putBoolean("matchAll", matchAll)
            .putInt("maxRerolls", maxRerolls)
            .putLong("minGems", minGems)
            .putLong("tapDelay", tapDelay)
            .putLong("confirmDelay", confirmDelay)
            .putLong("openAllDelay", openAllDelay)
            .putLong("summaryDelay", summaryDelay)
        points.forEach { (t, p) -> e.putFloat("pt_${t.name}_x", p.x).putFloat("pt_${t.name}_y", p.y) }
        e.apply()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

        fun load(ctx: Context): Config {
            val p = prefs(ctx)
            val rules = mutableListOf<Rule>()
            val raw = p.getString("rules", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    rules += Rule(o.optBoolean("e"), o.optString("n"), o.optInt("m"))
                }
            }
            if (rules.isEmpty()) {
                DIVINE_PRESETS.forEach { rules += Rule(false, it, 7) }
                repeat(2) { rules += Rule(false, "", 1) }
            }
            val points = mutableMapOf<Spot, Point>()
            Spot.entries.forEach { t ->
                if (p.contains("pt_${t.name}_x")) {
                    points[t] = Point(p.getFloat("pt_${t.name}_x", 0f), p.getFloat("pt_${t.name}_y", 0f))
                }
            }
            return Config(
                rules = rules,
                matchAll = p.getBoolean("matchAll", true),
                maxRerolls = p.getInt("maxRerolls", 100),
                minGems = p.getLong("minGems", 100_000),
                tapDelay = p.getLong("tapDelay", 700),
                confirmDelay = p.getLong("confirmDelay", 500),
                openAllDelay = p.getLong("openAllDelay", 2500),
                summaryDelay = p.getLong("summaryDelay", 1200),
                points = points,
            )
        }

        fun savePoint(ctx: Context, t: Spot, pt: Point) {
            prefs(ctx).edit().putFloat("pt_${t.name}_x", pt.x).putFloat("pt_${t.name}_y", pt.y).apply()
        }
    }
}
