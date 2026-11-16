package giangvc.cntt.ntu.autocare.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trung tâm giao tiếp realtime giữa các thành phần:
 *   - TikTokAutoService  (producer: cập nhật trạng thái kịch bản)
 *   - FloatingControlService (consumer: hiển thị trạng thái + gửi lệnh Play/Pause/Stop)
 *   - MainActivity (consumer: hiển thị trạng thái service)
 *
 * Dùng Kotlin StateFlow thay cho LocalBroadcast (đã deprecated).
 * Singleton object đảm bảo tất cả các thành phần truy cập cùng 1 instance.
 */
object AutomationStateManager {

    // ══════════════════════════════════════════════════════════
    //  Trạng thái kịch bản (Automation State Machine)
    // ══════════════════════════════════════════════════════════

    /** Các trạng thái của bộ máy tự động */
    enum class AutoState {
        /** Chưa bắt đầu / đã dừng hẳn */
        IDLE,
        /** Đang chạy kịch bản */
        RUNNING,
        /** Tạm dừng (người dùng bấm Pause) */
        PAUSED,
        /** Đang mở ứng dụng TikTok */
        LAUNCHING_APP,
        /** Đang tìm kiếm từ khóa */
        SEARCHING,
        /** Đang xem video */
        WATCHING,
        /** Đang thả tim / lưu video */
        INTERACTING,
        /** Đang đọc bình luận */
        READING_COMMENTS,
        /** Đang viết bình luận */
        POSTING_COMMENT,
        /** Đang chuyển video tiếp theo */
        NEXT_VIDEO,
        /** Lỗi — cần người dùng can thiệp */
        ERROR
    }

    /** Dữ liệu trạng thái hiện tại hiển thị trên floating widget */
    data class StatusInfo(
        val state: AutoState = AutoState.IDLE,
        /** Dòng mô tả ngắn hiển thị trên widget (VD: "Xem: 14s | Đang lướt cmt...") */
        val statusText: String = "Sẵn sàng",
        /** Từ khóa đang tìm kiếm */
        val currentKeyword: String = "",
        /** Số video đã xem cho từ khóa hiện tại */
        val videosWatched: Int = 0,
        /** Tổng số video đã xem trong phiên */
        val totalVideosWatched: Int = 0,
        /** Tổng lượt tim đã thả */
        val totalLikes: Int = 0,
        /** Tổng số video đã lưu */
        val totalSaves: Int = 0,
        /** Tổng bình luận đã gửi */
        val totalComments: Int = 0
    )

    // ── StateFlow cho trạng thái kịch bản ──

    private val _statusInfo = MutableStateFlow(StatusInfo())
    /** Flow trạng thái hiện tại — FloatingControlService & MainActivity collect */
    val statusInfo: StateFlow<StatusInfo> = _statusInfo.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  Lệnh điều khiển (Control Commands)
    // ══════════════════════════════════════════════════════════

    /** Lệnh từ FloatingWidget → TikTokAutoService */
    enum class Command {
        PLAY,
        PAUSE,
        STOP
    }

    private val _currentCommand = MutableStateFlow(Command.STOP)
    /** Trạng thái lệnh hiện tại (State) */
    val currentCommand: StateFlow<Command> = _currentCommand.asStateFlow()

    private val _commandFlow = kotlinx.coroutines.flow.MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    /** Flow phát sự kiện lệnh điều khiển — đảm bảo mỗi lần bấm Play/Pause/Stop đều được nhận */
    val command: kotlinx.coroutines.flow.SharedFlow<Command> = _commandFlow

    /** Kiểm tra nhanh xem lệnh hiện tại có phải PLAY không */
    fun isRunning(): Boolean = _currentCommand.value == Command.PLAY

    // ══════════════════════════════════════════════════════════
    //  Trạng thái kết nối Service
    // ══════════════════════════════════════════════════════════

    private val _isAccessibilityConnected = MutableStateFlow(false)
    /** Accessibility Service đã được bật & kết nối hay chưa */
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isFloatingServiceRunning = MutableStateFlow(false)
    /** FloatingControlService đang chạy hay không */
    val isFloatingServiceRunning: StateFlow<Boolean> = _isFloatingServiceRunning.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  API cập nhật (gọi từ Service / Activity)
    // ══════════════════════════════════════════════════════════

    // ── Từ TikTokAutoService ──

    /** Cập nhật trạng thái kịch bản (gọi từ TikTokAutoService) */
    fun updateStatus(
        state: AutoState,
        statusText: String,
        currentKeyword: String = _statusInfo.value.currentKeyword,
        videosWatched: Int = _statusInfo.value.videosWatched,
        totalVideosWatched: Int = _statusInfo.value.totalVideosWatched,
        totalLikes: Int = _statusInfo.value.totalLikes,
        totalSaves: Int = _statusInfo.value.totalSaves,
        totalComments: Int = _statusInfo.value.totalComments
    ) {
        _statusInfo.value = StatusInfo(
            state = state,
            statusText = statusText,
            currentKeyword = currentKeyword,
            videosWatched = videosWatched,
            totalVideosWatched = totalVideosWatched,
            totalLikes = totalLikes,
            totalSaves = totalSaves,
            totalComments = totalComments
        )
    }

    /** Cập nhật chỉ dòng trạng thái ngắn (tiện cho widget) */
    fun updateStatusText(text: String) {
        _statusInfo.value = _statusInfo.value.copy(statusText = text)
    }

    /** Tăng bộ đếm tương tác */
    fun incrementLikes() {
        _statusInfo.value = _statusInfo.value.copy(
            totalLikes = _statusInfo.value.totalLikes + 1
        )
    }

    fun incrementSaves() {
        _statusInfo.value = _statusInfo.value.copy(
            totalSaves = _statusInfo.value.totalSaves + 1
        )
    }

    fun incrementComments() {
        _statusInfo.value = _statusInfo.value.copy(
            totalComments = _statusInfo.value.totalComments + 1
        )
    }

    fun incrementVideosWatched() {
        val current = _statusInfo.value
        _statusInfo.value = current.copy(
            videosWatched = current.videosWatched + 1,
            totalVideosWatched = current.totalVideosWatched + 1
        )
    }

    /** Đặt lại bộ đếm video khi đổi từ khóa */
    fun resetKeywordVideoCount(newKeyword: String) {
        _statusInfo.value = _statusInfo.value.copy(
            currentKeyword = newKeyword,
            videosWatched = 0
        )
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    // ── Từ FloatingControlService ──

    fun setFloatingServiceRunning(running: Boolean) {
        _isFloatingServiceRunning.value = running
    }

    // ── Từ FloatingWidget (nút Play/Pause/Stop) ──

    /** Gửi lệnh Play — phát sự kiện cho TikTokAutoService */
    fun sendPlay() {
        _currentCommand.value = Command.PLAY
        _commandFlow.tryEmit(Command.PLAY)
    }

    /** Gửi lệnh Pause */
    fun sendPause() {
        _currentCommand.value = Command.PAUSE
        _commandFlow.tryEmit(Command.PAUSE)
    }

    /** Gửi lệnh Stop và reset trạng thái */
    fun sendStop() {
        _currentCommand.value = Command.STOP
        _commandFlow.tryEmit(Command.STOP)
        _statusInfo.value = StatusInfo() // reset về mặc định
    }

    // ── Reset toàn bộ ──

    /** Reset tất cả state về mặc định (khi app bị kill hoặc người dùng thoát) */
    fun resetAll() {
        _currentCommand.value = Command.STOP
        _commandFlow.tryEmit(Command.STOP)
        _statusInfo.value = StatusInfo()
        _isAccessibilityConnected.value = false
        _isFloatingServiceRunning.value = false
    }
}
