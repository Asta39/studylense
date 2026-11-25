package app.studylens

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Foreground service that owns the floating bubble and everything that appears over other apps:
 * the action menu, the drag-to-select overlay, the "reading" pill and the answer sheet.
 */
class BubbleService : Service() {
    companion object {
        const val ACTION_SHOW = "show"
        const val ACTION_HIDE = "hide"
        const val CHANNEL = "bubble"
        var running = false
    }

    // Palette from the mockups
    private val BG = Color.parseColor("#ECECEE")
    private val CARD = Color.parseColor("#F7F7F8")
    private val INK = Color.parseColor("#1C1C1E")
    private val SUB = Color.parseColor("#55555A")
    private val LINE = Color.parseColor("#E2E2E5")

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val serif: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.agrave_pro) }

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var select: View? = null
    private var pill: View? = null
    private var sheet: View? = null


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        store = Store(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "StudyLens bubble", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { store.bubbleOn = false; stopSelf(); return START_NOT_STICKY }
            else -> { goForeground(false); showBubble() }
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        listOf(bubble, menu, select, pill, sheet).forEach { it?.let { v -> runCatching { wm.removeView(v) } } }
        super.onDestroy()
    }

    private fun goForeground(@Suppress("UNUSED_PARAMETER") unused: Boolean) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("StudyLens is floating")
            .setContentText("Tap the lens in any app to capture a question")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide bubble", stop).build())
            .setOngoing(true)
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    // ---------- helpers ----------

    // Same width-based scaling as the app, so overlays match the mockups on every screen.
    private val ui by lazy { uiScale(resources.displayMetrics.widthPixels / resources.displayMetrics.density) }
    private val fontScale by lazy { maxOf(resources.configuration.fontScale, 1f) }
    private fun dp(v: Float) = v * resources.displayMetrics.density * ui
    private fun TextView.sizeSp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(v) * fontScale)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()
    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r) }
    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun text(s: String, size: Float, color: Int = INK, bold: Boolean = false, serifFont: Boolean = false) =
        TextView(this).apply {
            text = s; setTextColor(color); sizeSp(size)
            typeface = if (serifFont) serif else Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (bold && !serifFont) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Cover the status bar and cutout too, so frozen screenshots line up with the real screen.
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun remove(v: View?) { v?.let { runCatching { wm.removeView(it) } } }

    private fun pillButton(label: String, dark: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER
        setTextColor(if (dark) Color.WHITE else INK)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        sizeSp(15f)
        background = round(if (dark) INK else CARD, 28f)
        setOnClickListener { onClick() }
    }

    // ---------- bubble ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) { bubble?.visibility = View.VISIBLE; return }
        val size = listOf(44, 52, 60)[store.bubbleSize]
        val tabW = dp(size + 14); val tabH = dp(size)
        val tab = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(INK); cornerRadii = FloatArray(8) { i -> if (i in 0..1 || i in 6..7) dp(size / 2f) else 0f } }
            elevation = dp(8f)
            contentDescription = "Open StudyLens"
        }
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.mark); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) } }
            clipToOutline = true
        }
        val inner = (tabH * 0.7f).toInt()
        tab.addView(mark, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(8) })

        val screenW = wm.currentWindowMetrics.bounds.width()
        val p = overlayParams(tabW, tabH).apply { x = screenW - tabW + dp(4); y = store.bubbleY }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tab.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > dp(6) || abs(e.rawY - downY) > dp(6)) moved = true
                    if (moved) { p.x = startX + (e.rawX - downX).toInt(); p.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    else { // snap to nearest edge, Goldfish style
                        val left = p.x + tabW / 2 < screenW / 2
                        p.x = if (left) -dp(4) else screenW - tabW + dp(4)
                        (v.background as GradientDrawable).cornerRadii = FloatArray(8) { i ->
                            val round = if (left) i in 2..5 else (i in 0..1 || i in 6..7); if (round) dp(size / 2f) else 0f }
                        (mark.layoutParams as FrameLayout.LayoutParams).apply {
                            gravity = Gravity.CENTER_VERTICAL or (if (left) Gravity.END else Gravity.START)
                            leftMargin = if (left) 0 else dp(8); rightMargin = if (left) dp(8) else 0
                        }
                        mark.requestLayout()
                        wm.updateViewLayout(v, p); store.bubbleY = p.y
                    }
                }
            }
            true
        }
        wm.addView(tab, p)
        bubble = tab; bubbleParams = p
    }

    // ---------- action menu ----------

    private fun toggleMenu() {
        if (menu != null) { remove(menu); menu = null; return }
        dismissSheet()
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(70, 20, 20, 22)); setOnClickListener { remove(this); menu = null } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(CARD, 26f); elevation = dp(16f)
            setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true
        }
        fun row(title: String, sub: String, dark: Boolean, iconDash: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = round(if (dark) Color.WHITE else CARD, 18f)
            addView(View(this@BubbleService).apply { background = icon(dark, iconDash) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@BubbleService).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                addView(text(title, 14f, bold = true)); addView(text(sub, 11f, SUB))
            })
            setOnClickListener { remove(scrim); menu = null; onClick() }
        }
        card.addView(row("Full screen", "Answer every question shown", true, false) { begin("full") })
        card.addView(row("One question", "Drag a box around it", false, true) { begin("one") })
        card.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        card.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            fun small(label: String, click: () -> Unit) = TextView(this@BubbleService).apply {
                text = label; gravity = Gravity.CENTER; setTextColor(INK); sizeSp(12f)
                background = round(LINE, 14f); setPadding(0, dp(10), 0, dp(10)); setOnClickListener { remove(scrim); menu = null; click() }
            }
            addView(small("Open app") { openApp(null) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            addView(small("Hide bubble") { store.bubbleOn = false; stopSelf() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val bp = bubbleParams!!
        val screenW = wm.currentWindowMetrics.bounds.width()
        val onLeft = bp.x < screenW / 2
        scrim.addView(card, FrameLayout.LayoutParams(dp(240), -2).apply {
            gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
            topMargin = maxOf(dp(40), bp.y - dp(70))
            if (onLeft) leftMargin = dp(80) else rightMargin = dp(80)
        })
        wm.addView(scrim, overlayParams(-1, -1))
        menu = scrim
        bubble?.bringToFront()
        remove(bubble); wm.addView(bubble, bp) // keep the bubble above the scrim
    }

    private fun icon(dark: Boolean, dashed: Boolean): android.graphics.drawable.Drawable {
        val fg = if (dark) Color.WHITE else INK
        val bg = if (dark) INK else LINE
        return object : android.graphics.drawable.Drawable() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: Canvas) {
                val b = bounds; p.style = Paint.Style.FILL; p.color = bg
                c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.8f); p.color = fg; p.strokeCap = Paint.Cap.ROUND
                val s = b.width() * 0.22f; val l = b.width() * 0.1f
                val r = RectF(b.exactCenterX() - s, b.exactCenterY() - s * 0.8f, b.exactCenterX() + s, b.exactCenterY() + s * 0.8f)
                if (dashed) { p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f); c.drawRoundRect(r, dp(2f), dp(2f), p); p.pathEffect = null }
                else listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (x, y) ->
                    val dx = if (x == r.left) l else -l; val dy = if (y == r.top) l else -l
                    c.drawLine(x, y, x + dx, y, p); c.drawLine(x, y, x, y + dy, p) }
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(f: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    private fun openApp(scanId: Long?) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (scanId != null) i.putExtra("scan", scanId)
        startActivity(i)
    }

    // ---------- capture ----------

    private fun begin(mode: String) {
        if (store.apiKey.isBlank()) { toast("Add your Groq API key in StudyLens first"); openApp(null); return }
        if (ShotService.instance == null) {
            toast("Turn on “StudyLens screenshots” in Accessibility so the lens can take screenshots")
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (mode == "full") startFull() else startOne()
    }

    /** Hides our overlays, takes one screenshot, then shows them again. */
    private fun grab(done: (Bitmap?) -> Unit) {
        val views = listOf(bubble, menu, pill, sheet)
        views.forEach { it?.visibility = View.INVISIBLE }
        main.postDelayed({
            val svc = ShotService.instance
            if (svc == null) { bubble?.visibility = View.VISIBLE; done(null); return@postDelayed }
            svc.shot { bmp -> bubble?.visibility = View.VISIBLE; done(bmp) }
        }, 200)
    }

    private fun startFull() = grab { bmp ->
        if (bmp == null) { toast("Couldn’t capture the screen — try again"); return@grab }
        ask(bmp, true)
    }

    private fun startOne() = grab { bmp ->
        if (bmp == null) { toast("Couldn’t capture the screen — try again"); return@grab }
        showSelect(bmp)
    }

    // ---------- drag to select ----------

    private inner class SelectView(val shot: Bitmap) : View(this@BubbleService) {
        var rect: RectF? = null
        private var sx = 0f; private var sy = 0f
        private val dim = Paint().apply { color = Color.argb(140, 20, 20, 22) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        var onChange: () -> Unit = {}

        override fun onDraw(c: Canvas) {
            c.drawBitmap(shot, null, Rect(0, 0, width, height), null)
            val r = rect
            if (r == null) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim); return }
            c.drawRect(0f, 0f, width.toFloat(), r.top, dim)
            c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
            c.drawRect(0f, r.top, r.left, r.bottom, dim)
            c.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
            c.drawRoundRect(r, dp(14f), dp(14f), stroke)
            listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (x, y) -> c.drawCircle(x, y, dp(7f), dot) }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; rect = RectF(sx, sy, sx, sy) }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    rect = RectF(minOf(sx, e.x), minOf(sy, e.y), maxOf(sx, e.x), maxOf(sy, e.y)); onChange()
                }
            }
            invalidate(); return true
        }
    }

    private fun showSelect(shot: Bitmap) {
        bubble?.visibility = View.INVISIBLE
        val root = FrameLayout(this)
        val sel = SelectView(shot)
        root.addView(sel, FrameLayout.LayoutParams(-1, -1))
        val hint = text("Drag a box around one question", 12f, bold = true).apply {
            background = round(CARD, 18f); setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(56) })
        val bar = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(36)) }
        val answer = pillButton("Answer this  →", true) {}.apply { alpha = 0.4f }
        bar.addView(pillButton("Cancel", false) { remove(root); select = null; bubble?.visibility = View.VISIBLE },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(10) })
        bar.addView(answer, LinearLayout.LayoutParams(0, dp(56), 1.4f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        sel.onChange = { val r = sel.rect; answer.alpha = if (r != null && r.width() > dp(40) && r.height() > dp(24)) 1f else 0.4f }
        answer.setOnClickListener {
            val r = sel.rect ?: return@setOnClickListener
            if (r.width() < dp(40) || r.height() < dp(24)) return@setOnClickListener
            val sxScale = shot.width / sel.width.toFloat(); val syScale = shot.height / sel.height.toFloat()
            val x = (r.left * sxScale).toInt().coerceIn(0, shot.width - 1)
            val y = (r.top * syScale).toInt().coerceIn(0, shot.height - 1)
            val w = (r.width() * sxScale).toInt().coerceIn(1, shot.width - x)
            val h = (r.height() * syScale).toInt().coerceIn(1, shot.height - y)
            remove(root); select = null; bubble?.visibility = View.VISIBLE
            ask(Bitmap.createBitmap(shot, x, y, w, h), false)
        }
        wm.addView(root, overlayParams(-1, -1).apply { flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE })
        select = root
    }

    // ---------- asking + answer sheet ----------

    private fun ask(img: Bitmap, full: Boolean) {
        showPill(if (full) "Reading the whole screen…" else "Reading the question…")
        thread {
            val result = runCatching { Groq(store).answer(img, full) }
            main.post {
                remove(pill); pill = null
                result.onSuccess { answers ->
                    val scan = Scan(System.currentTimeMillis(), full, answers)
                    thread { store.add(scan, img) }
                    showSheet(scan)
                }.onFailure { showError(it.message ?: "Something went wrong") }
            }
        }
    }

    private fun showPill(label: String) {
        remove(pill)
        val v = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(INK, 22f); setPadding(dp(16), dp(10), dp(18), dp(10)); elevation = dp(8f)
            addView(ProgressBar(this@BubbleService).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(text(label, 13f, Color.WHITE, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
        }
        val p = overlayParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        wm.addView(v, p); pill = v
    }

    private fun dismissSheet() { remove(sheet); sheet = null }

    private fun sheetFrame(build: (LinearLayout) -> Unit) {
        dismissSheet()
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(56, 20, 20, 22)); setOnClickListener { dismissSheet() } }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = GradientDrawable().apply { setColor(BG); cornerRadii = floatArrayOf(dp(32f), dp(32f), dp(32f), dp(32f), 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(10), dp(16), dp(28)); elevation = dp(20f)
        }
        panel.addView(View(this).apply { background = round(Color.parseColor("#C4C4C9"), 3f) }, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        build(panel)
        panel.addView(text(Disclaimer.SHORT, 11f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        val maxH = (wm.currentWindowMetrics.bounds.height() * 0.8f).toInt()
        scrim.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        panel.post { if (panel.height > maxH) panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = maxH } }
        wm.addView(scrim, overlayParams(-1, -1))
        sheet = scrim
        remove(bubble); bubbleParams?.let { wm.addView(bubble, it) }
    }

    private fun header(panel: LinearLayout, title: View) {
        panel.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(4), dp(8))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@BubbleService).apply {
                text = "✕"; gravity = Gravity.CENTER; setTextColor(INK); background = round(CARD, 18f); contentDescription = "Close"
                setOnClickListener { dismissSheet() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        })
    }

    private fun letterCircle(letter: String, sizeDp: Int, textSp: Float) = TextView(this).apply {
        text = letter; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = serif
        sizeSp(if (letter.length > 2) textSp * 0.7f else textSp)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INK) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun showSheet(scan: Scan) {
        if (!scan.fullScreen && scan.answers.size == 1) showSingle(scan) else showMulti(scan)
    }

    /** The answer itself: a letter badge for multiple choice, or a clean answer box for anything else. */
    private fun answerBlock(a: Answer, index: Int, showNumber: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(CARD, 22f); setPadding(dp(16), dp(16), dp(16), dp(16))
        val label = buildString {
            if (showNumber) append("QUESTION ${a.number.ifBlank { "${index + 1}" }}")
            if (showNumber && a.type == "work") append(" · WORKED") 
        }
        if (label.isNotBlank()) addView(text(label, 10.5f, SUB, bold = true).apply { letterSpacing = 0.07f; setPadding(0, 0, 0, dp(4)) })
        if (a.question.isNotBlank()) addView(text(a.question, 12.5f, SUB).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(10)) })

        if (a.isLetter) {
            addView(LinearLayout(this@BubbleService).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(letterCircle(a.answer, 44, 22f))
                addView(text(a.answerText.ifBlank { "Option ${a.answer}" }, 20f, serifFont = true).apply { setPadding(dp(12), 0, 0, 0) })
            })
        } else {
            addView(LinearLayout(this@BubbleService).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Color.WHITE, 16f); setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text("ANSWER", 10f, SUB, bold = true).apply { letterSpacing = 0.08f; setPadding(0, 0, 0, dp(6)) })
                a.parts.ifEmpty { listOf(a.answer) }.forEach { part ->
                    addView(text(part, 15f, bold = true).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(2), 0, dp(2)) })
                }
            })
        }

        if (a.steps.isNotEmpty()) {
            addView(text("WORKING", 10f, SUB, bold = true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(4)) })
            a.steps.forEachIndexed { i, st ->
                addView(LinearLayout(this@BubbleService).apply {
                    setPadding(0, dp(3), 0, dp(3))
                    addView(text("${i + 1}", 11f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(INK) }
                    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(1) })
                    addView(text(st, 13f, Color.parseColor("#3A3A3F")).apply { setLineSpacing(0f, 1.25f); setPadding(dp(10), 0, 0, 0) })
                })
            }
        }
        if (a.explanation.isNotBlank()) {
            addView(View(this@BubbleService).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(12); bottomMargin = dp(10) })
            addView(text(a.explanation, 13f, SUB).apply { setLineSpacing(0f, 1.3f) })
        }
    }

    private fun copyText(a: Answer) = buildString {
        if (a.question.isNotBlank()) appendLine(a.question)
        append(a.answer); if (a.answerText.isNotBlank()) append(" — ${a.answerText}")
        a.steps.forEachIndexed { i, st -> append("\n${i + 1}. $st") }
        if (a.explanation.isNotBlank()) append("\n${a.explanation}")
    }

    private fun scroller(content: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }

    private fun showSingle(scan: Scan) = sheetFrame { panel ->
        val a = scan.answers.first()
        header(panel, text("ONE QUESTION", 11f, SUB, bold = true).apply { letterSpacing = 0.08f })
        panel.addView(scroller(answerBlock(a, 0, showNumber = false)), LinearLayout.LayoutParams(-1, 0, 1f).apply { height = -2 })
        panel.addView(LinearLayout(this).apply {
            setPadding(0, dp(12), 0, 0)
            addView(pillButton("Copy", false) { copy(copyText(a)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
            addView(pillButton("Next question  →", true) { dismissSheet(); begin("one") }, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        })
    }

    private fun showMulti(scan: Scan) = sheetFrame { panel ->
        val n = scan.answers.size
        header(panel, text("$n answer${if (n == 1) "" else "s"} found", 24f, serifFont = true))
        panel.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@BubbleService).apply {
                setPadding(dp(4), 0, 0, dp(10))
                scan.answers.forEachIndexed { i, a ->
                    val short = if (a.isLetter) a.answer else if (a.type == "work") "worked" else a.answer.take(14)
                    addView(text("${a.number.ifBlank { "${i + 1}" }} · $short", 11.5f, Color.WHITE, bold = true).apply {
                        background = round(INK, 14f); setPadding(dp(11), dp(6), dp(11), dp(6))
                    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                }
            })
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scan.answers.forEachIndexed { i, a -> list.addView(answerBlock(a, i, showNumber = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        panel.addView(scroller(list), LinearLayout.LayoutParams(-1, 0, 1f).apply { height = -2 })
        panel.addView(LinearLayout(this).apply {
            setPadding(0, dp(8), 0, 0)
            addView(pillButton("Open in app", false) { dismissSheet(); openApp(scan.id) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
            addView(pillButton("Scan again  ↻", true) { dismissSheet(); begin("full") }, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        })
    }

    private fun showError(msg: String) = sheetFrame { panel ->
        header(panel, text("Something went wrong", 26f, serifFont = true))
        panel.addView(text(msg, 14f, SUB).apply { background = round(CARD, 22f); setPadding(dp(16), dp(16), dp(16), dp(16)) })
        panel.addView(pillButton("Close", true) { dismissSheet() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("StudyLens", s))
        toast("Copied")
    }
}
