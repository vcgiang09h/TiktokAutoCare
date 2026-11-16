package giangvc.cntt.ntu.autocare.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PointF
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager.AutoState
import giangvc.cntt.ntu.autocare.engine.AutomationStateManager.Command
import giangvc.cntt.ntu.autocare.engine.GestureHelper
import giangvc.cntt.ntu.autocare.engine.NodeFinder
import giangvc.cntt.ntu.autocare.engine.SpintaxParser
import giangvc.cntt.ntu.autocare.model.FarmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AccessibilityService chính — điều phối toàn bộ State Machine 8 bước.
 *
 * Giao tiếp với FloatingControlService và MainActivity qua [AutomationStateManager] (StateFlow).
 *
 * State Machine (vòng lặp bất tận, mỗi vòng = 1 video):
 *   LAUNCH_APP → SEARCH_KEYWORD → WATCH_VIDEO → LIKE_AND_SAVE
 *   → VIEW_COMMENTS → POST_COMMENT → CLOSE_COMMENT → NEXT_VIDEO
 *   → (lặp lại WATCH_VIDEO hoặc SEARCH_KEYWORD)
 *
 * Anti-detect đã tích hợp:
 *   - Mọi gesture đều dùng Bezier curve (GestureHelper)
 *   - Phát hiện và skip quảng cáo / Livestream
 *   - Delay ngẫu nhiên có jitter giữa các bước
 *   - Không gọi node.recycle() (deprecated SDK 36)
 */
class TikTokAutoService : AccessibilityService() {

    // ── Coroutine scope gắn với lifecycle của Service ──
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // ── Job chạy State Machine (có thể cancel khi Pause/Stop) ──
    private var automationJob: Job? = null

    // ── Cấu hình người dùng (load khi PLAY được nhận) ──
    private var config: FarmConfig = FarmConfig()

    // ── Kích thước màn hình (lazy — lấy 1 lần) ──
    private var screenSize: PointF? = null

    // ── Thời điểm bắt đầu chạy kịch bản (dùng để hẹn giờ tự động dừng) ──
    private var loopStartTimeMs: Long = 0L

    private fun isTimeLimitExceeded(): Boolean {
        if (!config.enableTimeLimit || config.timeLimitMinutes <= 0) return false
        val elapsedMs = System.currentTimeMillis() - loopStartTimeMs
        return elapsedMs >= config.timeLimitMinutes * 60 * 1000L
    }

    private fun isContinueRunning(): Boolean {
        if (!isRunningCommand()) return false
        if (isTimeLimitExceeded()) {
            AutomationStateManager.updateStatus(
                state = AutoState.IDLE,
                statusText = "Đã hoàn thành ${config.timeLimitMinutes}p - Đã tự động dừng!"
            )
            AutomationStateManager.sendStop()
            return false
        }
        return true
    }

    // ── Package TikTok đang được dùng (phát hiện khi launch) ──
    private val tiktokPackages = listOf(
        "com.ss.android.ugc.trill",        // TikTok Vietnam
        "com.zhiliaoapp.musically"          // TikTok Global
    )
    private var activeTiktokPackage: String = tiktokPackages[0]

    // ══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationStateManager.setAccessibilityConnected(true)
        // Lắng nghe lệnh từ FloatingWidget qua StateFlow
        observeCommands()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // State Machine tự điều phối qua coroutine — không cần xử lý event ở đây.
        // Có thể dùng event để detect app switching nếu cần sau này.
    }

    override fun onInterrupt() {
        // Hệ thống yêu cầu ngắt gesture
        automationJob?.cancel()
        AutomationStateManager.updateStatus(AutoState.IDLE, "Bị gián đoạn")
    }

    override fun onDestroy() {
        super.onDestroy()
        automationJob?.cancel()
        serviceScope.cancel()
        AutomationStateManager.setAccessibilityConnected(false)
        AutomationStateManager.updateStatus(AutoState.IDLE, "Service đã dừng")
    }

    // ══════════════════════════════════════════════════════════════════
    //  Lắng nghe lệnh Play / Pause / Stop từ FloatingWidget
    // ══════════════════════════════════════════════════════════════════

    private fun observeCommands() {
        serviceScope.launch {
            AutomationStateManager.command.collectLatest { command ->
                when (command) {
                    Command.PLAY -> {
                        // Hủy bỏ job cũ (nếu có) để start vòng lặp mới sạch sẽ, không bị treo
                        automationJob?.cancel()
                        config = FarmConfig.loadFromPrefs(applicationContext)
                        screenSize = GestureHelper.getScreenSize(this@TikTokAutoService)
                        startAutomationLoop()
                    }
                    Command.PAUSE -> {
                        automationJob?.cancel()
                        AutomationStateManager.updateStatus(AutoState.PAUSED, "Đã tạm dừng")
                    }
                    Command.STOP -> {
                        automationJob?.cancel()
                        AutomationStateManager.updateStatus(AutoState.IDLE, "Sẵn sàng")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  State Machine — vòng lặp chính
    // ══════════════════════════════════════════════════════════════════

    private fun startAutomationLoop() {
        automationJob = serviceScope.launch {
            loopStartTimeMs = System.currentTimeMillis()
            AutomationStateManager.updateStatus(AutoState.RUNNING, "Đang khởi động...")

            try {
                // ── Bước 1: Mở TikTok ──
                val launchSuccess = stepLaunchApp()
                if (!launchSuccess) {
                    AutomationStateManager.updateStatus(AutoState.ERROR, "Không tìm thấy TikTok")
                    return@launch
                }

                // Chờ TikTok tải giao diện ổn định
                delay(randomDelay(2500, 3500))

                // Chỉ đưa về Trang chủ NẾU thực sự đang ở màn hình khác (tránh bấm Back nhầm trên màn hình chính)
                val initRoot = getRoot()
                if (!isAtHomeScreen(initRoot)) {
                    stepBackToHomeScreen()
                    delay(1200)
                }

                // ══════════════════════════════════════════════════════════════
                // BƯỚC 1: TÌM KIẾM THEO CHỦ ĐỀ — CHỈ CHẠY ĐÚNG 1 LẦN KHI BẬT TOOL
                // ══════════════════════════════════════════════════════════════
                val keywordsList = config.keywords.filter { it.isNotBlank() }
                if (config.enableSearchByKeyword && keywordsList.isNotEmpty()) {
                    val keyword = keywordsList.random() // Chọn ngẫu nhiên 1 từ khóa thay vì luôn lấy từ khóa đầu tiên
                    AutomationStateManager.resetKeywordVideoCount(keyword)

                    val searchOk = stepSearchKeyword(keyword)
                    if (searchOk) {
                        // Xem 4-5 video trong chủ đề vừa tìm kiếm
                        val topicLimit = (4..5).random()
                        var topicVideosWatched = 0
                        var consecutiveSkipCount = 0

                        while (topicVideosWatched < topicLimit && isContinueRunning()) {
                            ensureTikTokForeground()

                            val root = getRoot()
                            val screenH = screenSize?.y ?: resources.displayMetrics.heightPixels.toFloat()
                            val screenW = screenSize?.x ?: resources.displayMetrics.widthPixels.toFloat()
                            val adOrLive = NodeFinder.detectAdOrLive(root, screenH, screenW)
                            if (adOrLive != null) {
                                consecutiveSkipCount++
                                if (consecutiveSkipCount >= 3) {
                                    consecutiveSkipCount = 0
                                    break // Thoát luồng chủ đề để về Trang chủ
                                }
                                AutomationStateManager.updateStatusText("Bỏ qua: $adOrLive")
                                delay(randomDelay(800, 1200))
                                swipeNextVideo()
                                delay(randomDelay(1800, 2600)) // Chờ video kế tiếp load xong
                                continue
                            }

                            consecutiveSkipCount = 0
                            // Chỉ lướt xem theo chủ đề, KHÔNG tương tác like/comment/save để tránh lỗi
                            watchTopicVideoOnly(
                                keywordLabel = "#$keyword",
                                currentCount = topicVideosWatched + 1,
                                totalLimit = topicLimit
                            )
                            topicVideosWatched++

                            if (topicVideosWatched < topicLimit && isContinueRunning()) {
                                stepNextVideo()
                                delay(randomDelay(1800, 2800))
                            }
                        }
                    } else {
                        AutomationStateManager.updateStatusText("Không tìm được video #$keyword, về Trang chủ...")
                        delay(2000)
                    }

                    // Sau khi xem xong 4-5 video chủ đề -> Quay về Trang chủ đúng 1 lần duy nhất
                    stepBackToHomeScreen()
                    delay(randomDelay(2000, 3000))
                }

                // ══════════════════════════════════════════════════════════════
                // BƯỚC 2: VÒNG LẶP CHÍNH — LƯỚT XEM LIÊN TỤC Ở TRANG CHỦ (DÀNH CHO BẠN)
                // ══════════════════════════════════════════════════════════════
                AutomationStateManager.updateStatus(
                    state = AutoState.WATCHING,
                    statusText = "Lướt xem: Dành cho bạn",
                    currentKeyword = "Trang chủ"
                )

                // ĐẢM BẢO CHẮC CHẮN ĐÃ VỀ TỚI TRANG CHỦ trước khi vào vòng lặp tương tác
                var ensureHomeCount = 0
                while (isContinueRunning() && !isAtHomeScreen(getRoot()) && ensureHomeCount < 5) {
                    AutomationStateManager.updateStatusText("Đang kiểm tra & chuyển về Trang chủ...")
                    stepBackToHomeScreen()
                    delay(randomDelay(1500, 2500))
                    ensureHomeCount++
                }

                var homeConsecutiveSkipCount = 0
                while (isContinueRunning()) {
                    ensureTikTokForeground()

                    val root = getRoot()

                    // KIỂM TRA BẮT BUỘC: Nếu chưa ở Trang chủ (chưa về tới hoặc bị lạc màn hình) -> Chuyển về Trang chủ trước!
                    if (!isAtHomeScreen(root)) {
                        AutomationStateManager.updateStatusText("Chưa ở Trang chủ, đang chuyển về Trang chủ...")
                        stepBackToHomeScreen()
                        delay(randomDelay(1500, 2500))
                        continue
                    }

                    val screenH = screenSize?.y ?: resources.displayMetrics.heightPixels.toFloat()
                    val screenW = screenSize?.x ?: resources.displayMetrics.widthPixels.toFloat()
                    val adOrLive = NodeFinder.detectAdOrLive(root, screenH, screenW)
                    if (adOrLive != null) {
                        homeConsecutiveSkipCount++
                        AutomationStateManager.updateStatusText("Bỏ qua: $adOrLive ($homeConsecutiveSkipCount)")
                        delay(randomDelay(800, 1200))

                        // LUÔN LUÔN vuốt chuyển sang video tiếp theo để chắc chắn bỏ qua video này
                        swipeNextVideo()
                        delay(randomDelay(1800, 2600)) // Chờ video tiếp theo trượt vào và hiển thị hoàn tất

                        // Nếu bỏ qua liên tiếp quá nhiều lần (>= 4 lần), đồng bộ lại để tránh bị kẹt trong phòng LIVE hoặc màn hình phụ
                        if (homeConsecutiveSkipCount >= 4) {
                            AutomationStateManager.updateStatusText("Bỏ qua $homeConsecutiveSkipCount lần, đồng bộ lại Trang chủ...")
                            homeConsecutiveSkipCount = 0
                            stepBackToHomeScreen()
                            delay(randomDelay(1500, 2500))
                        }
                        continue
                    }

                    homeConsecutiveSkipCount = 0
                    processSingleVideo(keywordLabel = "Trang chủ")

                    stepNextVideo()
                    delay(randomDelay(2000, 3500))
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Bị cancel bởi Pause/Stop — đây là luồng bình thường
                throw e
            } catch (e: Exception) {
                AutomationStateManager.updateStatus(
                    AutoState.ERROR,
                    "Lỗi: ${e.message?.take(40) ?: "unknown"}"
                )
            }
        }
    }

    /**
     * Xem video trong luồng chủ đề tìm kiếm.
     * QUY TẮC QUAN TRỌNG: Chỉ lướt xem qua 4-5 video theo yêu cầu,
     * TUYỆT ĐỐI KHÔNG thực hiện tương tác (thả tim, bình luận, lưu) để tránh lỗi.
     * Mọi tương tác chỉ diễn ra sau khi đã trở về Trang chủ (Home Feed).
     */
    private suspend fun watchTopicVideoOnly(keywordLabel: String, currentCount: Int, totalLimit: Int) {
        val watchTimeMs = config.randomWatchTimeMs().coerceIn(4000L, 8000L)

        AutomationStateManager.updateStatus(
            state = AutoState.WATCHING,
            statusText = "Xem chủ đề ($currentCount/$totalLimit) • $keywordLabel (${watchTimeMs / 1000}s)",
            currentKeyword = keywordLabel
        )

        // Chỉ chờ xem hết thời gian chỉ định, hoàn toàn không gọi stepLikeAndSave hay bình luận
        delay(watchTimeMs)
        AutomationStateManager.incrementVideosWatched()
    }

    /**
     * Thực hiện quy trình xem và tương tác cho 1 video:
     *   Xem thời gian ngẫu nhiên -> Thả tim (nếu bật) -> Lưu video (nếu bật) -> Bình luận (nếu bật)
     */
    private suspend fun processSingleVideo(keywordLabel: String, currentCount: Int = 0) {
        val watchTimeMs = config.randomWatchTimeMs()
        val interactionStartMs = watchTimeMs * 2 / 5

        AutomationStateManager.updateStatus(
            state = AutoState.WATCHING,
            statusText = "Đang xem video... (${watchTimeMs / 1000}s) • $keywordLabel",
            videosWatched = currentCount
        )

        // Chờ 1 lúc trước khi tương tác
        delay(randomDelay(
            (interactionStartMs * 0.8).toLong(),
            (interactionStartMs * 1.2).toLong()
        ))

        // Bước 4: Thả tim & Lưu
        stepLikeAndSave()

        delay(randomDelay(1500, 3000))

        // Bước 5 & 6: Đọc & Viết bình luận (chỉ chạy nếu bật bình luận)
        var commentPanelOpen = false
        val wantRead = config.shouldReadComments()
        val wantWrite = config.shouldWriteComment()
        if (wantRead || wantWrite) {
            commentPanelOpen = stepViewComments(wantRead = wantRead, wantWrite = wantWrite)
        }

        // Bước 7: Đóng khung bình luận
        if (commentPanelOpen) {
            stepCloseComments()
        }

        // Chờ nốt thời gian xem còn lại
        val remainingMs = watchTimeMs - interactionStartMs - 3000
        if (remainingMs > 500) {
            delay(randomDelay(
                (remainingMs * 0.7).toLong(),
                remainingMs
            ))
        }

        AutomationStateManager.incrementVideosWatched()
    }

    // ══════════════════════════════════════════════════════════════════
    //  Các bước State Machine (Step functions)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Bước 1: LAUNCH_APP
     * Mở TikTok (ưu tiên VN, fallback Global). Chờ 3–5 giây cho app load.
     */
    private suspend fun stepLaunchApp(): Boolean {
        AutomationStateManager.updateStatus(AutoState.LAUNCHING_APP, "Đang mở TikTok...")

        // Thử từng package TikTok
        for (pkg in tiktokPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(launchIntent)
            activeTiktokPackage = pkg
            // Chờ app load
            delay(randomDelay(3000, 5000))
            return true
        }

        return false
    }

    /**
     * Bước 2: SEARCH_KEYWORD
     * Tìm nút Search → nhập từ khóa → submit → chuyển tab Video → click video đầu tiên.
     */
    private suspend fun stepSearchKeyword(keyword: String): Boolean {
        AutomationStateManager.updateStatus(AutoState.SEARCHING, "Tìm: $keyword")

        val screen = screenSize ?: return false

        // 1. Tìm nút Search ở góc trên phải màn hình chính TikTok
        var root = getRoot()
        val searchBtn = NodeFinder.findSearchButton(root)

        if (searchBtn != null) {
            GestureHelper.clickNode(this, searchBtn)
        } else {
            // Fallback tap: góc trên phải màn hình (vị trí kinh điển của icon kính lúp trên TikTok)
            val approxSearchX = screen.x * 0.90f
            val approxSearchY = screen.y * 0.06f
            GestureHelper.tapCoordinates(this, approxSearchX, approxSearchY)
            delay(1200)

            root = getRoot()
            if (NodeFinder.findSearchInput(root) == null) {
                // Nếu chưa mở ô search, thử back rồi tìm lại một lần nữa
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1000)
                root = getRoot()
                val searchBtn2 = NodeFinder.findSearchButton(root) ?: return false
                GestureHelper.clickNode(this, searchBtn2)
            }
        }

        // Chờ trang tìm kiếm mở chậm rãi và ổn định
        delay(randomDelay(2000, 3000))

        // 2. Nhập từ khóa vào ô Search
        root = getRoot()
        var searchInput = NodeFinder.findSearchInput(root)
        if (searchInput == null) {
            // Thử tap vào thanh search ở đầu màn hình
            GestureHelper.tapCoordinates(this, screen.x * 0.5f, screen.y * 0.06f)
            delay(1500)
            root = getRoot()
            searchInput = NodeFinder.findSearchInput(root)
        }

        if (searchInput == null) return false

        // Click vào ô nhập để focus hiển thị bàn phím
        GestureHelper.clickNode(this, searchInput)
        delay(randomDelay(1000, 1600))

        // Điền từ khóa bằng ACTION_SET_TEXT
        NodeFinder.setTextToNode(searchInput, keyword)
        // Chờ TikTok kích hoạt TextWatcher và tải danh sách gợi ý tìm kiếm
        delay(randomDelay(2000, 3000))

        // 3. Submit tìm kiếm an toàn (TUYỆT ĐỐI không bấm nhầm nút Clear X):
        root = getRoot()
        // Cách 1: Bấm nút "Tìm kiếm" màu đỏ ở góc trên phải (nếu có)
        val submitBtn = NodeFinder.findSearchSubmitButton(root, screen.x, screen.y)
        if (submitBtn != null) {
            GestureHelper.clickNode(this, submitBtn)
        } else {
            // Cách 2: Bấm vào dòng gợi ý từ khóa đầu tiên bên dưới ô tìm kiếm
            val suggestion = NodeFinder.findSearchSuggestion(root, keyword, screen.y)
            if (suggestion != null) {
                GestureHelper.clickNode(this, suggestion)
            } else {
                // Cách 3: Gửi sự kiện Search/Enter trực tiếp qua AccessibilityAction (an toàn, không bấm nhầm đĩa nhạc)
                var imeTriggered = false
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        imeTriggered = searchInput.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                } catch (_: Exception) {}

                // Fallback chỉ khi ACTION_IME_ENTER thất bại và bàn phím ảo đang mở
                if (!imeTriggered) {
                    GestureHelper.tapCoordinates(this, screen.x * 0.90f, screen.y * 0.93f)
                }
            }
        }

        delay(randomDelay(3500, 5000)) // Chờ kết quả tìm kiếm xuất hiện đầy đủ

        // 4. Chuyển sang tab Video trong kết quả nếu có
        root = getRoot()
        val videoTab = NodeFinder.findVideoTab(root)
        if (videoTab != null) {
            GestureHelper.clickNode(this, videoTab)
            delay(randomDelay(2000, 3000))
        }

        // 5. Click vào video đầu tiên trong kết quả tìm kiếm (vùng màu xanh khoanh)
        root = getRoot()
        val firstVideoCard = NodeFinder.findFirstVideoCard(root, screen.x, screen.y)
        if (firstVideoCard != null) {
            GestureHelper.clickNode(this, firstVideoCard)
        } else {
            // Tọa độ chuẩn 100% của video card đầu tiên (cột 1) dưới creator card: X = 22%, Y = 40%
            GestureHelper.tapCoordinates(this, screen.x * 0.22f, screen.y * 0.40f)
        }

        delay(randomDelay(2500, 3500))

        // Kiểm tra xem video đã mở sang toàn màn hình chưa
        val checkRoot = getRoot()
        val isVideoOpen = NodeFinder.isVideoPlayerScreen(checkRoot)

        if (!isVideoOpen) {
            // Nếu chưa mở, tap thêm 1 lần nữa ở vị trí thumbnail video cột 1 (vùng màu xanh)
            GestureHelper.tapCoordinates(this, screen.x * 0.22f, screen.y * 0.40f)
            delay(2500)
        }

        return true
    }

    /**
     * Bước 4: LIKE_AND_SAVE
     * Thả tim và/hoặc lưu video dựa theo tỷ lệ cấu hình.
     * QUY TẮC BẮT BUỘC: Tap bằng tọa độ vật lý (dispatchGesture / tapCoordinates)
     * TUYỆT ĐỐI KHÔNG dùng ACTION_CLICK vì TikTok chặn synthetic accessibility click trên nút Tim!
     */
    private suspend fun stepLikeAndSave() {
        val screen = screenSize ?: return
        AutomationStateManager.updateStatus(AutoState.INTERACTING, "Đang tương tác...")

        // ── Thả tim ──
        if (config.shouldLike()) {
            val root = getRoot()
            val likeBtn = NodeFinder.findLikeButton(root, screen.x, screen.y)

            var liked = false
            if (likeBtn != null) {
                val bounds = android.graphics.Rect()
                likeBtn.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    // Chạm vật lý trực tiếp lên icon Tim ở cột bên phải
                    val tapX = bounds.centerX().toFloat()
                    val tapY = bounds.centerY().toFloat()
                    liked = GestureHelper.tapCoordinates(this, tapX, tapY)
                }
            }

            // Fallback: nếu tap tọa độ nút tim thất bại hoặc không thấy nút -> Double-tap giữa màn hình
            if (!liked) {
                GestureHelper.doubleTapCenter(this, screen.x.toInt(), screen.y.toInt())
            }

            AutomationStateManager.incrementLikes()
            delay(randomDelay(600, 1200))
        }

        // ── Lưu video / Bookmark ──
        if (config.shouldSave()) {
            val root = getRoot()
            val bookmarkBtn = NodeFinder.findBookmarkButton(root, screen.x, screen.y)

            if (bookmarkBtn != null) {
                val bounds = android.graphics.Rect()
                bookmarkBtn.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    GestureHelper.tapCoordinates(this, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                } else {
                    GestureHelper.clickNode(this, bookmarkBtn, forcePhysicalTap = true)
                }
                AutomationStateManager.incrementSaves()
                delay(randomDelay(500, 900))
            }
        }
    }

    /**
     * Bước 5 & 6: VIEW_COMMENTS & POST_COMMENT
     * Mở khung bình luận, lướt đọc, và tùy chọn viết bình luận.
     * @return true nếu khung bình luận đang mở sau khi hàm kết thúc
     */
    private suspend fun stepViewComments(wantRead: Boolean = true, wantWrite: Boolean = false): Boolean {
        val screen = screenSize ?: return false

        val root = getRoot()
        val screenH = screen.y

        // 1. Kiểm tra xem khung bình luận đã mở sẵn từ trước hay chưa
        val isAlreadyOpen = NodeFinder.findCloseCommentButton(root, screenH) != null

        if (!isAlreadyOpen) {
            // 2. Tìm nút bình luận ở cột bên phải
            val commentBtn = NodeFinder.findCommentButton(root, screen.x, screen.y) ?: return false

            AutomationStateManager.updateStatus(AutoState.READING_COMMENTS, "Mở bình luận...")

            // BẮT BUỘC dùng cử chỉ chạm vật lý (tapCoordinates / dispatchGesture) lên tọa độ nút bình luận
            // TUYỆT ĐỐI KHÔNG dùng ACTION_CLICK vì TikTok có thể kích hoạt accessibility action "viết bình luận" làm bật bàn phím ảo!
            val bounds = android.graphics.Rect()
            commentBtn.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                GestureHelper.tapCoordinates(this, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            } else {
                GestureHelper.clickNode(this, commentBtn, forcePhysicalTap = true)
            }

            delay(randomDelay(1400, 1800)) // Chờ panel bình luận trượt lên ổn định
        }

        // 3. Nếu có cấu hình đọc bình luận: lướt đọc (1–3 lần scroll)
        if (wantRead) {
            dismissKeyboardIfOpen()
            delay(400)

            val scrollCount = (1..3).random()
            repeat(scrollCount) { i ->
                // Đảm bảo bàn phím không che khuất màn hình đọc bình luận
                dismissKeyboardIfOpen()
                AutomationStateManager.updateStatusText("Đọc bình luận (${i + 1}/$scrollCount)...")
                GestureHelper.scrollComments(this, screen.x.toInt(), screen.y.toInt())
                delay(randomDelay(1500, 3500))
            }
        }

        // 4. Viết bình luận (chỉ khi được cấu hình bật)
        if (wantWrite) {
            stepPostComment()
        }

        return true
    }

    /**
     * Bước 6: POST_COMMENT
     * Nhập và gửi bình luận từ từ điển Spintax.
     * Chỉ gọi khi khung bình luận đang mở.
     */
    private suspend fun stepPostComment() {
        val fallbackComments = listOf(
            "Video hay quá bạn ơi ❤️",
            "Nội dung hữu ích quá!",
            "Đỉnh thật sự luôn 👍",
            "Cảm ơn bạn đã chia sẻ ạ!"
        )
        val commentText = SpintaxParser.generateComment(config.commentDictionary)
            ?: fallbackComments.random()

        AutomationStateManager.updateStatus(AutoState.POSTING_COMMENT, "Đang viết bình luận...")

        // 1. Kiểm tra xem ô soạn thảo đã mở chưa
        var root = getRoot()
        var activeInput = NodeFinder.findActiveCommentInput(root)

        // Nếu chưa mở (bàn phím chưa lên): bấm vào thanh nhập ở đáy
        if (activeInput == null) {
            val commentInputBar = NodeFinder.findCommentInput(root)
            if (commentInputBar != null) {
                GestureHelper.clickNode(this, commentInputBar, forcePhysicalTap = true)
            } else {
                // Fallback: tap tọa độ thanh nhập bình luận ở đáy màn hình
                val screen = screenSize
                if (screen != null) {
                    GestureHelper.tapCoordinates(this, screen.x * 0.35f, screen.y - 65f)
                }
            }

            delay(randomDelay(1000, 1400)) // Chờ bàn phím và ô nhập nổi lên ổn định
            root = getRoot()
            activeInput = NodeFinder.findActiveCommentInput(root) ?: NodeFinder.findCommentInput(root)
        }

        if (activeInput == null) {
            android.util.Log.w("TikTokAutoService", "Không tìm thấy ô nhập bình luận sau khi mở bàn phím")
            dismissKeyboardIfOpen()
            return
        }

        // 2. Điền nội dung bình luận vào ô nhập
        NodeFinder.setTextToNode(this, activeInput, commentText)
        delay(randomDelay(1000, 1500)) // Chờ TikTok cập nhật TextWatcher và kích hoạt nút Gửi

        // 3. Tìm nút Gửi (Đăng bình luận)
        val sendRoot = getRoot()
        val sendBtn = NodeFinder.findSendButton(sendRoot, screenSize?.x ?: 0f)

        var sent = false
        if (sendBtn != null) {
            sent = GestureHelper.clickNode(this, sendBtn, forcePhysicalTap = true)
        }

        // Fallback 1: nếu chưa click được, tap thẳng vào tọa độ nút Gửi ở mép phải hàng công cụ
        if (!sent) {
            val inputBounds = android.graphics.Rect()
            activeInput.getBoundsInScreen(inputBounds)
            val screenW = screenSize?.x ?: 1080f
            val tapX = screenW - 100f
            val tapY = if (inputBounds.bottom > 0) inputBounds.bottom + 65f else (screenSize?.y ?: 2400f) * 0.58f
            sent = GestureHelper.tapCoordinates(this, tapX, tapY)
        }

        // Fallback 2: phím Enter/Gửi trên bàn phím ảo
        if (!sent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activeInput.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        delay(randomDelay(1800, 2500)) // Chờ bình luận được gửi lên server
        AutomationStateManager.incrementComments()

        // 4. Ẩn bàn phím sau khi đăng bình luận
        dismissKeyboardIfOpen()
    }

    /**
     * Bước 7: CLOSE_COMMENT
     * Đóng khung bình luận để quay lại màn hình video chính.
     */
    private suspend fun stepCloseComments() {
        val screenH = screenSize?.y ?: resources.displayMetrics.heightPixels.toFloat()

        // 1. Nếu bàn phím đang mở thì ẩn trước
        dismissKeyboardIfOpen()

        val root = getRoot()
        val closeBtn = NodeFinder.findCloseCommentButton(root, screenH)

        if (closeBtn != null) {
            val bounds = android.graphics.Rect()
            closeBtn.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                GestureHelper.tapCoordinates(this, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            } else {
                GestureHelper.clickNode(this, closeBtn, forcePhysicalTap = true)
            }
        } else {
            // Fallback: Back action đóng panel
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        delay(randomDelay(600, 1000))

        // 2. Kiểm tra lại nếu panel vẫn chưa đóng -> Nhấn Back dự phòng để đóng hẳn
        val checkRoot = getRoot()
        if (NodeFinder.findCloseCommentButton(checkRoot, screenH) != null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
        }
    }

    /**
     * Kiểm tra xem bàn phím ảo có đang bật không (qua node input focus) và ẩn ngay lập tức.
     */
    private fun dismissKeyboardIfOpen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && (focused.isEditable || focused.className?.contains("EditText") == true)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
        return false
    }

    /**
     * Bước 8: NEXT_VIDEO
     * Vuốt Bezier từ dưới lên để sang video tiếp theo.
     */
    private suspend fun stepNextVideo() {
        val screen = screenSize ?: return
        AutomationStateManager.updateStatus(AutoState.NEXT_VIDEO, "Chuyển video...")
        GestureHelper.swipeNextVideo(this, screen.x.toInt(), screen.y.toInt())
    }

    // ══════════════════════════════════════════════════════════════════
    //  Tiện ích nội bộ
    // ══════════════════════════════════════════════════════════════════

    /** Lấy root node TikTok (ưu tiên cửa sổ TikTok, fallback sang rootInActiveWindow) */
    private fun getRoot(): AccessibilityNodeInfo? {
        return NodeFinder.getTikTokRoot(this, tiktokPackages.toSet())
    }

    /** Kiểm tra xem lệnh hiện tại có phải PLAY không (để check giữa vòng lặp) */
    private fun isRunningCommand(): Boolean {
        return AutomationStateManager.isRunning()
    }

    /** Wrapper nội bộ cho swipeNextVideo */
    private suspend fun swipeNextVideo() {
        val screen = screenSize ?: return
        GestureHelper.swipeNextVideo(this, screen.x.toInt(), screen.y.toInt())
    }

    /**
     * Random delay có phân phối Gaussian mô phỏng phản xạ tự nhiên của con người.
     * 99.7% giá trị rơi vào khoảng [minMs, maxMs] và tập trung quanh giá trị trung bình.
     */
    private fun randomDelay(minMs: Long, maxMs: Long): Long {
        val mean = (minMs + maxMs) / 2.0
        val stdDev = (maxMs - minMs) / 6.0
        val sample = (java.util.Random().nextGaussian() * stdDev + mean).toLong()
        return sample.coerceIn(minMs, maxMs)
    }

    /**
     * Kiểm tra và đảm bảo TikTok đang ở Foreground.
     */
    private suspend fun ensureTikTokForeground() {
        if (!isTikTokInForeground()) {
            AutomationStateManager.updateStatusText("Đưa TikTok về màn hình chính...")
            stepLaunchApp()
            delay(2000)
        }
    }

    /**
     * Kiểm tra xem TikTok có đang hiển thị ở Foreground hay không.
     */
    private fun isTikTokInForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.packageName?.toString() in tiktokPackages
    }

    /**
     * Back từ video tìm kiếm / kết quả tìm kiếm / trang bất thường về Trang chủ (Home Feed / Đề xuất / Dành cho bạn).
     */
    private suspend fun stepBackToHomeScreen(): Boolean {
        val screenW = screenSize?.x ?: resources.displayMetrics.widthPixels.toFloat()
        val screenH = screenSize?.y ?: resources.displayMetrics.heightPixels.toFloat()

        // Kiểm tra ngay nếu đã ở Trang chủ -> KHÔNG LÀM GÌ THỪA
        if (isAtHomeScreen(getRoot())) {
            AutomationStateManager.updateStatusText("Đang ở Trang chủ")
            return true
        }

        AutomationStateManager.updateStatusText("Quay về Trang chủ...")

        for (attempt in 1..4) {
            val root = getRoot() ?: continue
            if (isAtHomeScreen(root)) {
                AutomationStateManager.updateStatusText("Đã về Trang chủ")
                return true
            }

            // 1. Đóng panel bình luận nếu đang mở
            val closeCommentBtn = NodeFinder.findCloseCommentButton(root, screenH)
            if (closeCommentBtn != null) {
                GestureHelper.clickNode(this, closeCommentBtn)
                delay(800)
                if (isAtHomeScreen(getRoot())) return true
            }

            // 2. Click tab "Trang chủ" ở thanh điều hướng đáy màn hình (ƯU TIÊN để về thẳng Home trong 1 chạm)
            val homeTab = NodeFinder.findHomeTab(root, screenH, screenW)
            if (homeTab != null) {
                GestureHelper.clickNode(this, homeTab)
                delay(1500)
                if (isAtHomeScreen(getRoot())) return true
            }

            // 3. Nếu có nút Back ở góc trên bên trái (đang xem video search hoặc ở search/profile) -> Click nút Back
            val topLeftBackBtn = NodeFinder.findTopLeftBackButton(root, screenW, screenH)
            if (topLeftBackBtn != null) {
                val desc = topLeftBackBtn.contentDescription?.toString()?.lowercase() ?: ""
                val text = topLeftBackBtn.text?.toString()?.lowercase() ?: ""
                val resId = topLeftBackBtn.viewIdResourceName?.lowercase() ?: ""
                if (!desc.contains("live") && !text.contains("live") && !resId.contains("live")) {
                    GestureHelper.clickNode(this, topLeftBackBtn)
                    delay(1200)
                    if (isAtHomeScreen(getRoot())) return true
                }
            }

            // 4. Nếu đang ở màn hình tìm kiếm (có nút Hủy) -> Click để thoát ngay
            val cancelBtn = NodeFinder.findCancelSearchButton(root, screenH)
            if (cancelBtn != null) {
                GestureHelper.clickNode(this, cancelBtn)
                delay(1200)
                if (isAtHomeScreen(getRoot())) return true
            }

            // 5. Nếu chưa về được -> Nhấn phím Back hệ thống
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1200)
            if (isAtHomeScreen(getRoot())) return true
        }

        return isAtHomeScreen(getRoot())
    }

    /**
     * Kiểm tra CHÍNH XÁC xem màn hình hiện tại có thực sự là Trang chủ (Home Feed / Đề xuất / Dành cho bạn) hay không.
     * TUYỆT ĐỐI không bị nhầm lẫn với:
     *   - Video đang xem trong kết quả tìm kiếm (có nút Back ở góc trên bên trái)
     *   - Màn hình lưới kết quả tìm kiếm (có tab Top, Video, Người dùng...)
     *   - Trang cá nhân / Trang trưng bày
     *   - Bảng bình luận đang mở
     */
    private fun isAtHomeScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val screenW = screenSize?.x ?: resources.displayMetrics.widthPixels.toFloat()
        val screenH = screenSize?.y ?: resources.displayMetrics.heightPixels.toFloat()

        // 1. Nếu khung bình luận đang mở -> Chưa phải Home feed sẵn sàng
        if (NodeFinder.findCloseCommentButton(root, screenH) != null) return false

        // 2. Nếu đang ở màn hình Search (ô search đỉnh màn hình, nút Hủy, hoặc tab search Top/Video)
        if (NodeFinder.findSearchInput(root, screenH) != null) return false
        if (NodeFinder.findCancelSearchButton(root, screenH) != null) return false
        if (NodeFinder.isSearchResultsScreen(root, screenH)) return false

        // 3. Nếu đang ở Trang cá nhân / Trưng bày / Shop
        if (NodeFinder.isProfileOrShowcaseScreen(root)) return false

        // 4. Nếu có nút Back (<-) ở góc trên bên trái -> Đang ở màn hình con (Search video, profile, hashtag...)
        // TUYỆT ĐỐI không phải Trang chủ vì Trang chủ TikTok không bao giờ có nút Back ở góc trên bên trái!
        if (NodeFinder.findTopLeftBackButton(root, screenW, screenH) != null) return false

        // 5. Kiểm tra dấu hiệu đặc trưng BẮT BUỘC của Trang chủ TikTok (Home Feed):
        val topFeedTab = NodeFinder.findTopFeedTab(root, screenH)

        // Dấu hiệu 5.1: Có tab Feed trên đỉnh ("Đề xuất", "Dành cho bạn", "Đã follow", "Bạn bè", "Khám phá")
        // Ngay cả khi video hiện tại là Live card hay Quảng cáo (chưa có nút like thông thường),
        // sự hiện diện của topFeedTab chứng minh 100% người dùng đang ở trên Home Feed
        if (topFeedTab != null) {
            return true
        }

        // Dấu hiệu 5.2: Tab "Trang chủ" ở đáy màn hình đang được chọn
        val homeTab = NodeFinder.findHomeTab(root, screenH, screenW)
        if (homeTab != null) {
            val isHomeTabSelected = homeTab.isSelected || homeTab.isChecked ||
                    (homeTab.parent?.isSelected == true) ||
                    (homeTab.contentDescription?.contains("đã chọn", ignoreCase = true) == true) ||
                    (homeTab.contentDescription?.contains("selected", ignoreCase = true) == true)

            if (isHomeTabSelected) {
                return true
            }
        }

        // Dấu hiệu 5.3: Hoặc có video player đang phát (nút tim / comment)
        val hasVideoPlayer = NodeFinder.isVideoPlayerScreen(root)
        return hasVideoPlayer
    }
}
