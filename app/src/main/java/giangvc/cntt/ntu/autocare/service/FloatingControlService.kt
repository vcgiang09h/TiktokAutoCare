package giangvc.cntt.ntu.autocare.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import giangvc.cntt.ntu.autocare.MainActivity
import giangvc.cntt.ntu.autocare.R
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager.AutoState
import giangvc.cntt.ntu.autocare.model.FarmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Foreground Service quản lý popup bảng điều khiển nổi (Floating Control Widget).
 *
 * Chức năng:
 *   - Hiển thị popup card nổi kéo thả tự do trên màn hình (WindowManager)
 *   - Đồng hồ đếm thời gian thực (⏱ Thời gian: 00:00 hoặc Còn lại theo hẹn giờ)
 *   - Trạng thái trực quan theo thời gian thực (⚡ Trạng thái: Đang xem, tìm kiếm, bỏ qua...)
 *   - Thống kê chi tiết (📊 Đã xem: X video • Y tim • Z cmt)
 *   - Nút Thu nhỏ/Mở rộng (— / +) để người dùng thoải mái xem video TikTok
 *   - Nút Play/Pause & Nút Đóng (✕)
 */
class FloatingControlService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "autocare_floating_channel"
        private const val CLICK_DRAG_TOLERANCE = 12f
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    // Views inside floating popup
    private var layoutCard: View? = null
    private var layoutMinimizedIcon: View? = null
    private var layoutHeader: View? = null
    private var layoutBody: View? = null
    private var btnMinimize: TextView? = null
    private var btnPlayPause: ImageButton? = null
    private var btnClose: TextView? = null
    private var tvTimer: TextView? = null
    private var tvStatus: TextView? = null
    private var tvStats: TextView? = null

    // Live Timer Ticker
    private var elapsedSeconds: Long = 0L
    private var tickerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForegroundSafe()
        initFloatingWidget()
        observeAutomationState()
        AutomationStateManager.setFloatingServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker(reset = true)
        serviceScope.cancel()
        removeFloatingWidget()
        AutomationStateManager.setFloatingServiceRunning(false)
    }

    // ══════════════════════════════════════════════════════════
    //  Foreground Service & Notification (Safe Mode)
    // ══════════════════════════════════════════════════════════

    private fun startInForegroundSafe() {
        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    0
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ══════════════════════════════════════════════════════════
    //  Floating Widget UI & WindowManager
    // ══════════════════════════════════════════════════════════

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingWidget() {
        if (floatingView != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_AutoCare)
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_widget, null)
            val view = floatingView ?: return

            layoutCard = view.findViewById(R.id.layoutCard)
            layoutMinimizedIcon = view.findViewById(R.id.layoutMinimizedIcon)
            layoutHeader = view.findViewById(R.id.layoutHeader)
            layoutBody = view.findViewById(R.id.layoutBody)
            btnMinimize = view.findViewById(R.id.btnMinimize)
            btnPlayPause = view.findViewById(R.id.btnPlayPause)
            btnClose = view.findViewById(R.id.btnClose)
            tvTimer = view.findViewById(R.id.tvTimer)
            tvStatus = view.findViewById(R.id.tvStatus)
            tvStats = view.findViewById(R.id.tvStats)

            tvStatus?.isSelected = true

            // Đặt kích thước chiều rộng cố định thu gọn (tăng nhẹ lên 235dp để vừa vặn chữ)
            val displayMetrics = resources.displayMetrics
            val density = displayMetrics.density
            val screenWidth = displayMetrics.widthPixels
            val targetWidthPx = (235 * density).toInt().coerceAtMost((screenWidth * 0.65f).toInt())
            layoutCard?.layoutParams = (layoutCard?.layoutParams ?: ViewGroup.LayoutParams(
                targetWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                width = targetWidthPx
            }

            val config = FarmConfig.loadFromPrefs(this)
            updateTimerDisplay(config)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 60
                y = 260
            }

            setupControlListeners()
            setupDraggableTouch()

            windowManager.addView(view, windowParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingWidget() {
        floatingView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingView = null
        }
    }

    private fun setupControlListeners() {
        // Nút Thu nhỏ (-) -> Thu nhỏ thành floating bubble icon trên màn hình
        btnMinimize?.setOnClickListener {
            minimizeToIcon()
        }

        // Nút Play / Pause
        btnPlayPause?.setOnClickListener {
            // Kiểm tra dịch vụ Trợ năng trước khi kích hoạt
            if (!AutomationStateManager.isAccessibilityConnected.value) {
                android.widget.Toast.makeText(
                    this,
                    "Dịch vụ Trợ năng chưa kết nối. Đang mở AutoCare để kích hoạt...",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                tvStatus?.text = "Lỗi: Trợ năng tắt"
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                return@setOnClickListener
            }

            val currentState = AutomationStateManager.statusInfo.value.state
            when (currentState) {
                AutoState.IDLE,
                AutoState.PAUSED,
                AutoState.ERROR -> {
                    AutomationStateManager.updateStatusText("Đang khởi động...")
                    AutomationStateManager.sendPlay()
                }
                else -> {
                    AutomationStateManager.updateStatusText("Đang tạm dừng...")
                    AutomationStateManager.sendPause()
                }
            }
        }

        // Nút Đóng popup (✕)
        btnClose?.setOnClickListener {
            AutomationStateManager.sendStop()
            stopSelf()
        }
    }

    private fun minimizeToIcon() {
        layoutCard?.visibility = View.GONE
        layoutMinimizedIcon?.visibility = View.VISIBLE
        floatingView?.let {
            try {
                windowManager.updateViewLayout(it, windowParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun expandToCard() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val cardWidth = layoutCard?.width?.takeIf { it > 0 }
            ?: ((235 * displayMetrics.density).toInt().coerceAtMost((screenWidth * 0.65f).toInt()))

        // Đảm bảo khi mở rộng, popup không bị tràn lề phải màn hình
        if (windowParams.x + cardWidth > screenWidth) {
            windowParams.x = (screenWidth - cardWidth - (12 * displayMetrics.density).toInt()).coerceAtLeast(0)
        }

        layoutMinimizedIcon?.visibility = View.GONE
        layoutCard?.visibility = View.VISIBLE
        tvStatus?.isSelected = true
        floatingView?.let {
            try {
                windowManager.updateViewLayout(it, windowParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableTouch() {
        val dragListener = createDragListener()

        // Kéo thả toàn bộ popup card qua phần thân body, không gắn lên layoutHeader để tránh cản trở click nút bấm
        layoutCard?.setOnTouchListener(dragListener)
        layoutBody?.setOnTouchListener(dragListener)

        // Kéo thả hoặc bấm chạm nhẹ vào icon thu nhỏ để mở rộng lại
        layoutMinimizedIcon?.setOnTouchListener(createDragListener(onTap = {
            expandToCard()
        }))
    }

    private fun createDragListener(onTap: (() -> Unit)? = null): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        if (!isDragging && (abs(dx) > CLICK_DRAG_TOLERANCE || abs(dy) > CLICK_DRAG_TOLERANCE)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            windowParams.x = (initialX + dx).toInt()
                            windowParams.y = (initialY + dy).toInt()
                            try {
                                floatingView?.let { windowManager.updateViewLayout(it, windowParams) }
                            } catch (_: Exception) {}
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            onTap?.invoke()
                        }
                        return isDragging || onTap != null
                    }
                }
                return false
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Realtime State Observer & Timer Ticker
    // ══════════════════════════════════════════════════════════

    private fun observeAutomationState() {
        serviceScope.launch {
            AutomationStateManager.statusInfo.collect { info ->
                tvStatus?.text = info.statusText
                tvStats?.text = "${info.totalVideosWatched} video • ${info.totalLikes} tim • ${info.totalSaves} lưu"

                when (info.state) {
                    AutoState.IDLE -> {
                        btnPlayPause?.setImageResource(R.drawable.ic_play)
                        stopTicker(reset = true)
                    }
                    AutoState.PAUSED -> {
                        btnPlayPause?.setImageResource(R.drawable.ic_play)
                        stopTicker(reset = false)
                    }
                    AutoState.ERROR -> {
                        btnPlayPause?.setImageResource(R.drawable.ic_play)
                        stopTicker(reset = false)
                    }
                    else -> {
                        btnPlayPause?.setImageResource(R.drawable.ic_pause)
                        startTickerIfNeeded()
                    }
                }
            }
        }
    }

    private fun startTickerIfNeeded() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            val config = FarmConfig.loadFromPrefs(this@FloatingControlService)
            while (isActive) {
                delay(1000)
                elapsedSeconds++
                updateTimerDisplay(config)
            }
        }
    }

    private fun stopTicker(reset: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        if (reset) {
            elapsedSeconds = 0L
            val config = FarmConfig.loadFromPrefs(this@FloatingControlService)
            updateTimerDisplay(config)
        }
    }

    private fun updateTimerDisplay(config: FarmConfig) {
        if (config.enableTimeLimit && config.timeLimitMinutes > 0) {
            val totalSec = config.timeLimitMinutes * 60L
            val remainingSec = (totalSec - elapsedSeconds).coerceAtLeast(0)
            val remMm = remainingSec / 60
            val remSs = remainingSec % 60
            tvTimer?.text = String.format(Locale.getDefault(), "%02d:%02d", remMm, remSs)
        } else {
            val mm = elapsedSeconds / 60
            val ss = elapsedSeconds % 60
            tvTimer?.text = String.format(Locale.getDefault(), "%02d:%02d", mm, ss)
        }
    }
}
