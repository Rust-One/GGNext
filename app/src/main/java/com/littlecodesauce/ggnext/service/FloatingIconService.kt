package com.littlecodesauce.ggnext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.littlecodesauce.ggnext.MainActivity
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.ui.theme.AppFontCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮球 + 覆盖面板前台服务。
 * Bug 2 修复（对齐图2）：
 * 当长按悬浮球展开/收起左右减速 `<<`、加速 `>>` 与底部倍率标签 `1.0x` 时，
 * 立即调用 `refreshBallWindowLayout()` 执行 `wm.updateViewLayout(root, lp)`（同步 + post 双保险），
 * 确保系统 WindowManager 重新计算并扩展悬浮窗 Surface 宽高，彻底解决长按变速后悬浮窗被裁剪在旧 48dp 区域内导致显示不全的问题。
 */
class FloatingIconService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var ballRoot: LinearLayout? = null
    private var ball: HotIconView? = null
    private var speedDecBtn: TextView? = null
    private var speedIncBtn: TextView? = null
    private var speedLabel: TextView? = null
    private var panelRoot: FrameLayout? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShown = false
    private var panelCompose: ComposePanel? = null

    override fun onCreate() {
        super.onCreate()
        Session.init(application)
        OverlayState.running = true
        startForegroundNotification()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildBall()
        observeSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OverlayState.running = true
        if (ballRoot == null) {
            buildBall()
        }
        when (intent?.action) {
            ACTION_TOGGLE_PANEL -> togglePanel()
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_HIDE_PANEL -> hidePanel()
        }
        return START_STICKY
    }

    /**
     * Bug 2 核心修复：
     * 当子视图（`<<` / `>>` / `1.0x`）从 GONE 切换为 VISIBLE 或尺寸改变时，
     * 必须通知 WindowManager 更新窗口 LayoutParams，否则 Android WindowManager 会沿用初始 48dp 的窗口缓冲区导致左右按钮被截断。
     */
    private fun refreshBallWindowLayout() {
        val root = ballRoot ?: return
        val lp = ballParams ?: return
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        root.requestLayout()
        root.invalidate()
        if (OverlayState.ballAttachedToWindow) {
            runCatching { wm.updateViewLayout(root, lp) }
            root.post {
                root.requestLayout()
                root.invalidate()
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
    }

    private fun observeSessionState() {
        serviceScope.launch {
            Session.ui
                .map { it.panelVisible }
                .distinctUntilChanged()
                .collect { visible ->
                    if (visible && !panelShown) {
                        showPanel()
                    } else if (!visible && panelShown) {
                        hidePanel()
                    }
                    ballRoot?.visibility = if (visible) View.GONE else View.VISIBLE
                    if (!visible) {
                        refreshBallWindowLayout()
                    }
                }
        }
        serviceScope.launch {
            Session.ui
                .map { it.settings["icons_size"]?.toIntOrNull() ?: 48 }
                .distinctUntilChanged()
                .collect { sizeDp ->
                    val px = dp(sizeDp.coerceIn(28, 84))
                    val b = ball ?: return@collect
                    b.layoutParams = LinearLayout.LayoutParams(px, px)
                    refreshBallWindowLayout()
                }
        }
        serviceScope.launch {
            Session.ui
                .map {
                    listOf(
                        it.speedBarExpanded,
                        it.speedText,
                        it.preset,
                        it.darkMode,
                        it.dynamicColor,
                        it.speedMultiplier,
                        it.customFontPath
                    )
                }
                .distinctUntilChanged()
                .collect {
                    val cur = Session.ui.value
                    val expanded = cur.speedBarExpanded
                    val speedTxt = cur.speedText
                    val preset = cur.preset
                    val darkMode = cur.darkMode
                    val customTf = AppFontCache.typefaceFor(cur.customFontPath)
                    val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val isDark = when (darkMode) {
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.LIGHT -> false
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.DARK -> true
                        com.littlecodesauce.ggnext.ui.theme.DarkMode.FOLLOW -> isNight
                    }
                    val accentColorInt = if (cur.dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        runCatching {
                            val resId = if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                            getColor(resId)
                        }.getOrElse {
                            val c = preset.seedFor(isDark)
                            (c.value shr 32).toInt()
                        }
                    } else {
                        val c = preset.seedFor(isDark)
                        (c.value shr 32).toInt()
                    }
                    ball?.setThemeAccent(accentColorInt, isDark)
                    ball?.setCustomTypeface(customTf)

                    val btnBgColor = if (isDark) Color.parseColor("#EE221C20") else Color.parseColor("#EEFFFFFF")
                    val btnBorderColor = (accentColorInt and 0x00FFFFFF) or (if (isDark) (0x88 shl 24) else (0xBB shl 24))
                    val btnTextColor = if (isDark) Color.parseColor("#F2E8EC") else Color.parseColor("#121217")

                    val boldTf = customTf?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
                    val monoTf = customTf ?: Typeface.MONOSPACE

                    speedDecBtn?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedDecBtn?.setTextColor(btnTextColor)
                    speedDecBtn?.typeface = boldTf

                    speedIncBtn?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedIncBtn?.setTextColor(btnTextColor)
                    speedIncBtn?.typeface = boldTf

                    speedLabel?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(btnBgColor)
                        setStroke(dp(1), btnBorderColor)
                    }
                    speedLabel?.setTextColor(btnTextColor)
                    speedLabel?.typeface = monoTf

                    speedDecBtn?.visibility = if (expanded) View.VISIBLE else View.GONE
                    speedIncBtn?.visibility = if (expanded) View.VISIBLE else View.GONE
                    val showBadge = expanded || cur.speedMultiplier != 1.0f
                    speedLabel?.visibility = if (showBadge) View.VISIBLE else View.GONE
                    speedLabel?.text = speedTxt
                    refreshBallWindowLayout()
                }
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "GGNext 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n: Notification = builder
            .setContentTitle("GGNext 正在运行")
            .setContentText("短按悬浮球打开修改器，长按悬浮球调节变速")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFY_ID, n) }
    }

    private fun overlayType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun makeStepButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            isSingleLine = true
            includeFontPadding = false
            minWidth = dp(40)
            minimumHeight = dp(32)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EE221C20"))
                setStroke(dp(1), Color.parseColor("#88E7A8B8"))
            }
            visibility = View.GONE
            setOnClickListener { onClick() }
        }
    }

    private fun buildBall() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val decBtn = makeStepButton("<<") { Session.stepSpeed(false) }.apply {
            setOnLongClickListener {
                Session.resetSpeed()
                true
            }
        }
        val incBtn = makeStepButton(">>") { Session.stepSpeed(true) }.apply {
            setOnLongClickListener {
                Session.setSpeedBarExpanded(false)
                true
            }
        }

        val iconView = HotIconView(this)
        val sizeDp = Session.setting("icons_size").toIntOrNull()?.coerceIn(28, 84) ?: 48

        topRow.addView(
            decBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(5) }
        )
        topRow.addView(iconView, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        topRow.addView(
            incBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
        )

        val badge = TextView(this).apply {
            text = "1.0x"
            isSingleLine = true
            includeFontPadding = false
            minWidth = dp(44)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#EE221C20"))
                setStroke(dp(1), Color.parseColor("#88E7A8B8"))
            }
            visibility = View.GONE
            setOnClickListener {
                Session.resetSpeed()
            }
            setOnLongClickListener {
                Session.setSpeedBarExpanded(false)
                true
            }
        }

        container.addView(
            topRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        )

        var longPressTriggered = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressTriggered = true
                iconView.isPressedState = false
                iconView.invalidate()
                Session.toggleSpeedBar()
                refreshBallWindowLayout()
            }
        }

        val touchListener = View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = ballParams?.x ?: 16
                    startY = ballParams?.y ?: 320
                    dragging = false
                    longPressTriggered = false
                    iconView.isPressedState = true
                    iconView.invalidate()
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, 420L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        ballParams?.let { lp ->
                            lp.x = (startX + dx.toInt()).coerceAtLeast(0)
                            lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                            runCatching { wm.updateViewLayout(container, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    iconView.isPressedState = false
                    iconView.invalidate()
                    if (!dragging && !longPressTriggered) {
                        v.performClick()
                        showPanel()
                    }
                    dragging = false
                    longPressTriggered = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    iconView.isPressedState = false
                    iconView.invalidate()
                    dragging = false
                    longPressTriggered = false
                    true
                }
                else -> false
            }
        }
        iconView.setOnTouchListener(touchListener)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }
        ballRoot = container
        ball = iconView
        speedDecBtn = decBtn
        speedIncBtn = incBtn
        speedLabel = badge
        ballParams = lp
        val added = runCatching { wm.addView(container, lp) }
            .onFailure { AppLog.add("overlay", "ball addView failed: ${it.message}") }
            .isSuccess
        OverlayState.ballAttachedToWindow = added
        if (Session.ui.value.panelVisible) {
            container.visibility = View.GONE
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    private fun togglePanel() {
        if (panelShown) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelShown) {
            ballRoot?.visibility = View.GONE
            return
        }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (!Session.popSheet()) {
                        hidePanel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val compose = ComposePanel(this) { hidePanel() }
        compose.attach(root)

        // 严格避让顶部系统状态栏与刘海区域：移除 FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS，
        // 同时监听 WindowInsets 按窗口实际屏幕 Y 坐标动态补足顶部安全留白（绝不双倍叠加）
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val requiredTop = maxOf(sysBars.top, cutout.top)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val windowTopY = loc[1].coerceAtLeast(0)
            val extraTop = (requiredTop - windowTopY).coerceAtLeast(0)
            if (v.paddingTop != extraTop) {
                v.setPadding(0, extraTop, 0, 0)
            }
            insets
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dimAmount = 0f
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                fitInsetsTypes = android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.displayCutout()
            }
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val added = runCatching { wm.addView(root, lp) }
            .onFailure { e ->
                AppLog.add("overlay", "panel addView error: ${e.javaClass.simpleName}: ${e.message}")
                compose.detach()
            }
            .isSuccess

        ballRoot?.visibility = View.GONE

        if (added) {
            panelRoot = root
            panelCompose = compose
            panelParams = lp
            panelShown = true
            OverlayState.panelAttachedToWindow = true
            Session.setPanelVisible(true)
            root.post { ViewCompat.requestApplyInsets(root) }
        } else {
            OverlayState.panelAttachedToWindow = false
            Session.setPanelVisible(true)
        }
    }

    private fun hidePanel() {
        ballRoot?.visibility = View.VISIBLE
        refreshBallWindowLayout()
        if (!panelShown) {
            Session.setPanelVisible(false)
            return
        }
        panelShown = false
        OverlayState.panelAttachedToWindow = false
        Session.setPanelVisible(false)
        ball?.showProgress = false
        ball?.invalidate()
        runCatching {
            panelCompose?.detach()
            panelRoot?.let { wm.removeView(it) }
        }
        panelCompose = null
        panelRoot = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        OverlayState.running = false
        OverlayState.ballAttachedToWindow = false
        OverlayState.panelAttachedToWindow = false
        runCatching {
            hidePanel()
            ballRoot?.let { wm.removeView(it) }
        }
        ballRoot = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class HotIconView(context: Context) : View(context) {
        private val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            color = Color.parseColor("#E7A8B8")
        }
        private val innerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#99E7A8B8")
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE1F191D")
        }
        private val bgPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5382730")
        }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2E8EC")
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        var showProgress = false
        var isPressedState = false
        private val rect = RectF()

        fun setCustomTypeface(tf: Typeface?) {
            fg.typeface = tf?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
            invalidate()
        }

        fun setThemeAccent(accentColor: Int, isDark: Boolean = true) {
            outerRing.color = accentColor
            innerBorder.color = (accentColor and 0x00FFFFFF) or (if (isDark) (0x99 shl 24) else (0xCC shl 24))
            if (isDark) {
                bg.color = Color.parseColor("#EE1F191D")
                bgPressed.color = Color.parseColor("#F5382730")
                fg.color = Color.parseColor("#F2E8EC")
            } else {
                bg.color = Color.parseColor("#EEFFFFFF")
                bgPressed.color = Color.parseColor("#F0E6E9F0")
                fg.color = Color.parseColor("#1A1216")
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 4f
            canvas.drawCircle(cx, cy, r, if (isPressedState) bgPressed else bg)
            canvas.drawCircle(cx, cy, r, innerBorder)
            fg.textSize = r * 0.92f
            val baseline = cy - (fg.descent() + fg.ascent()) / 2f
            canvas.drawText("G", cx, baseline, fg)
        }
    }

    companion object {
        private const val CHANNEL = "ggnext_overlay"
        private const val NOTIFY_ID = 96
        const val ACTION_TOGGLE_PANEL = "com.littlecodesauce.ggnext.ACTION_TOGGLE_PANEL"
        const val ACTION_SHOW_PANEL = "com.littlecodesauce.ggnext.ACTION_SHOW_PANEL"
        const val ACTION_HIDE_PANEL = "com.littlecodesauce.ggnext.ACTION_HIDE_PANEL"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, FloatingIconService::class.java).apply {
                if (action != null) this.action = action
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    context.startForegroundService(i)
                else
                    context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingIconService::class.java))
            }
        }
    }
}
