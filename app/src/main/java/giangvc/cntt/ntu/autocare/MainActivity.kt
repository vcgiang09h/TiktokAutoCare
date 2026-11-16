package giangvc.cntt.ntu.autocare

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager
import giangvc.cntt.ntu.autocare.model.FarmConfig
import giangvc.cntt.ntu.autocare.service.FloatingControlService
import giangvc.cntt.ntu.autocare.service.TikTokAutoService
import kotlinx.coroutines.launch

/**
 * Màn hình cấu hình chính của AutoCare.
 *
 * Cung cấp giao diện 1 màn hình cuộn gồm 4 Thẻ:
 *   1. Trạng thái Quyền hệ thống (Accessibility, Overlay, Notification)
 *   2. Cấu hình Chủ đề (tùy chọn bật/tắt) & Thời gian xem
 *   3. Cấu hình Tương tác (tùy chọn Thả tim, Lưu video, Bình luận Spintax)
 *   4. Nút Khởi chạy / Dừng thanh điều khiển nổi
 */
class MainActivity : AppCompatActivity() {

    // ── UI Views: Thẻ 1 (Quyền hệ thống) ──
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var btnGrantNotification: MaterialButton

    // ── UI Views: Thẻ 2 (Chủ đề & Thời gian) ──
    private lateinit var edtWatchMin: EditText
    private lateinit var edtWatchMax: EditText
    private lateinit var switchTimeLimit: SwitchCompat
    private lateinit var layoutTimeLimitContainer: View
    private lateinit var edtTimeLimitMinutes: EditText
    private lateinit var switchSearchByKeyword: SwitchCompat
    private lateinit var layoutKeywordsContainer: View
    private lateinit var edtKeywords: EditText
    private lateinit var edtVideosMin: EditText
    private lateinit var edtVideosMax: EditText

    // ── UI Views: Thẻ 3 (Tương tác & Bình luận) ──
    private lateinit var switchLike: SwitchCompat
    private lateinit var edtLikeRate: EditText

    private lateinit var switchSave: SwitchCompat
    private lateinit var edtSaveRate: EditText

    private lateinit var switchComment: SwitchCompat
    private lateinit var layoutCommentContainer: View
    private lateinit var edtReadCmtRate: EditText
    private lateinit var edtWriteCmtRate: EditText
    private lateinit var edtCommentDict: EditText

    // ── UI Views: Thẻ 4 (Nút hành động) ──
    private lateinit var btnToggleOverlay: MaterialButton

    // ── Permission Launcher for Notification (Android 13+) ──
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkPermissionsState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            // Xử lý insets an toàn
            findViewById<View>(R.id.mainRoot)?.let { mainRoot ->
                ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
                    insets
                }
            }

            bindViews()
            loadConfigToUI()
            setupListeners()
            observeStateManager()
            checkPreviousCrashLog()
        } catch (e: Throwable) {
            e.printStackTrace()
            try {
                Toast.makeText(this, "Lỗi khởi động: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    private fun checkPreviousCrashLog() {
        try {
            val prefs = getSharedPreferences(AutoCareApp.PREF_CRASH, Context.MODE_PRIVATE)
            val lastCrash = prefs.getString(AutoCareApp.KEY_LAST_CRASH, null)
            if (!lastCrash.isNullOrBlank()) {
                prefs.edit().remove(AutoCareApp.KEY_LAST_CRASH).apply()
                AlertDialog.Builder(this)
                    .setTitle("Báo cáo sự cố lần trước")
                    .setMessage(lastCrash.take(1200))
                    .setPositiveButton("Đã hiểu", null)
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            checkPermissionsState()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        saveConfigFromUI(showToast = false)
    }

    // ══════════════════════════════════════════════════════════
    //  UI Binding & Initialization
    // ══════════════════════════════════════════════════════════

    private fun bindViews() {
        // Card 1: Permissions
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)

        // Card 2: Topic & Time
        edtWatchMin = findViewById(R.id.edtWatchMin)
        edtWatchMax = findViewById(R.id.edtWatchMax)
        switchTimeLimit = findViewById(R.id.switchTimeLimit)
        layoutTimeLimitContainer = findViewById(R.id.layoutTimeLimitContainer)
        edtTimeLimitMinutes = findViewById(R.id.edtTimeLimitMinutes)
        switchSearchByKeyword = findViewById(R.id.switchSearchByKeyword)
        layoutKeywordsContainer = findViewById(R.id.layoutKeywordsContainer)
        edtKeywords = findViewById(R.id.edtKeywords)
        edtVideosMin = findViewById(R.id.edtVideosMin)
        edtVideosMax = findViewById(R.id.edtVideosMax)

        // Card 3: Interaction & Comment
        switchLike = findViewById(R.id.switchLike)
        edtLikeRate = findViewById(R.id.edtLikeRate)

        switchSave = findViewById(R.id.switchSave)
        edtSaveRate = findViewById(R.id.edtSaveRate)

        switchComment = findViewById(R.id.switchComment)
        layoutCommentContainer = findViewById(R.id.layoutCommentContainer)
        edtReadCmtRate = findViewById(R.id.edtReadCmtRate)
        edtWriteCmtRate = findViewById(R.id.edtWriteCmtRate)
        edtCommentDict = findViewById(R.id.edtCommentDict)

        // Card 4: Action button
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
    }

    private fun setupListeners() {
        // Permissions
        btnGrantAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnGrantOverlay.setOnClickListener { openOverlaySettings() }
        btnGrantNotification.setOnClickListener { requestNotificationPermission() }

        // Switch Hẹn giờ dừng
        switchTimeLimit.setOnCheckedChangeListener { _, isChecked ->
            layoutTimeLimitContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Switch Chủ đề
        switchSearchByKeyword.setOnCheckedChangeListener { _, isChecked ->
            layoutKeywordsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Switch Thả tim
        switchLike.setOnCheckedChangeListener { _, isChecked ->
            edtLikeRate.isEnabled = isChecked
            edtLikeRate.alpha = if (isChecked) 1.0f else 0.4f
        }

        // Switch Lưu video
        switchSave.setOnCheckedChangeListener { _, isChecked ->
            edtSaveRate.isEnabled = isChecked
            edtSaveRate.alpha = if (isChecked) 1.0f else 0.4f
        }

        // Switch Bình luận
        switchComment.setOnCheckedChangeListener { _, isChecked ->
            layoutCommentContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Nút Khởi chạy / Dừng thanh nổi
        btnToggleOverlay.setOnClickListener {
            handleToggleOverlay()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  StateFlow Observer (Realtime Updates)
    // ══════════════════════════════════════════════════════════

    private fun observeStateManager() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationStateManager.isAccessibilityConnected.collect { isConnected ->
                        try {
                            updateAccessibilityButtonState(isConnected)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }

                launch {
                    AutomationStateManager.isFloatingServiceRunning.collect { isRunning ->
                        try {
                            updateActionButtons(isRunning)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun updateActionButtons(isServiceRunning: Boolean) {
        if (isServiceRunning) {
            btnToggleOverlay.setText(R.string.btn_stop_overlay)
            btnToggleOverlay.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
        } else {
            btnToggleOverlay.setText(R.string.btn_start_overlay)
            btnToggleOverlay.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Permission Management
    // ══════════════════════════════════════════════════════════

    private fun checkPermissionsState() {
        try {
            // 1. Accessibility Service
            val isA11yEnabled = isAccessibilityServiceEnabled(this, TikTokAutoService::class.java)
            AutomationStateManager.setAccessibilityConnected(isA11yEnabled)
            updateAccessibilityButtonState(isA11yEnabled)

            // 2. Overlay Permission
            val isOverlayEnabled = try {
                Settings.canDrawOverlays(this)
            } catch (e: Exception) {
                false
            }
            updatePermissionButton(
                btnGrantOverlay,
                isGranted = isOverlayEnabled,
                grantedText = getString(R.string.perm_status_granted),
                deniedText = getString(R.string.perm_status_denied)
            )

            // 3. Notification Permission
            val isNotificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    true
                }
            } else {
                true
            }
            updatePermissionButton(
                btnGrantNotification,
                isGranted = isNotificationEnabled,
                grantedText = getString(R.string.perm_status_granted),
                deniedText = getString(R.string.perm_status_denied)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateAccessibilityButtonState(isConnected: Boolean) {
        updatePermissionButton(
            btnGrantAccessibility,
            isGranted = isConnected,
            grantedText = getString(R.string.perm_status_granted),
            deniedText = getString(R.string.perm_status_denied)
        )
    }

    private fun updatePermissionButton(
        button: MaterialButton,
        isGranted: Boolean,
        grantedText: String,
        deniedText: String
    ) {
        if (isGranted) {
            button.text = grantedText
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            button.isEnabled = false
        } else {
            button.text = deniedText
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
            button.isEnabled = true
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Tìm và bật 'AutoCare' trong danh sách Dịch vụ đã cài đặt",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở Cài đặt Trợ năng", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isAccessibilityServiceEnabled(
        context: Context,
        service: Class<out AccessibilityService>
    ): Boolean {
        try {
            // Cách 1: AccessibilityManager chính quy của Android (an toàn, không cần permission)
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledServices != null) {
                for (enabledService in enabledServices) {
                    val serviceInfo = enabledService.resolveInfo?.serviceInfo
                    if (serviceInfo != null &&
                        serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == service.name
                    ) {
                        return true
                    }
                }
            }

            // Cách 2: Fallback kiểm tra qua Settings.Secure
            val expectedComponentName = ComponentName(context, service)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // ══════════════════════════════════════════════════════════
    //  Configuration Load & Save
    // ══════════════════════════════════════════════════════════

    private fun loadConfigToUI() {
        val config = FarmConfig.loadFromPrefs(this)

        // Time
        edtWatchMin.setText(config.watchTimeMinSec.toString())
        edtWatchMax.setText(config.watchTimeMaxSec.toString())
        switchTimeLimit.isChecked = config.enableTimeLimit
        layoutTimeLimitContainer.visibility = if (config.enableTimeLimit) View.VISIBLE else View.GONE
        edtTimeLimitMinutes.setText(config.timeLimitMinutes.toString())

        // Topic
        switchSearchByKeyword.isChecked = config.enableSearchByKeyword
        layoutKeywordsContainer.visibility = if (config.enableSearchByKeyword) View.VISIBLE else View.GONE
        edtKeywords.setText(config.keywordsAsText())
        edtVideosMin.setText(config.videosPerKeywordMin.toString())
        edtVideosMax.setText(config.videosPerKeywordMax.toString())

        // Like
        switchLike.isChecked = config.enableLike
        edtLikeRate.isEnabled = config.enableLike
        edtLikeRate.alpha = if (config.enableLike) 1.0f else 0.4f
        edtLikeRate.setText(config.likeRate.toString())

        // Save
        switchSave.isChecked = config.enableSave
        edtSaveRate.isEnabled = config.enableSave
        edtSaveRate.alpha = if (config.enableSave) 1.0f else 0.4f
        edtSaveRate.setText(config.saveRate.toString())

        // Comment
        switchComment.isChecked = config.enableComment
        layoutCommentContainer.visibility = if (config.enableComment) View.VISIBLE else View.GONE
        edtReadCmtRate.setText(config.readCommentRate.toString())
        edtWriteCmtRate.setText(config.writeCommentRate.toString())
        edtCommentDict.setText(config.commentDictionary)
    }

    private fun saveConfigFromUI(showToast: Boolean = true): FarmConfig? {
        val enableSearch = switchSearchByKeyword.isChecked
        val rawKeywords = edtKeywords.text.toString()
        val keywords = rawKeywords.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val watchMin = edtWatchMin.text.toString().toIntOrNull() ?: 10
        val watchMax = edtWatchMax.text.toString().toIntOrNull() ?: 25
        val enableTimeLimit = switchTimeLimit.isChecked
        val timeLimitMinutes = edtTimeLimitMinutes.text.toString().toIntOrNull() ?: 30
        val videosMin = edtVideosMin.text.toString().toIntOrNull() ?: 4
        val videosMax = edtVideosMax.text.toString().toIntOrNull() ?: 5

        val enableLike = switchLike.isChecked
        val likeRate = edtLikeRate.text.toString().toIntOrNull() ?: 20

        val enableSave = switchSave.isChecked
        val saveRate = edtSaveRate.text.toString().toIntOrNull() ?: 5

        val enableComment = switchComment.isChecked
        val readRate = edtReadCmtRate.text.toString().toIntOrNull() ?: 30
        val writeRate = edtWriteCmtRate.text.toString().toIntOrNull() ?: 10
        val commentDict = edtCommentDict.text.toString().trim()

        val config = FarmConfig(
            enableSearchByKeyword = enableSearch,
            keywords = keywords,
            watchTimeMinSec = watchMin,
            watchTimeMaxSec = watchMax,
            videosPerKeywordMin = videosMin,
            videosPerKeywordMax = videosMax,
            enableTimeLimit = enableTimeLimit,
            timeLimitMinutes = timeLimitMinutes,
            enableLike = enableLike,
            likeRate = likeRate,
            enableSave = enableSave,
            saveRate = saveRate,
            enableComment = enableComment,
            readCommentRate = readRate,
            writeCommentRate = writeRate,
            commentDictionary = commentDict
        )

        val errors = config.validate()
        if (errors.isNotEmpty()) {
            if (showToast) {
                showValidationErrorsDialog(errors)
            }
            return null
        }

        FarmConfig.saveToPrefs(this, config)
        if (showToast) {
            Toast.makeText(this, R.string.msg_config_saved, Toast.LENGTH_SHORT).show()
        }
        return config
    }

    private fun showValidationErrorsDialog(errors: List<String>) {
        val message = errors.mapIndexed { idx, err -> "${idx + 1}. $err" }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Cấu hình chưa hợp lệ")
            .setMessage(message)
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════
    //  Action: Launch or Stop Floating Widget
    // ══════════════════════════════════════════════════════════

    private fun handleToggleOverlay() {
        val isRunning = AutomationStateManager.isFloatingServiceRunning.value

        if (isRunning) {
            stopFloatingService()
        } else {
            val config = saveConfigFromUI(showToast = true) ?: return

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Cần cấp quyền 'Hiển thị trên ứng dụng khác' trước!",
                    Toast.LENGTH_LONG
                ).show()
                openOverlaySettings()
                return
            }

            val isA11yEnabled = isAccessibilityServiceEnabled(this, TikTokAutoService::class.java)
            if (!isA11yEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("Chưa bật quyền Trợ năng")
                    .setMessage("AutoCare cần quyền Trợ năng (Accessibility) để tự động hóa tương tác TikTok. Vui lòng bật dịch vụ trong Cài đặt.")
                    .setPositiveButton("Mở Cài đặt") { _, _ -> openAccessibilitySettings() }
                    .setNegativeButton("Hủy", null)
                    .show()
                return
            }

            startFloatingService()
        }
    }

    private fun startFloatingService() {
        try {
            val intent = Intent(this, FloatingControlService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Đã khởi chạy Menu nổi!", Toast.LENGTH_SHORT).show()

            // Trì hoãn việc ẩn Activity về background để đảm bảo Service hoàn tất
            // startForeground() trong khi Activity vẫn đang ở Foreground,
            // tránh tuyệt đối ForegroundServiceStartNotAllowedException trên Android 12+ (SDK 31-36).
            window.decorView.postDelayed({
                try {
                    moveTaskToBack(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khởi chạy Menu nổi: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun stopFloatingService() {
        try {
            AutomationStateManager.sendStop()
            val intent = Intent(this, FloatingControlService::class.java)
            stopService(intent)
            Toast.makeText(this, "Đã đóng Menu nổi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi đóng Menu nổi: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
}
