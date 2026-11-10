package com.obelisk.reroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RerollService : AccessibilityService() {

    companion object {
        var instance: RerollService? = null
            private set
        private const val CHANNEL = "reroller"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var body: LinearLayout
    private var calibView: View? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reroller", NotificationManager.IMPORTANCE_HIGH)
        )
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        panel?.let { runCatching { wm.removeView(it) } }
        calibView?.let { runCatching { wm.removeView(it) } }
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay panel

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 20, 20, 30))
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val header = TextView(this).apply {
            text = "≡ Reroller (drag / tap)"
            setTextColor(Color.YELLOW)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "  ✕  "
            setTextColor(Color.RED)
            textSize = 16f
            setOnClickListener { hidePanel() }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header)
            addView(close)
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxWidth = dp(230)
            text = "Ready"
        }
        startBtn = btn("▶ Start") { if (job?.isActive == true) stopLoop("Stopped by you") else startLoop() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startBtn)
            addView(btn("Calib") { startCalibration() })
            addView(btn("Read") { testRead() })
        }
        body.addView(row)
        body.addView(status)
        root.addView(headerRow)
        root.addView(body)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 2
        }

        // Drag the header to move; a short tap toggles the body.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = panelParams.x; startY = panelParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) moved = true
                    panelParams.x = (startX + dx).toInt(); panelParams.y = (startY + dy).toInt()
                    wm.updateViewLayout(root, panelParams)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }
        wm.addView(root, panelParams)
        panel = root
    }

    fun hidePanel() {
        if (job?.isActive == true) stopLoop("Stopped (panel closed)")
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    /** Screen area covered by our own panel, so OCR ignores its text. */
    private fun panelRect(): Rect? {
        val p = panel ?: return null
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
    }

    private fun setStatus(s: String) {
        if (panel != null) status.text = s
    }

    private fun setKeepScreenOn(on: Boolean) {
        val p = panel ?: return
        panelParams.flags = if (on) panelParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else panelParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(p, panelParams)
    }

    // ---------------------------------------------------------------- calibration

    @SuppressLint("ClickableViewAccessibility")
    private fun startCalibration() {
        if (job?.isActive == true) { toast("Stop first"); return }
        if (calibView != null) return
        val steps = Spot.entries
        var i = 0
        val tv = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(120), dp(16), dp(16))
        }
        fun refresh() { tv.text = "Calibration ${i + 1}/${steps.size}\n${steps[i].hint}" }
        refresh()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                Config.savePoint(this, steps[i], Point(e.rawX, e.rawY))
                i++
                if (i >= steps.size) {
                    wm.removeView(tv); calibView = null
                    panel?.visibility = View.VISIBLE
                    setStatus("Calibration saved ✔")
                } else refresh()
            }
            true
        }
        panel?.visibility = View.GONE
        wm.addView(tv, params)
        calibView = tv
    }

    // ---------------------------------------------------------------- primitives

    private suspend fun tap(p: Point): Boolean = suspendCancellableCoroutine { c ->
        val path = Path().apply { moveTo(p.x, p.y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    private suspend fun screenshotOnce(): Bitmap = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp != null) c.resume(bmp) else c.resumeWithException(RuntimeException("Empty screenshot"))
            }
            override fun onFailure(code: Int) {
                c.resumeWithException(RuntimeException("Screenshot error $code"))
            }
        })
    }

    private suspend fun screenshot(): Bitmap {
        var last: Exception? = null
        repeat(6) {
            try { return screenshotOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = e; delay(400)
            }
        }
        throw last ?: RuntimeException("Screenshot failed")
    }

    private class Shot(val bmp: Bitmap, val lines: List<OLine>)

    private suspend fun ocrBitmap(bmp: Bitmap): Text = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    private suspend fun capture(): Shot {
        val bmp = screenshot()
        return Shot(bmp, OcrParser.lines(ocrBitmap(bmp), panelRect()))
    }

    /** Divine counts read from the results popup: votes merged over up to 3 screenshots. */
    private class DivineRead(val found: Boolean, val close: Point?, val votes: Map<String, List<Int>>, val rows: Int)

    private suspend fun readDivine(names: List<String>, waitMs: Long): DivineRead {
        val known = DivineReader.knownNames(names)
        val votes = mutableMapOf<String, MutableList<Int>>()
        var close: Point? = null
        var rows = 0
        val end = System.currentTimeMillis() + waitMs
        var reads = 0
        while (reads < 3) {
            val shot = capture()
            val r = DivineReader.read(shot.bmp, shot.lines, known, ::ocrBitmap)
            if (!r.popupFound) {
                if (reads == 0 && System.currentTimeMillis() < end) { delay(500); continue }
                break
            }
            reads++
            close = r.closePoint
            rows = maxOf(rows, r.rows)
            r.votes.forEach { (k, v) -> votes.getOrPut(k) { mutableListOf() } += v }
            if (names.all { DivineReader.consensus(votes[it]) != null }) break
            delay(300)
        }
        return DivineRead(reads > 0, close, votes, rows)
    }

    /** Average colour around a point: returns (r, g, b). */
    private fun colourAt(bmp: Bitmap, p: Point): Triple<Int, Int, Int> {
        var r = 0; var g = 0; var b = 0; var n = 0
        val cx = p.x.toInt(); val cy = p.y.toInt()
        for (dx in -12..12 step 3) for (dy in -12..12 step 3) {
            val x = cx + dx; val y = cy + dy
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            val c = bmp.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return Triple(0, 0, 0)
        return Triple(r / n, g / n, b / n)
    }

    private fun luma(bmp: Bitmap, p: Point): Int {
        val (r, g, b) = colourAt(bmp, p)
        return (r * 3 + g * 6 + b) / 10
    }

    /** Waits until Open All has finished (button turns dim again), up to 30 s. */
    private suspend fun waitOpenAllDone(idleLuma: Int, p: Point) {
        delay(800)
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            if (luma(screenshot(), p) <= idleLuma + 8) return
            delay(500)
        }
    }

    /** Reroll button is orange when the panel is open, red when closed. */
    private fun isPanelOpen(shot: Shot, p: Point): Boolean {
        val (r, g, _) = colourAt(shot.bmp, p)
        if (r > 150 && g > r * 0.35) return true
        val s = OcrParser.allText(shot.lines)
        return "refunds" in s || "open them again" in s
    }

    // ---------------------------------------------------------------- loop

    private fun startLoop() {
        val cfg = Config.load(this)
        if (!cfg.isCalibrated) { setStatus("Calibrate first (Calib)"); return }
        if (cfg.activeRules.isEmpty()) { setStatus("No target rules set in the app"); return }
        startBtn.text = "■ Stop"
        setKeepScreenOn(true)
        job = scope.launch {
            try {
                runLoop(cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish("Error: ${e.message}", alert = true)
            }
        }
    }

    private fun stopLoop(msg: String) {
        job?.cancel()
        job = null
        startBtn.text = "▶ Start"
        setKeepScreenOn(false)
        setStatus(msg)
    }

    private fun finish(msg: String, alert: Boolean) {
        stopLoop(msg)
        if (alert) alert(msg)
    }

    private suspend fun runLoop(cfg: Config) {
        val pt = cfg.points
        val names = cfg.activeRules.map { it.name }
        var rerolls = 0
        var lastGems: Long? = null
        var noChange = 0
        var lastResult = ""

        while (true) {
            if (rerolls >= cfg.maxRerolls) { finish("Max rerolls reached ($rerolls)\n$lastResult", true); return }

            // 1. Read gems; make sure the reroll panel is open (orange Reroll button).
            var shot = capture()
            val gems = OcrParser.gemsNear(shot.lines, pt.getValue(Spot.GEM_COUNTER))
            if (gems != null) {
                if (gems - 200 < cfg.minGems) { finish("Gem limit reached (💎$gems)\n$lastResult", true); return }
                if (lastGems != null && gems >= lastGems) {
                    noChange++
                    if (noChange >= 3) { finish("Gems not decreasing – refund not happening? Stopped.", true); return }
                } else noChange = 0
                lastGems = gems
            }
            if (!isPanelOpen(shot, pt.getValue(Spot.REROLL))) {
                tap(pt.getValue(Spot.REROLL)); delay(cfg.tapDelay)
                shot = capture()
                if (!isPanelOpen(shot, pt.getValue(Spot.REROLL))) {
                    finish("Could not open Reroll panel. Check calibration.", true); return
                }
            }

            // Open All is dim now (nothing to open) – remember that brightness.
            val openAllIdle = luma(shot.bmp, pt.getValue(Spot.OPEN_ALL))

            // 2. 💎200 -> confirm (second tap)
            setStatus("#${rerolls + 1}: rerolling…  💎${gems ?: "?"}\n$lastResult")
            tap(pt.getValue(Spot.GEM)); delay(cfg.confirmDelay)
            tap(pt.getValue(Spot.GEM)); delay(cfg.tapDelay)
            rerolls++

            // 3. Open All -> wait until the button is dim again (all opened), then extra wait
            tap(pt.getValue(Spot.OPEN_ALL))
            waitOpenAllDone(openAllIdle, pt.getValue(Spot.OPEN_ALL))
            delay(cfg.openAllDelay)

            // 4. Results popup (appears after Open All) -> read Divine counts -> close it
            val read = readDivine(names, 10_000)
            if (!read.found) { finish("Results popup not detected (#$rerolls). Raise the delays.", true); return }
            read.close?.let { tap(it) }; delay(cfg.tapDelay)

            // Decide. A count is "sure" when ≥2 independent reads agree. If unsure but any read
            // reaches the target, stop anyway so a good roll is never thrown away.
            var possible = false
            val hits = cfg.activeRules.map { rule ->
                val v = read.votes[rule.name]
                val c = DivineReader.consensus(v)
                when {
                    c != null -> c >= rule.min
                    v.isNullOrEmpty() -> false
                    v.max() >= rule.min -> { possible = true; true }
                    else -> false
                }
            }
            lastResult = "Last: " + cfg.activeRules.joinToString(", ") { r ->
                val v = read.votes[r.name]
                "${r.name} ${DivineReader.consensus(v) ?: v?.joinToString("/")?.let { "?($it)" } ?: 0}/${r.min}"
            }
            val ok = if (cfg.matchAll) hits.all { it } else hits.any { it }
            if (ok && possible) { finish("⚠ POSSIBLE HIT after $rerolls rerolls – read unsure, please check!\n$lastResult", true); return }
            if (ok) { finish("🎉 TARGET HIT after $rerolls rerolls!\n$lastResult", true); return }
            setStatus("#$rerolls done  💎${gems ?: "?"}\n$lastResult")
        }
    }

    /** Debug: read the current screen (results popup open) and show what was understood. */
    private fun testRead() {
        if (job?.isActive == true) return
        val cfg = Config.load(this)
        scope.launch {
            try {
                setStatus("Reading…")
                val shot = capture()
                val names = cfg.activeRules.map { it.name }.ifEmpty { DIVINE_PRESETS }
                val gems = cfg.points[Spot.GEM_COUNTER]?.let { OcrParser.gemsNear(shot.lines, it) }
                val panel = cfg.points[Spot.REROLL]?.let { isPanelOpen(shot, it) }
                val r = readDivine(DivineReader.knownNames(names), 0)
                setStatus(
                    "💎 $gems | panel open: $panel | popup: ${r.found} | rows: ${r.rows}\n" +
                        DivineReader.knownNames(names).joinToString("\n") { n ->
                            val v = r.votes[n]
                            "$n: ${DivineReader.consensus(v) ?: "?"}  votes ${v ?: "-"}"
                        }
                )
            } catch (e: Exception) {
                setStatus("Read error: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- alerts

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun alert(msg: String) {
        runCatching {
            val v = getSystemService(VibratorManager::class.java).defaultVibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1))
        }
        runCatching {
            val n = android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle("Obelisk Reroller")
                .setContentText(msg.lineSequence().first())
                .setStyle(android.app.Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        }
    }
}
