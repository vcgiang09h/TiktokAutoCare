package giangvc.cntt.ntu.autocare.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bộ tạo cử chỉ (gesture) giả lập hành vi người thật cho TikTokAutoService.
 *
 * Mọi cử chỉ đều:
 *  - Dùng đường cong Bezier bậc 2 (không bao giờ thẳng đứng) → chống fingerprint
 *  - Mô phỏng cung ngón cái (Thumb Arc Motion) tự nhiên của người thật
 *  - Có thời gian ngẫu nhiên (Gaussian jitter)
 *  - An toàn chống treo: suspendCancellableCoroutine + timeout bảo vệ
 *  - KHÔNG gọi node.recycle() (deprecated trên SDK 36)
 */
object GestureHelper {

    private val rng = Random.Default
    private const val GESTURE_TIMEOUT_MS = 2500L

    // ══════════════════════════════════════════════════════════════════
    //  1. Swipe to Next Video — Bezier từ dưới lên (Thumb Arc Curve)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Vuốt sang video tiếp theo với đường cong Bezier bậc 2 mô phỏng ngón tay thật.
     *
     * Đặc tả:
     *  - Xuất phát: vùng thấp màn hình (72%–80% chiều cao)
     *  - Điểm đích: vùng cao màn hình (18%–26% chiều cao)
     *  - TUYỆT ĐỐI giữ trục X lệch rất nhẹ (±6px) và cung cong nhỏ (8px-14px)
     *    để KHÔNG BAO GIỜ kích hoạt cử chỉ vuốt ngang mở Profile tác giả của TikTok!
     *  - Thời gian vuốt dứt khoát 220ms–320ms để tạo lực cuộn (fling) chắc chắn sang video sau.
     */
    suspend fun swipeNextVideo(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val centerX = screenWidth / 2f
        val startY = screenHeight * rng.nextFloat(0.72f, 0.80f)
        val endY = screenHeight * rng.nextFloat(0.18f, 0.26f)

        // Lệch trục X tối thiểu (±6px) — chống kích hoạt lướt ngang sang profile
        val startX = centerX + rng.nextFloat(-6f, 6f)
        val endX = centerX + rng.nextFloat(-6f, 6f)

        // Cung cong Bezier rất nhẹ (8px - 14px) chống bot nhưng an toàn tuyệt đối
        val controlOffsetX = rng.nextFloat(8f, 14f) * if (rng.nextBoolean()) 1 else -1
        val controlX = (startX + endX) / 2f + controlOffsetX
        val controlY = (startY + endY) / 2f

        val duration = rng.nextLong(220, 320)

        return dispatchBezierSwipe(service, startX, startY, controlX, controlY, endX, endY, duration)
    }

    // ══════════════════════════════════════════════════════════════════
    //  2. Scroll Comments — vuốt đọc bình luận (giữa màn hình đi lên)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Lướt đọc bình luận trong khung comment.
     * Vuốt từ 75% chiều cao lên 40% chiều cao (khu vực comment panel).
     */
    suspend fun scrollComments(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val startX = screenWidth * rng.nextFloat(0.45f, 0.55f)
        val startY = screenHeight * rng.nextFloat(0.72f, 0.78f)
        val endX = screenWidth * rng.nextFloat(0.45f, 0.55f)
        val endY = screenHeight * rng.nextFloat(0.38f, 0.44f)

        // Bezier điều khiển nhẹ, trông tự nhiên hơn scroll thẳng
        val controlX = (startX + endX) / 2f + rng.nextFloat(-25f, 25f)
        val controlY = (startY + endY) / 2f

        // Scroll đọc comment chậm rãi
        val duration = rng.nextLong(320, 500)

        return dispatchBezierSwipe(service, startX, startY, controlX, controlY, endX, endY, duration)
    }

    // ══════════════════════════════════════════════════════════════════
    //  3. Click trên AccessibilityNodeInfo
    // ══════════════════════════════════════════════════════════════════

    /**
     * Click vào một AccessibilityNodeInfo với offset ngẫu nhiên nhỏ trong vùng node.
     *
     * @param forcePhysicalTap Nếu true, bỏ qua ACTION_CLICK và chạm trực tiếp bằng dispatchGesture (cần thiết cho các nút tương tác TikTok).
     */
    suspend fun clickNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        forcePhysicalTap: Boolean = false
    ): Boolean {
        if (!forcePhysicalTap && node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // Fallback: tap vào tọa độ trung tâm của node với jitter tự nhiên
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.isEmpty) return false

        val tapX = bounds.centerX().toFloat() + rng.nextFloat(-6f, 6f)
        val tapY = bounds.centerY().toFloat() + rng.nextFloat(-6f, 6f)

        return dispatchTap(service, tapX, tapY)
    }

    // ══════════════════════════════════════════════════════════════════
    //  4. Double-tap giữa màn hình (thả tim kiểu TikTok)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Double-tap vào giữa màn hình (lệch tâm ±40px) để thả tim.
     * TikTok kích hoạt like animation khi double-tap vào vùng video.
     */
    suspend fun doubleTapCenter(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val tapX = screenWidth / 2f + rng.nextFloat(-40f, 40f)
        val tapY = screenHeight / 2f + rng.nextFloat(-40f, 40f)

        // Tap đầu tiên
        val firstTap = dispatchTap(service, tapX, tapY, duration = 50)
        if (!firstTap) return false

        // Delay ngẫu nhiên giữa 2 tap (80–130ms)
        kotlinx.coroutines.delay(rng.nextLong(80, 130))

        // Tap thứ hai (lệch nhẹ so với tap đầu cho tự nhiên)
        val tapX2 = tapX + rng.nextFloat(-8f, 8f)
        val tapY2 = tapY + rng.nextFloat(-8f, 8f)
        return dispatchTap(service, tapX2, tapY2, duration = 50)
    }

    // ══════════════════════════════════════════════════════════════════
    //  5. Primitives — Bezier swipe & single tap (Timeout Protected)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tap tại tọa độ (x, y) cụ thể (public API cho fallback).
     */
    suspend fun tapCoordinates(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = 60L
    ): Boolean {
        return dispatchTap(service, x, y, duration)
    }

    /**
     * Dispatch 1 cử chỉ vuốt theo đường cong Bezier bậc 2 được bảo vệ bởi timeout.
     */
    private suspend fun dispatchBezierSwipe(
        service: AccessibilityService,
        startX: Float, startY: Float,
        controlX: Float, controlY: Float,
        endX: Float, endY: Float,
        duration: Long
    ): Boolean {
        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val path = Path().apply {
                    moveTo(startX, startY)
                    quadTo(controlX, controlY, endX, endY)
                }

                val stroke = GestureDescription.StrokeDescription(path, 0L, duration)

                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                service.dispatchGesture(gesture, callback, null)
            }
        }
        return result ?: false
    }

    /**
     * Dispatch 1 cử chỉ tap tại tọa độ (x, y) được bảo vệ bởi timeout.
     */
    private suspend fun dispatchTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = 60L
    ): Boolean {
        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val path = Path().apply {
                    moveTo(x, y)
                }

                val stroke = GestureDescription.StrokeDescription(path, 0L, duration)

                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                service.dispatchGesture(gesture, callback, null)
            }
        }
        return result ?: false
    }

    // ══════════════════════════════════════════════════════════════════
    //  6. Tiện ích
    // ══════════════════════════════════════════════════════════════════

    /** Lấy kích thước màn hình thiết bị (px) */
    fun getScreenSize(service: AccessibilityService): PointF {
        val metrics = service.resources.displayMetrics
        return PointF(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    /** Random long trong khoảng [from, until) an toàn */
    private fun Random.nextLong(from: Long, until: Long): Long {
        if (until <= from) return from
        return nextLong(from, until)
    }

    /** Random float trong khoảng [from, until) an toàn */
    private fun Random.nextFloat(from: Float, until: Float): Float {
        if (until <= from) return from
        return from + nextFloat() * (until - from)
    }
}
