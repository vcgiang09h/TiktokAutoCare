package giangvc.cntt.ntu.autocare.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Bộ tìm kiếm phần tử UI TikTok thông minh — chống thay đổi viewId giữa các bản cập nhật.
 *
 * Chiến lược tìm kiếm (theo thứ tự ưu tiên):
 *  1. contentDescription — hỗ trợ cả tiếng Việt lẫn tiếng Anh
 *  2. text — nội dung văn bản hiển thị
 *  3. viewIdResourceName — id tài nguyên (ít ổn định, TikTok hay đổi)
 *  4. Vị trí tương đối trên màn hình (cột nút bên phải)
 *
 * Thứ tự cột nút tương tác bên phải TikTok (từ trên xuống):
 *   Avatar (follow) → Tim (like) → Bình luận (comment) → Lưu (bookmark/save) → Chia sẻ (share)
 *
 * QUAN TRỌNG: Không gọi node.recycle() — deprecated trên SDK mới.
 */
object NodeFinder {

    // ══════════════════════════════════════════════════════════════════
    //  1. Các keyword nhận dạng nút TikTok (đa ngôn ngữ)
    // ══════════════════════════════════════════════════════════════════

    private val SEARCH_KEYWORDS = setOf(
        "search", "tìm kiếm", "搜索", "buscar", "rechercher"
    )

    private val LIKE_KEYWORDS = setOf(
        "like", "thích", "tim", "❤", "いいね", "me gusta",
        "j'aime", "curtir"
    )

    private val COMMENT_KEYWORDS = setOf(
        "comment", "bình luận", "bình_luận", "comments",
        "評論", "comentar", "commenter"
    )

    private val BOOKMARK_KEYWORDS = setOf(
        "save", "lưu", "collect", "bookmark", "收藏",
        "guardar", "sauvegarder"
    )

    private val SHARE_KEYWORDS = setOf(
        "share", "chia sẻ", "chia_se", "分享",
        "compartir", "partager"
    )

    private val HOME_KEYWORDS = setOf(
        "trang chủ", "trang chu", "home", "首页"
    )

    private val FOR_YOU_KEYWORDS = setOf(
        "đề xuất", "de xuat", "đề xuất cho bạn", "dành cho bạn", "danh cho ban",
        "for you", "foryou", "đã follow", "đang theo dõi", "following", "bạn bè", "friends", "khám phá"
    )

    private val CANCEL_SEARCH_KEYWORDS = setOf(
        "hủy", "huy", "cancel", "hủy bỏ", "quay lại", "back", "trở lại"
    )

    // ── Dấu hiệu Quảng cáo (Ad / Sponsored) ──
    // Nhãn tài trợ chính thức (chuỗi độc lập ngắn, không dùng contains trên caption)
    private val AD_SPONSORED_BADGES = setOf(
        "được tài trợ", "sponsored", "paid partnership",
        "đối tác trả phí", "tài trợ bởi", "sponsored by", "nội dung được tài trợ"
    )

    // Các hành động CTA rõ ràng của quảng cáo tải app hoặc truy cập web
    // TUYỆT ĐỐI KHÔNG đưa "tìm hiểu thêm", "mua ngay", "xem thêm" vào vì đó là của AI tag, disclaimer hoặc TikTok Shop
    private val AD_CTA_EXPLICIT_ACTIONS = setOf(
        "cài đặt ngay", "tải ngay", "tải ứng dụng", "install now", "download now",
        "tải về ngay", "đăng ký ngay", "sign up", "nhận ưu đãi", "claim offer",
        "get offer", "xem trang web", "truy cập ngay", "visit site", "chơi ngay", "play now"
    )

    private val AD_VIEW_ID_KEYWORDS = setOf(
        "ad_badge", "ad_tag", "ad_cta", "sponsored_label", "commercial_tag"
    )

    // ── Dấu hiệu LIVE Stream (đặc biệt: badge 'Đang LIVE' và 'Nhấn để xem LIVE' ở góc dưới trái) ──
    private val LIVE_EXPLICIT_BADGES = setOf(
        "đang live", "dang live", "đang phát live",
        "nhấn để xem live", "chạm để xem live", "bấm để xem live",
        "nhan de xem live", "cham de xem live", "bam de xem live",
        "xem phiên live", "tap to watch live", "tap into live",
        "watch live", "vào xem live", "vào live"
    )

    private val LIVE_VIEW_ID_KEYWORDS = setOf(
        "live_tag", "live_badge", "live_window", "live_stream",
        "live_status", "live_anim", "live_card", "live_container",
        "live_header", "live_icon", "live_anchor", "feed_live"
    )

    private const val MAX_TREE_NODES_VISITED = 1200

    // ══════════════════════════════════════════════════════════════════
    //  2. Nút tìm kiếm (Search)
    // ══════════════════════════════════════════════════════════════════

    /** Tìm nút Search/Tìm kiếm trên màn hình chính TikTok */
    fun findSearchButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findByDescriptionOrText(root, SEARCH_KEYWORDS)
    }

    /**
     * Tìm nút Submit "Tìm kiếm" nằm ở góc trên cùng bên phải (bên cạnh ô nhập tìm kiếm).
     * Có chữ "Tìm kiếm" hoặc "Search" ở vùng X >= 60% và Y <= 14% chiều cao màn hình.
     */
    /**
     * Tìm nút Submit "Tìm kiếm" nằm ở góc trên cùng bên phải (bên cạnh ô nhập tìm kiếm).
     * BẮT BUỘC có chữ "Tìm kiếm" hoặc "Search".
     * TUYỆT ĐỐI không trả về nút Clear / Xóa văn bản (X).
     */
    fun findSearchSubmitButton(
        root: AccessibilityNodeInfo?,
        screenWidth: Float,
        screenHeight: Float
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val keywords = setOf("tìm kiếm", "tim kiem", "search")

        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            // BỎ QUA nếu là nút Xóa (Clear button bên trong ô nhập)
            val isClearBtn = text.contains("xóa") || desc.contains("xóa") ||
                            text.contains("clear") || desc.contains("clear") ||
                            text == "✕" || text == "×" || text == "x"
            if (isClearBtn) continue

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            val isTopRight = bounds.left >= screenWidth * 0.65f && bounds.top <= screenHeight * 0.14f && bounds.bottom > 0

            if (isTopRight && keywords.any { text.contains(it) || desc.contains(it) }) {
                return node // Chỉ trả về khi khớp chính xác chữ "Tìm kiếm" hoặc "Search"
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Không trả về node tùy ý để chống bấm nhầm nút Clear (X)
        return null
    }

    /**
     * Tìm dòng gợi ý tìm kiếm (search suggestion) đầu tiên xuất hiện dưới ô tìm kiếm khi vừa gõ từ khóa.
     */
    fun findSearchSuggestion(
        root: AccessibilityNodeInfo?,
        keyword: String,
        screenHeight: Float
    ): AccessibilityNodeInfo? {
        root ?: return null
        val lowerKw = keyword.trim().lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            // Khu vực gợi ý tìm kiếm (Y từ 8% đến 35% màn hình)
            val isInSuggestionArea = bounds.top >= screenHeight * 0.08f && bounds.top <= screenHeight * 0.35f

            if (isInSuggestionArea) {
                val matchesKw = text.contains(lowerKw) || desc.contains(lowerKw)
                val isNotEditText = node.className?.toString()?.contains("EditText") != true

                if (matchesKw && isNotEditText) {
                    var clickableNode: AccessibilityNodeInfo? = node
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    return clickableNode ?: node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Tìm ô nhập liệu tìm kiếm (SearchEditText trên thanh tìm kiếm đỉnh màn hình).
     * BẮT BUỘC nằm ở phần trên cùng của màn hình (Y <= 15%).
     * Tuyệt đối không lấy ô nhập bình luận ở đáy màn hình.
     */
    fun findSearchInput(root: AccessibilityNodeInfo?, screenHeight: Float = 0f): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            if (node.isEditable && node.isEnabled) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)

                // Ô tìm kiếm phải nằm ở đỉnh màn hình: bounds.top <= 15% chiều cao (hoặc < 350px)
                val isTopArea = if (screenHeight > 0) {
                    bounds.top <= screenHeight * 0.15f
                } else {
                    bounds.top < 350
                }

                if (isTopArea && bounds.bottom > 0) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  3. Nút Tab "Video" trong kết quả tìm kiếm
    // ══════════════════════════════════════════════════════════════════

    /** Tìm tab "Video" trong kết quả tìm kiếm TikTok */
    fun findVideoTab(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findByDescriptionOrText(root, setOf("video", "videos", "Video"))
    }

    /**
     * Tìm thẻ video đầu tiên trong lưới kết quả tìm kiếm TikTok (vùng màu xanh khoanh).
     *
     * Tiêu chí nhận diện chính xác:
     *   - BẮT BUỘC nằm dưới phần Creator Profile Header (bounds.top >= screenHeight * 0.26f).
     *   - BẮT BUỘC loại bỏ hoàn toàn các phần tử Profile (chứa "follow", "trưng bày", "sản phẩm"...).
     *   - Nằm ở cột 1 bên trái (bounds.left < screenWidth * 0.45f).
     *   - Kích thước chuẩn video thumbnail (chiều rộng >= 18%, chiều cao >= 10% màn hình).
     */
    fun findFirstVideoCard(
        root: AccessibilityNodeInfo?,
        screenWidth: Float,
        screenHeight: Float
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0

        val profileKeywords = setOf(
            "follow", "follower", "đang follow", "theo dõi", "trưng bày",
            "sản phẩm", "cửa hàng", "shop", "xem trang", "người dùng"
        )

        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            val width = bounds.width()
            val height = bounds.height()

            val minWidth = (screenWidth * 0.18f).toInt()
            val minHeight = (screenHeight * 0.10f).toInt()

            val isCorrectPosition = bounds.left < screenWidth * 0.45f &&
                    bounds.top >= screenHeight * 0.26f &&
                    bounds.top <= screenHeight * 0.60f &&
                    width >= minWidth && height >= minHeight

            if (isCorrectPosition && node.isClickable) {
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                val isProfileElement = profileKeywords.any { kw ->
                    text.contains(kw) || desc.contains(kw)
                }

                if (!isProfileElement) {
                    candidates.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Chọn candidate ở hàng trên cùng của video grid (top nhỏ nhất), cột 1 (left nhỏ nhất)
        return candidates.minWithOrNull(
            compareBy<AccessibilityNodeInfo> {
                val b = android.graphics.Rect()
                it.getBoundsInScreen(b)
                b.top
            }.thenBy {
                val b = android.graphics.Rect()
                it.getBoundsInScreen(b)
                b.left
            }
        )
    }

    /**
     * Tìm nút hoặc tab "Trang chủ" (Home) ở thanh điều hướng đáy màn hình.
     * Tab "Trang chủ" BẮT BUỘC nằm ở góc dưới cùng bên TRÁI màn hình:
     *   - X <= 32% chiều rộng màn hình (bounds.left <= screenWidth * 0.32f hoặc < 350px)
     *   - Y >= 80% chiều cao màn hình (bounds.bottom >= screenHeight * 0.80f)
     * TUYỆT ĐỐI KHÔNG tìm ở nửa bên phải màn hình (tránh bấm nhầm đĩa nhạc, nút share, nút profile).
     */
    fun findHomeTab(
        root: AccessibilityNodeInfo?,
        screenHeight: Float = 0f,
        screenWidth: Float = 0f
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val minBottom = if (screenHeight > 0f) screenHeight * 0.80f else 1800f
        val maxLeft = if (screenWidth > 0f) screenWidth * 0.32f else 350f

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // BẮT BUỘC: Tab Trang chủ nằm ở góc dưới bên TRÁI
            if (bounds.bottom >= minBottom && bounds.left <= maxLeft && bounds.width() > 0) {
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val resId = node.viewIdResourceName?.lowercase() ?: ""

                // Bỏ qua nếu là đĩa nhạc hoặc âm thanh
                if (resId.contains("sound") || resId.contains("music") || resId.contains("audio") || resId.contains("mqb")) {
                    continue
                }

                if (HOME_KEYWORDS.any { text == it || desc == it || (text.startsWith(it) && text.length <= 12) || (desc.startsWith(it) && desc.length <= 12) }) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }

    /**
     * Tìm tab nguồn cấp ở đỉnh màn hình: "Đề xuất", "Dành cho bạn", "Đã follow", "Đang follow", "Bạn bè", "Khám phá"
     * BẮT BUỘC nằm ở phần trên của màn hình (bounds.top <= screenHeight * 0.22f).
     * Đây là dấu hiệu độc quyền của Trang chủ (Home Feed), không bao giờ xuất hiện ở màn hình Search hay Profile.
     */
    fun findTopFeedTab(root: AccessibilityNodeInfo?, screenHeight: Float): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val maxTop = if (screenHeight > 0f) screenHeight * 0.22f else 450f

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.top <= maxTop && bounds.bottom > 0 && bounds.width() > 0) {
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                if (FOR_YOU_KEYWORDS.any { text == it || desc == it || (text.contains(it) && text.length <= 25) || (desc.contains(it) && desc.length <= 25) }) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }

    /**
     * Tìm nút Back (<- hoặc Quay lại / Trở lại) ở góc trên bên trái màn hình.
     * Nút này xuất hiện khi đang xem video từ kết quả tìm kiếm, trang kết quả tìm kiếm, trang cá nhân,
     * và TUYỆT ĐỐI KHÔNG BAO GIỜ được nhận nhầm nút LIVE Stream ở góc trên bên trái Trang chủ!
     */
    fun findTopLeftBackButton(root: AccessibilityNodeInfo?, screenWidth: Float, screenHeight: Float): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val maxRight = if (screenWidth > 0f) screenWidth * 0.25f else 300f
        val maxBottom = if (screenHeight > 0f) screenHeight * 0.15f else 400f

        val backKeywords = setOf(
            "quay lại", "back", "trở lại", "trở về", "hủy", "cancel",
            "navigate up", "đóng", "close", "arrow_back"
        )
        val backIds = setOf(
            "back", "return", "arrow_back", "nav_back", "btn_back", "iv_back", "icon_back", "action_bar_back"
        )
        val liveKeywords = setOf(
            "live", "trực tiếp", "phát trực tiếp"
        )

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Nằm ở góc trên bên trái
            if (bounds.left >= 0 && bounds.right <= maxRight && bounds.top >= 0 && bounds.bottom <= maxBottom && bounds.width() > 0) {
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val resId = node.viewIdResourceName?.lowercase() ?: ""

                // LOẠI TRỪ TUYỆT ĐỐI nút LIVE ở góc trên bên trái Trang chủ
                val isLive = liveKeywords.any { desc.contains(it) || text.contains(it) || resId.contains(it) }
                if (!isLive) {
                    val matchesText = backKeywords.any { desc.contains(it) || text.contains(it) }
                    val matchesId = backIds.any { resId.contains(it) }

                    // BẮT BUỘC phải là nút clickable và có text/id chứng minh là nút Back thực sự
                    if (node.isClickable && (matchesText || matchesId)) {
                        return node
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }

    /**
     * Nhận diện màn hình Kết quả tìm kiếm (Search Results).
     * Dấu hiệu: Dãy tab phân loại kết quả tìm kiếm ("Top", "Video", "Người dùng", "Âm thanh") ở phía trên.
     */
    fun isSearchResultsScreen(root: AccessibilityNodeInfo?, screenHeight: Float): Boolean {
        root ?: return false
        val maxTop = if (screenHeight > 0f) screenHeight * 0.25f else 500f
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        var hasTopTab = false
        var hasOtherSearchTab = false
        val otherTabs = setOf("video", "người dùng", "nguoi dung", "âm thanh", "am thanh", "users", "sounds", "hashtags", "cửa hàng")

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.top <= maxTop && bounds.bottom > 0) {
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                if (text == "top" || desc == "top") {
                    hasTopTab = true
                }
                if (otherTabs.any { text == it || desc == it }) {
                    hasOtherSearchTab = true
                }
                if (hasTopTab && hasOtherSearchTab) {
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return false
    }

    /** Tìm tab "Dành cho bạn" (For You) hoặc tab nguồn cấp chính */
    fun findForYouTab(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findByDescriptionOrText(root, FOR_YOU_KEYWORDS)
    }

    /** Tìm nút Hủy / Cancel trên thanh tìm kiếm để đóng tìm kiếm (BẮT BUỘC nằm ở đỉnh màn hình Y <= 18%) */
    fun findCancelSearchButton(root: AccessibilityNodeInfo?, screenHeight: Float = 0f): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val maxTop = if (screenHeight > 0f) screenHeight * 0.18f else 400f

        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.bottom <= maxTop && bounds.width() > 0) {
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""

                if (CANCEL_SEARCH_KEYWORDS.any { text == it || desc == it || (text.startsWith(it) && text.length <= 10) }) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  4. Nút tương tác bên phải video (Like, Comment, Save, Share)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tìm nút Thả tim (Like) trên màn hình video TikTok.
     * BẮT BUỘC nằm ở cột hành động bên phải:
     *   - X >= 70% chiều rộng màn hình
     *   - Y nằm trong khoảng 38% - 82% chiều cao màn hình (dưới avatar, trên comment)
     * TUYỆT ĐỐI KHÔNG nhận nhầm Avatar tác giả hoặc bio/caption có chứa chữ "thích".
     */
    fun findLikeButton(
        root: AccessibilityNodeInfo?,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val minLeft = if (screenWidth > 0f) screenWidth * 0.70f else 700f
        val minY = if (screenHeight > 0f) screenHeight * 0.38f else 800f
        val maxY = if (screenHeight > 0f) screenHeight * 0.82f else 1850f

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.left >= minLeft && bounds.top >= minY && bounds.bottom <= maxY && bounds.width() > 0) {
                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                val entryName = viewId.substringAfterLast("/")
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                // Loại bỏ avatar, profile, sound, music (chống bấm nhầm mở Profile tác giả)
                val isProfileOrSound = entryName in setOf("avatar", "profile", "xl7", "h4b", "sound", "music", "mqb") ||
                        desc.contains("hồ sơ") || desc.contains("follow") || desc.contains("theo dõi") || desc.contains("âm thanh")

                if (!isProfileOrSound) {
                    val matchesLikeDesc = desc.contains("thích video") || desc.contains("lượt thích") ||
                            desc.contains("likes") || desc == "thích" || desc == "like"
                    val matchesLikeId = entryName == "ew6" || entryName == "evt"

                    if (matchesLikeDesc || matchesLikeId) {
                        return node
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return findByDescriptionOrText(root, LIKE_KEYWORDS)
    }

    /**
     * Tìm nút Bình luận (Comment) ở cột bên phải màn hình video TikTok.
     * TUYỆT ĐỐI KHÔNG bắt nhầm ô nhập liệu "Thêm bình luận..." ở đáy màn hình.
     */
    fun findCommentButton(
        root: AccessibilityNodeInfo?,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val minLeft = if (screenWidth > 0f) screenWidth * 0.70f else 700f
        val minY = if (screenHeight > 0f) screenHeight * 0.45f else 950f
        val maxY = if (screenHeight > 0f) screenHeight * 0.85f else 1850f

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++

            // BỎ QUA HOÀN TOÀN nếu là ô nhập liệu EditText (chống bật bàn phím ảo)
            if (node.isEditable || node.className?.contains("EditText") == true ||
                node.className?.contains("AutoCompleteTextView") == true
            ) {
                continue
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.left >= minLeft && bounds.top >= minY && bounds.bottom <= maxY && bounds.width() > 0) {
                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                val entryName = viewId.substringAfterLast("/")
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""

                // Bỏ qua nếu có dấu hiệu ô nhập text
                if (entryName.contains("edit") || entryName.contains("input") ||
                    text.contains("thêm bình luận") || desc.contains("thêm bình luận")
                ) {
                    continue
                }

                if (desc.contains("bình luận") || desc.contains("comment") || entryName == "dnm" || entryName == "dk4") {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // TUYỆT ĐỐI không fallback sang findByDescriptionOrText để tránh click nhầm vào EditText "Thêm bình luận..."
        return null
    }

    /**
     * Tìm nút Lưu / Bookmark (Yêu thích) ở cột bên phải màn hình video TikTok.
     */
    fun findBookmarkButton(
        root: AccessibilityNodeInfo?,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f
    ): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val minLeft = if (screenWidth > 0f) screenWidth * 0.70f else 700f
        val minY = if (screenHeight > 0f) screenHeight * 0.55f else 1200f
        val maxY = if (screenHeight > 0f) screenHeight * 0.88f else 2000f

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.left >= minLeft && bounds.top >= minY && bounds.bottom <= maxY && bounds.width() > 0) {
                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                val entryName = viewId.substringAfterLast("/")
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                val isExclude = entryName in setOf("sound", "music", "mqb", "chia sẻ", "share") ||
                        desc.contains("âm thanh") || desc.contains("chia sẻ") || desc.contains("share")

                if (!isExclude) {
                    val matchesBookmark = desc.contains("yêu thích") || desc.contains("favorite") ||
                            desc.contains("lưu") || desc.contains("bookmark") || desc.contains("save") ||
                            entryName == "gge" || entryName == "gfl"

                    if (matchesBookmark) {
                        return node
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return findByDescriptionOrText(root, BOOKMARK_KEYWORDS)
    }

    /** Tìm nút Chia sẻ (Share) */
    fun findShareButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findByDescriptionOrText(root, SHARE_KEYWORDS)
    }

    // ══════════════════════════════════════════════════════════════════
    //  5. Ô nhập bình luận & nút Gửi
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tìm ô nhập bình luận ("Thêm bình luận..." / "Add a comment...").
     * Ưu tiên: EditText có thể edit + hint/text chứa "bình luận" hoặc "comment", hoặc View bọc ngoài.
     */
    fun findCommentInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val commentInputKeywords = setOf(
            "thêm bình luận", "add a comment", "comment...", "bình luận...",
            "add comment", "write a comment"
        )
        // Thử tìm qua description/text trước
        val byDesc = findByDescriptionOrText(root, commentInputKeywords)
        if (byDesc != null) return byDesc

        // Fallback: EditText có thể nhập được
        return findFirstEditableNode(root)
    }

    /**
     * Tìm ô nhập bình luận đang active/focused (khi bàn phím hoặc dialog soạn thảo đang mở).
     * Tiêu chí:
     *  - Node có isFocused = true và là EditText hoặc isEditable = true.
     *  - Hoặc node EditText có vị trí ở nửa trên (Y < 1800) khi bàn phím đẩy lên.
     */
    fun findActiveCommentInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        // 1. Kiểm tra node đang nắm FOCUS_INPUT
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && (focused.isEditable || focused.className?.contains("EditText") == true)) {
            return focused
        }

        // 2. BFS tìm EditText đang focused hoặc nằm ở vị trí soạn thảo
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var fallbackEditor: AccessibilityNodeInfo? = null
        val bounds = Rect()

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val isEdit = node.isEditable || node.className?.contains("EditText") == true
            if (isEdit && node.isEnabled) {
                node.getBoundsInScreen(bounds)
                if (node.isFocused) {
                    return node
                }
                // Nếu không focused nhưng nằm ở vị trí soạn thảo (bị bàn phím đẩy lên, Y < 1800 và Y > 200)
                if (bounds.bottom in 200..1800 && bounds.width() > 0) {
                    fallbackEditor = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return fallbackEditor
    }

    /**
     * Tìm nút Gửi / Đăng bình luận.
     * TikTok sử dụng:
     *   - contentDescription: "Đăng bình luận", "Gửi", "Send", "Post", "Submit"
     *   - resource-id: "c93", "send_btn", "btn_send", "send", "post", "publish"
     *   - Fallback vị trí: Nút clickable ở mép phải (X >= 78% màn hình) trên thanh công cụ soạn thảo.
     */
    fun findSendButton(root: AccessibilityNodeInfo?, screenWidth: Float = 0f): AccessibilityNodeInfo? {
        root ?: return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val minLeft = if (screenWidth > 0f) screenWidth * 0.78f else 800f
        var fallbackRightButton: AccessibilityNodeInfo? = null
        val bounds = Rect()

        val sendKeywords = setOf(
            "send", "gửi", "post", "đăng", "submit", "publish", "enviar", "发布",
            "đăng bình luận", "gửi bình luận"
        )
        val sendIds = setOf("c93", "send", "publish", "post", "send_btn", "btn_send", "iv_send", "djp")

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val entryName = resId.substringAfterLast("/")

            val matchesKeyword = sendKeywords.any { kw -> desc.contains(kw) || text.contains(kw) }
            val matchesId = sendIds.any { entryName.contains(it) }

            if (matchesKeyword || matchesId) {
                return node
            }

            // Lưu lại button nằm sát mép phải (X > 78%) ở hàng công cụ soạn thảo (Y trong 800..1800)
            if (node.isClickable && (node.className?.contains("Button") == true || node.className?.contains("ImageView") == true)) {
                node.getBoundsInScreen(bounds)
                if (bounds.left >= minLeft && bounds.top in 800..1800 && bounds.width() > 0) {
                    fallbackRightButton = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return fallbackRightButton
    }

    /**
     * Tìm nút đóng khung bình luận (nút X hoặc "Đóng").
     * QUY TẮC:
     *  - Chỉ nhận diện các nút mang ý nghĩa ĐÓNG ("đóng", "close", "✕", "×", id chứa "close").
     *  - TUYỆT ĐỐI KHÔNG tìm chữ "back" hay "quay lại" vì sẽ bắt nhầm mô tả bài hát ("background music", "quay lại").
     *  - Nút đóng bình luận nằm ở nửa trên khung bình luận (Y <= 70% màn hình), KHÔNG BAO GIỜ nằm ở góc dưới đáy màn hình.
     */
    fun findCloseCommentButton(root: AccessibilityNodeInfo?, screenHeight: Float = 0f): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val maxBottom = if (screenHeight > 0f) screenHeight * 0.70f else Float.MAX_VALUE

        val closeKeywords = setOf("close", "đóng", "关闭", "✕", "×", "x")

        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Nút đóng comment không bao giờ nằm ở góc dưới đáy màn hình (vùng âm thanh / điều hướng đáy)
            if (screenHeight > 0f && bounds.top >= maxBottom) {
                continue
            }

            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""

            // Bỏ qua các thành phần âm thanh / video
            if (resId.contains("sound") || resId.contains("music") || resId.contains("audio") || resId.contains("mqb")) {
                continue
            }

            val isCloseMatch = closeKeywords.any { text == it || desc == it || desc.startsWith("đóng") || desc.startsWith("close") }
            val isIdMatch = resId.contains("close") || resId.contains("btn_close")

            if ((isCloseMatch || isIdMatch) && bounds.width() > 0 && bounds.height() > 0) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  6. Phát hiện Quảng cáo & LIVE (chuẩn xác cao, chống nhận diện nhầm)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Nhận diện thông minh xem màn hình hiện tại có phải Quảng cáo hay LIVE Stream không.
     *
     * Cơ chế lọc chính xác:
     *   - Quét duy nhất 1 lần (Single-pass BFS) qua cây UI, dừng ngay khi phát hiện.
     *   - LOẠI TRỪ TUYỆT ĐỐI nút/tab LIVE ở góc trên cùng bên trái màn hình chính
     *     (nút này là lối tắt chuyển mục LIVE của TikTok, xuất hiện trên mọi video ở Trang chủ).
     *   - Chỉ nhận diện là LIVE khi có huy hiệu "Đang LIVE" / "LIVE" ở vùng video (thân/dưới màn hình)
     *     hoặc container video LIVE (long_press_layout với contentDescription = "LIVE").
     *   - Kiểm tra các nút CTA quảng cáo (Tải ngay, Cài đặt ngay...) và nhãn Sponsored chính thức.
     *
     * @return "Quảng cáo", "LIVE" hoặc null nếu là video thông thường
     */
    fun detectAdOrLive(
        root: AccessibilityNodeInfo?,
        screenHeight: Float = 0f,
        screenWidth: Float = 0f
    ): String? {
        root ?: return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visitedCount = 0
        val maxTopHeader = if (screenHeight > 0f) screenHeight * 0.22f else 450f
        val maxHeaderLeft = if (screenWidth > 0f) screenWidth * 0.35f else 400f

        while (queue.isNotEmpty() && visitedCount < MAX_TREE_NODES_VISITED) {
            val node = queue.removeFirst()
            visitedCount++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Bỏ qua các node không hiển thị hoặc có kích thước rỗng (ẩn / GONE)
            if (!node.isVisibleToUser || bounds.width() <= 0 || bounds.height() <= 0) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                continue
            }

            val rawText = node.text?.toString()?.trim() ?: ""
            val rawDesc = node.contentDescription?.toString()?.trim() ?: ""
            val lowerText = rawText.lowercase()
            val lowerDesc = rawDesc.lowercase()
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val entryName = viewId.substringAfterLast("/")

            // BỎ QUA NÚT / TAB "LIVE" Ở GÓC TRÊN CÙNG BÊN TRÁI MÀN HÌNH CHÍNH
            // Đây là nút điều hướng sang mục LIVE của TikTok, luôn xuất hiện ở mọi video trên Home feed.
            val isTopLeftHeader = bounds.bottom <= maxTopHeader && bounds.left <= maxHeaderLeft
            val isHeaderLiveTab = isTopLeftHeader && (lowerText == "live" || lowerDesc == "live" || entryName.contains("live"))

            if (!isHeaderLiveTab) {
                // 1. Nhận diện LIVE Stream
                // 1.1: Container phát video LIVE trên Home Feed (long_press_layout với contentDescription = "LIVE")
                if (entryName == "long_press_layout" && (lowerDesc == "live" || lowerText == "live")) {
                    return "LIVE"
                }

                // 1.2: Các cụm từ huy hiệu đặc thù BẮT BUỘC của LIVE (ngắn <= 25 ký tự)
                if (isLiveBadgePhrase(rawText) || isLiveBadgePhrase(rawDesc)) {
                    return "LIVE"
                }

                // 1.3: Tag/Huy hiệu "LIVE", "Đang LIVE", "🔴 LIVE" ở vùng thân / góc dưới video
                // TUYỆT ĐỐI không tính nếu nằm ở dải tab đỉnh màn hình (bounds.bottom <= maxTopHeader)
                if (bounds.top >= maxTopHeader * 0.8f && (isLiveBadge(rawText) || isLiveBadge(rawDesc))) {
                    return "LIVE"
                }
            }

            // 2. Nhận diện Quảng cáo (Sponsored / Ads)
            // 2.1: Nhãn tài trợ chính thức của TikTok ("Được tài trợ", "Sponsored", "Paid partnership")
            // QUY TẮC BẮT BUỘC: Phải là nhãn độc lập ngắn (độ dài <= 30 ký tự).
            if (isSponsoredBadge(rawText) || isSponsoredBadge(rawDesc)) {
                return "Quảng cáo"
            }

            // 2.2: Tag ngắn chính thức "Ad •", "[Ad]" ở vùng thân / dưới video (Y >= 35%)
            if (bounds.top >= maxTopHeader * 1.5f && (isShortAdBadge(rawText) || isShortAdBadge(rawDesc))) {
                return "Quảng cáo"
            }

            // 2.3: Nút CTA cài app / truy cập web của nhà quảng cáo (Cài đặt ngay, Tải ngay, Đăng ký ngay...)
            // Phải là nút bấm (clickable hoặc Button) ở nửa dưới màn hình (Y >= 40%)
            if (bounds.top >= (if (screenHeight > 0f) screenHeight * 0.40f else 750f) &&
                (node.isClickable || node.className?.contains("Button") == true) &&
                isAdCtaButton(rawText, rawDesc)
            ) {
                return "Quảng cáo"
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        return null
    }

    /** Kiểm tra xem chuỗi có phải huy hiệu cụm từ LIVE rõ ràng không */
    private fun isLiveBadgePhrase(str: String): Boolean {
        if (str.isEmpty() || str.length > 25) return false
        val s = str.trim().lowercase()
        return LIVE_EXPLICIT_BADGES.any { s == it || s.startsWith(it) }
    }

    /** Kiểm tra xem chuỗi có phải tag/badge LIVE độc lập không (ví dụ: 'Đang LIVE' ở góc dưới trái) */
    private fun isLiveBadge(str: String): Boolean {
        if (str.isEmpty() || str.length > 25) return false
        val s = str.trim().lowercase()
        return s == "live" || s == "🔴 live" || s == "live 🔴" || s == "[live]" ||
                s == "đang live" || s == "dang live" ||
                s.startsWith("đang live") || s.startsWith("dang live") ||
                s.startsWith("live •") || s.startsWith("live |") || s.startsWith("🔴 live")
    }

    /** Kiểm tra xem chuỗi có phải huy hiệu tài trợ chính thức của TikTok không (ngắn <= 30 ký tự) */
    private fun isSponsoredBadge(str: String): Boolean {
        if (str.isEmpty() || str.length > 30) return false
        val s = str.trim().lowercase()
        return s == "được tài trợ" || s == "sponsored" || s == "paid partnership" ||
                s == "đối tác trả phí" || s == "nội dung được tài trợ" || s == "tài trợ bởi" ||
                s.startsWith("được tài trợ") || s.startsWith("sponsored") ||
                s.startsWith("paid partnership")
    }

    /** Kiểm tra xem chuỗi có phải nút CTA quảng cáo rõ ràng không (độ dài ngắn <= 25 ký tự) */
    private fun isAdCtaButton(rawText: String, rawDesc: String): Boolean {
        val t = rawText.trim().lowercase()
        val d = rawDesc.trim().lowercase()
        return AD_CTA_EXPLICIT_ACTIONS.any {
            (t.isNotEmpty() && (t == it || (t.startsWith(it) && rawText.length <= 25))) ||
            (d.isNotEmpty() && (d == it || (d.startsWith(it) && rawDesc.length <= 25)))
        }
    }

    /** Kiểm tra xem chuỗi có phải ký hiệu Ad ngắn chính thức không */
    private fun isShortAdBadge(str: String): Boolean {
        if (str.isEmpty() || str.length > 15) return false
        val s = str.trim().lowercase()
        return s == "ad •" || s == "• ad" || s == "[ad]" || s == "ad |"
    }

    /** Kiểm tra xem video hiện tại có phải quảng cáo không */
    fun isAdContent(root: AccessibilityNodeInfo?, screenHeight: Float = 0f, screenWidth: Float = 0f): Boolean {
        return detectAdOrLive(root, screenHeight, screenWidth) == "Quảng cáo"
    }

    /** Kiểm tra xem video hiện tại có phải LIVE không */
    fun isLiveContent(root: AccessibilityNodeInfo?, screenHeight: Float = 0f, screenWidth: Float = 0f): Boolean {
        return detectAdOrLive(root, screenHeight, screenWidth) == "LIVE"
    }

    /**
     * Nhận diện màn hình đang phát Video TikTok (Full-screen Video Player).
     * Dấu hiệu tuyệt đối: Có nút Thả tim (Like) VÀ nút Bình luận (Comment) hoặc Chia sẻ (Share).
     */
    fun isVideoPlayerScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val hasLike = findLikeButton(root) != null
        val hasComment = findCommentButton(root) != null
        val hasShare = findShareButton(root) != null
        return hasLike && (hasComment || hasShare)
    }

    /**
     * Nhận diện màn hình Trang cá nhân (Creator Profile) hoặc Trang trưng bày (Shop Showcase).
     * Giúp hệ thống tự động thoát ra Trang chủ thay vì lầm tưởng là video thường và vuốt lặp vô hạn.
     */
    fun isProfileOrShowcaseScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // NẾU ĐANG PHÁT VIDEO PLAYER -> TUYỆT ĐỐI KHÔNG PHẢI TRANG CÁ NHÂN!
        if (isVideoPlayerScreen(root)) return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            // Dấu hiệu: Trang trưng bày / Chỉnh sửa hồ sơ / Thêm tiểu sử / Trang hồ sơ người khác
            if (text.contains("trang trưng bày") || desc.contains("trang trưng bày") ||
                text.contains("chỉnh sửa hồ sơ") || desc.contains("chỉnh sửa hồ sơ") ||
                text.contains("thêm tiểu sử") || desc.contains("thêm tiểu sử") ||
                text.contains("lời nhắc sinh nhật") || desc.contains("lời nhắc sinh nhật") ||
                (text == "tin nhắn" && (desc.contains("follow") || desc.contains("theo dõi")))
            ) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════════
    //  7. Điền văn bản vào EditText (Action Set Text)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Điền văn bản vào EditText node bằng ACTION_SET_TEXT và fallback bằng Clipboard + ACTION_PASTE.
     * Đảm bảo hoạt động tin cậy 100% trên cả Android gốc và các bản tùy biến như MIUI / TikTok custom input.
     *
     * @return true nếu set text thành công
     */
    fun setTextToNode(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        // Chuẩn bị sẵn dữ liệu trong Clipboard để dự phòng
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("autocare_comment", text)
            clipboard?.setPrimaryClip(clip)
        } catch (_: Exception) {}

        // 1. Thử ACTION_SET_TEXT chuẩn của Android Accessibility
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // 2. CHỈ KHI ACTION_SET_TEXT thất bại (trả về false) mới fallback dán qua ACTION_PASTE
        // Tránh bị gõ chữ 2 lần liên tiếp vào cùng một ô nhập
        if (!success) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        return true
    }

    /** Overload giữ tương thích ngược */
    fun setTextToNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ══════════════════════════════════════════════════════════════════
    //  8. Core search helpers (đa năng, dùng nội bộ)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Tìm node đầu tiên có contentDescription hoặc text chứa 1 trong các từ khóa.
     * Tìm kiếm theo DFS (Depth-First Search) trên cây AccessibilityNodeInfo.
     *
     * KHÔNG gọi node.recycle() — deprecated trên SDK mới.
     */
    fun findByDescriptionOrText(
        root: AccessibilityNodeInfo?,
        keywords: Set<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        val lowerKeywords = keywords.map { it.lowercase() }.toSet()

        // DFS: stack-based để tránh StackOverflow với cây sâu, giới hạn số node tránh hao RAM
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visitedCount = 0

        while (stack.isNotEmpty() && visitedCount < MAX_TREE_NODES_VISITED) {
            val node = stack.removeLast()
            visitedCount++

            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            if (lowerKeywords.any { kw -> desc.contains(kw) || text.contains(kw) }) {
                return node
            }

            // Duyệt children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child)
            }
        }

        return null
    }

    /**
     * Tìm node theo viewIdResourceName (kém ổn định — dùng làm fallback cuối).
     */
    fun findById(
        root: AccessibilityNodeInfo?,
        viewId: String
    ): AccessibilityNodeInfo? {
        root ?: return null
        val results = root.findAccessibilityNodeInfosByViewId(viewId)
        return results?.firstOrNull()
    }

    /**
     * Tìm node theo className.
     */
    fun findByClassName(
        root: AccessibilityNodeInfo?,
        className: String
    ): List<AccessibilityNodeInfo> {
        root ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.className?.toString() == className) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child)
            }
        }

        return result
    }

    /**
     * Tìm EditText đầu tiên có thể chỉnh sửa trong cây UI.
     */
    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visitedCount = 0

        while (stack.isNotEmpty() && visitedCount < MAX_TREE_NODES_VISITED) {
            val node = stack.removeLast()
            visitedCount++

            if (node.isEditable && node.isEnabled) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child)
            }
        }
        return null
    }

    /**
     * Kiểm tra xem cây UI có chứa text khớp với bất kỳ keyword nào không.
     * Dùng để phát hiện Ad/LIVE content.
     */
    private fun containsAnyText(
        root: AccessibilityNodeInfo,
        keywords: Set<String>
    ): Boolean {
        val lowerKeywords = keywords.map { it.lowercase() }.toSet()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visitedCount = 0

        while (stack.isNotEmpty() && visitedCount < MAX_TREE_NODES_VISITED) {
            val node = stack.removeLast()
            visitedCount++

            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            if (lowerKeywords.any { kw -> desc.contains(kw) || text.contains(kw) }) {
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child)
            }
        }

        return false
    }

    // ══════════════════════════════════════════════════════════════════
    //  9. Tiện ích lấy root window TikTok
    // ══════════════════════════════════════════════════════════════════

    /**
     * Lấy root node của cửa sổ ứng dụng hiện tại.
     * Ưu tiên cửa sổ TikTok; fallback sang rootInActiveWindow.
     *
     * Cần flag [flagRetrieveInteractiveWindows] trong accessibility_config.xml
     * để có thể đọc node từ nhiều cửa sổ (comment panel overlay).
     */
    fun getTikTokRoot(
        service: android.accessibilityservice.AccessibilityService,
        tiktokPackages: Set<String> = setOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically"
        )
    ): AccessibilityNodeInfo? {
        // Thử tìm trong tất cả cửa sổ đang hiển thị (cần flagRetrieveInteractiveWindows)
        try {
            val windows: List<AccessibilityWindowInfo> = service.windows ?: emptyList()
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() in tiktokPackages) {
                    return root
                }
            }
        } catch (_: Exception) {
            // Không có quyền truy cập windows (SDK < 21 hoặc flag chưa set)
        }

        // Fallback: rootInActiveWindow
        return service.rootInActiveWindow
    }
}
