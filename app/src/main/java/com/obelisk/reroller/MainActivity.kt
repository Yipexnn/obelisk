package com.obelisk.reroller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val ruleViews = mutableListOf<Triple<CheckBox, EditText, EditText>>()
    private lateinit var matchAll: RadioButton
    private lateinit var maxRerolls: EditText
    private lateinit var minGems: EditText
    private lateinit var tapDelay: EditText
    private lateinit var confirmDelay: EditText
    private lateinit var openAllDelay: EditText
    private lateinit var serviceState: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val cfg = Config.load(this)

        header("Setup")
        text(
            "1. App info → ⋮ → 'Allow restricted settings' (needed for side-loaded apps)\n" +
                "2. Enable 'Obelisk Reroller' in Accessibility\n" +
                "3. In the game open the Reroll panel (Divine slots visible), press 'Calib' on the floating panel and tap each button as asked\n" +
                "4. Set targets below → Save → press ▶ Start on the floating panel"
        )
        serviceState = text("")
        row(
            button("App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            button("Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        root.addView(button("Show floating panel") {
            val svc = RerollService.instance
            if (svc == null) Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
            else { svc.showPanel(); Toast.makeText(this, "Panel shown", Toast.LENGTH_SHORT).show() }
        })

        header("Divine targets (count ≥ min)")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        matchAll = RadioButton(this).apply { text = "ALL must match"; id = View.generateViewId() }
        val matchAny = RadioButton(this).apply { text = "ANY matches"; id = View.generateViewId() }
        rg.addView(matchAll); rg.addView(matchAny)
        rg.check(if (cfg.matchAll) matchAll.id else matchAny.id)
        root.addView(rg)

        cfg.rules.forEach { r ->
            val cb = CheckBox(this).apply { isChecked = r.enabled }
            val name = EditText(this).apply {
                setText(r.name); hint = "Custom divine relic name"; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val min = numberField(r.min.toString()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(cb); addView(name); addView(min)
            })
            ruleViews += Triple(cb, name, min)
        }

        header("Safety limits")
        maxRerolls = labeled("Max rerolls per run", cfg.maxRerolls.toString())
        minGems = labeled("Stop when gems below", cfg.minGems.toString())

        header("Timing (ms) – raise if your phone lags")
        tapDelay = labeled("Wait after tap", cfg.tapDelay.toString())
        confirmDelay = labeled("Wait between 💎200 taps", cfg.confirmDelay.toString())
        openAllDelay = labeled("Wait after Open All (relics revealed)", cfg.openAllDelay.toString())

        root.addView(button("💾 Save") { save() })
    }

    override fun onResume() {
        super.onResume()
        val cfg = Config.load(this)
        serviceState.text =
            "Service: " + (if (RerollService.instance != null) "ON ✔" else "OFF ✘") +
                "   Calibration: " + (if (cfg.isCalibrated) "done ✔" else "missing ✘")
    }

    private fun save() {
        val old = Config.load(this)
        val rules = ruleViews.map { (cb, n, m) ->
            Rule(cb.isChecked, n.text.toString().trim(), m.text.toString().toIntOrNull() ?: 0)
        }
        Config(
            rules = rules,
            matchAll = matchAll.isChecked,
            maxRerolls = maxRerolls.text.toString().toIntOrNull() ?: old.maxRerolls,
            minGems = minGems.text.toString().toLongOrNull() ?: old.minGems,
            tapDelay = tapDelay.text.toString().toLongOrNull() ?: old.tapDelay,
            confirmDelay = confirmDelay.text.toString().toLongOrNull() ?: old.confirmDelay,
            openAllDelay = openAllDelay.text.toString().toLongOrNull() ?: old.openAllDelay,
            summaryDelay = old.summaryDelay,
            points = old.points,
        ).save(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- tiny view helpers

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setPadding(0, dp(16), 0, dp(4))
        root.addView(this)
    }

    private fun text(s: String) = TextView(this).apply { text = s; root.addView(this) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    })

    private fun numberField(v: String) = EditText(this).apply {
        setText(v); inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun labeled(label: String, v: String): EditText {
        val f = numberField(v).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(f)
        })
        return f
    }
}
