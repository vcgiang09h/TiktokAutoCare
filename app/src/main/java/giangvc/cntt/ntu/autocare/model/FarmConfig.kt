package giangvc.cntt.ntu.autocare.model

/**
 * Data class lưu toàn bộ thông số cấu hình của người dùng.
 *
 * Được lưu/đọc qua SharedPreferences trong MainActivity.
 * Được TikTokAutoService đọc khi bắt đầu chạy kịch bản.
 */
data class FarmConfig(
    // ── Thẻ 2: Cấu hình Chủ đề & Thời gian ──
    /** Bật/tắt tìm kiếm theo chủ đề (false = lướt xem tự nhiên trang Dành cho bạn / FYP) */
    val enableSearchByKeyword: Boolean = true,

    /** Danh sách từ khóa tìm kiếm, mỗi phần tử là 1 từ khóa */
    val keywords: List<String> = listOf("review công nghệ", "mẹo vặt cuộc sống"),

    /** Thời gian xem video tối thiểu (giây) */
    val watchTimeMinSec: Int = 10,

    /** Thời gian xem video tối đa (giây) */
    val watchTimeMaxSec: Int = 25,

    /** Số video tối thiểu xem mỗi từ khóa trước khi đổi */
    val videosPerKeywordMin: Int = 4,

    /** Số video tối đa xem mỗi từ khóa trước khi đổi */
    val videosPerKeywordMax: Int = 5,

    /** Bật/tắt hẹn giờ tự động dừng tool */
    val enableTimeLimit: Boolean = true,

    /** Tổng thời gian chạy tối đa trước khi tự động dừng (phút) */
    val timeLimitMinutes: Int = 30,

    // ── Thẻ 3: Cấu hình Tương tác & Bình luận ──
    /** Bật/tắt thả tim */
    val enableLike: Boolean = true,

    /** Tỷ lệ thả tim (0–100%) */
    val likeRate: Int = 20,

    /** Bật/tắt lưu video / bookmark */
    val enableSave: Boolean = true,

    /** Tỷ lệ lưu video / bookmark (0–100%) */
    val saveRate: Int = 5,

    /** Bật/tắt đọc & viết bình luận */
    val enableComment: Boolean = true,

    /** Tỷ lệ mở khung đọc bình luận (0–100%) */
    val readCommentRate: Int = 30,

    /** Tỷ lệ viết bình luận — chỉ kích hoạt khi khung cmt đang mở (0–100%) */
    val writeCommentRate: Int = 10,

    /** Danh sách bình luận mẫu (mỗi dòng 1 bình luận) */
    val commentDictionary: String = "Video hay quá bạn ơi ❤️\nNội dung hữu ích quá!\nĐỉnh thật sự luôn 👍\nCảm ơn bạn đã chia sẻ ạ!"
) {
    companion object {
        // ── SharedPreferences keys ──
        const val PREFS_NAME = "autocare_config"
        const val CURRENT_CONFIG_VERSION = 3
        const val KEY_CONFIG_VERSION = "config_version"
        const val KEY_ENABLE_SEARCH = "enable_search"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_WATCH_MIN = "watch_time_min"
        const val KEY_WATCH_MAX = "watch_time_max"
        const val KEY_VIDEOS_MIN = "videos_per_kw_min"
        const val KEY_VIDEOS_MAX = "videos_per_kw_max"
        const val KEY_ENABLE_TIME_LIMIT = "enable_time_limit"
        const val KEY_TIME_LIMIT_MINUTES = "time_limit_minutes"
        const val KEY_ENABLE_LIKE = "enable_like"
        const val KEY_LIKE_RATE = "like_rate"
        const val KEY_ENABLE_SAVE = "enable_save"
        const val KEY_SAVE_RATE = "save_rate"
        const val KEY_ENABLE_COMMENT = "enable_comment"
        const val KEY_READ_CMT_RATE = "read_comment_rate"
        const val KEY_WRITE_CMT_RATE = "write_comment_rate"
        const val KEY_COMMENT_DICT = "comment_dictionary"

        /** Separator dùng khi lưu danh sách từ khóa vào SharedPreferences */
        const val KEYWORD_SEPARATOR = "\n"

        /** Đọc cấu hình từ SharedPreferences */
        fun loadFromPrefs(context: android.content.Context): FarmConfig {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val defaultVal = FarmConfig()
                val currentVer = prefs.getInt(KEY_CONFIG_VERSION, 0)

                // Nếu là phiên bản cũ, cập nhật sang các thông số mặc định mới và lưu lại
                if (currentVer < CURRENT_CONFIG_VERSION) {
                    val kwString = prefs.getString(KEY_KEYWORDS, null)
                    val existingKeywords = if (!kwString.isNullOrBlank()) {
                        kwString.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        defaultVal.keywords
                    }

                    val savedDict = prefs.getString(KEY_COMMENT_DICT, null)
                    val existingDict = if (!savedDict.isNullOrBlank() && !savedDict.contains("{Hay quá|Tuyệt vời")) {
                        savedDict
                    } else {
                        defaultVal.commentDictionary
                    }

                    val updatedConfig = defaultVal.copy(
                        keywords = existingKeywords,
                        commentDictionary = existingDict
                    )
                    saveToPrefs(context, updatedConfig)
                    return updatedConfig
                }

                val kwString = prefs.getString(KEY_KEYWORDS, null)
                val keywords = if (!kwString.isNullOrBlank()) {
                    kwString.lines().map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    defaultVal.keywords
                }

                val savedDict = prefs.getString(KEY_COMMENT_DICT, null)
                val commentDict = if (savedDict.isNullOrBlank() || savedDict.contains("{Hay quá|Tuyệt vời")) {
                    defaultVal.commentDictionary
                } else {
                    savedDict
                }

                FarmConfig(
                    enableSearchByKeyword = prefs.getBoolean(KEY_ENABLE_SEARCH, defaultVal.enableSearchByKeyword),
                    keywords = keywords,
                    watchTimeMinSec = prefs.getInt(KEY_WATCH_MIN, defaultVal.watchTimeMinSec),
                    watchTimeMaxSec = prefs.getInt(KEY_WATCH_MAX, defaultVal.watchTimeMaxSec),
                    videosPerKeywordMin = prefs.getInt(KEY_VIDEOS_MIN, defaultVal.videosPerKeywordMin),
                    videosPerKeywordMax = prefs.getInt(KEY_VIDEOS_MAX, defaultVal.videosPerKeywordMax),
                    enableTimeLimit = prefs.getBoolean(KEY_ENABLE_TIME_LIMIT, defaultVal.enableTimeLimit),
                    timeLimitMinutes = prefs.getInt(KEY_TIME_LIMIT_MINUTES, defaultVal.timeLimitMinutes),
                    enableLike = prefs.getBoolean(KEY_ENABLE_LIKE, defaultVal.enableLike),
                    likeRate = prefs.getInt(KEY_LIKE_RATE, defaultVal.likeRate),
                    enableSave = prefs.getBoolean(KEY_ENABLE_SAVE, defaultVal.enableSave),
                    saveRate = prefs.getInt(KEY_SAVE_RATE, defaultVal.saveRate),
                    enableComment = prefs.getBoolean(KEY_ENABLE_COMMENT, defaultVal.enableComment),
                    readCommentRate = prefs.getInt(KEY_READ_CMT_RATE, defaultVal.readCommentRate),
                    writeCommentRate = prefs.getInt(KEY_WRITE_CMT_RATE, defaultVal.writeCommentRate),
                    commentDictionary = commentDict
                )
            } catch (e: Exception) {
                e.printStackTrace()
                FarmConfig()
            }
        }

        /** Lưu cấu hình vào SharedPreferences */
        fun saveToPrefs(context: android.content.Context, config: FarmConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)
                putBoolean(KEY_ENABLE_SEARCH, config.enableSearchByKeyword)
                putString(KEY_KEYWORDS, config.keywordsAsText())
                putInt(KEY_WATCH_MIN, config.watchTimeMinSec)
                putInt(KEY_WATCH_MAX, config.watchTimeMaxSec)
                putInt(KEY_VIDEOS_MIN, config.videosPerKeywordMin)
                putInt(KEY_VIDEOS_MAX, config.videosPerKeywordMax)
                putBoolean(KEY_ENABLE_TIME_LIMIT, config.enableTimeLimit)
                putInt(KEY_TIME_LIMIT_MINUTES, config.timeLimitMinutes)
                putBoolean(KEY_ENABLE_LIKE, config.enableLike)
                putInt(KEY_LIKE_RATE, config.likeRate)
                putBoolean(KEY_ENABLE_SAVE, config.enableSave)
                putInt(KEY_SAVE_RATE, config.saveRate)
                putBoolean(KEY_ENABLE_COMMENT, config.enableComment)
                putInt(KEY_READ_CMT_RATE, config.readCommentRate)
                putInt(KEY_WRITE_CMT_RATE, config.writeCommentRate)
                putString(KEY_COMMENT_DICT, config.commentDictionary)
                apply()
            }
        }
    }

    // ── Validation ──

    /** Kiểm tra cấu hình hợp lệ, trả về danh sách lỗi (rỗng = OK) */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (enableSearchByKeyword) {
            if (keywords.isEmpty() || keywords.all { it.isBlank() }) {
                errors.add("Đã bật tìm kiếm theo chủ đề nhưng chưa nhập từ khóa nào")
            }
            if (videosPerKeywordMin < 1) {
                errors.add("Số video mỗi từ khóa tối thiểu phải ≥ 1")
            }
            if (videosPerKeywordMax < videosPerKeywordMin) {
                errors.add("Số video tối đa phải ≥ tối thiểu")
            }
        }

        if (watchTimeMinSec < 3) {
            errors.add("Thời gian xem tối thiểu phải ≥ 3 giây")
        }
        if (watchTimeMaxSec < watchTimeMinSec) {
            errors.add("Thời gian xem tối đa phải ≥ tối thiểu")
        }

        if (enableTimeLimit && timeLimitMinutes < 1) {
            errors.add("Thời gian hẹn giờ tự động dừng phải từ 1 phút trở lên")
        }

        if (likeRate !in 0..100 || saveRate !in 0..100 ||
            readCommentRate !in 0..100 || writeCommentRate !in 0..100
        ) {
            errors.add("Tất cả tỷ lệ tương tác phải nằm trong khoảng 0–100%")
        }

        if (enableComment && writeCommentRate > 0 && commentDictionary.isBlank()) {
            errors.add("Đã bật bình luận với tỷ lệ viết > 0% nhưng chưa có nội dung bình luận nào")
        }

        return errors
    }

    // ── Tiện ích ──

    /** Lấy từ khóa dạng chuỗi nhiều dòng (để hiển thị trong EditText) */
    fun keywordsAsText(): String = keywords.joinToString(KEYWORD_SEPARATOR)

    /** Random thời gian xem (ms) */
    fun randomWatchTimeMs(): Long {
        val min = minOf(watchTimeMinSec, watchTimeMaxSec).coerceAtLeast(1)
        val max = maxOf(watchTimeMinSec, watchTimeMaxSec).coerceAtLeast(min)
        return (min..max).random() * 1000L
    }

    /** Random số video cho 1 từ khóa */
    fun randomVideosPerKeyword(): Int {
        val min = minOf(videosPerKeywordMin, videosPerKeywordMax).coerceAtLeast(1)
        val max = maxOf(videosPerKeywordMin, videosPerKeywordMax).coerceAtLeast(min)
        return (min..max).random()
    }

    /** Kiểm tra xem có nên thả tim cho video này không */
    fun shouldLike(): Boolean = enableLike && (1..100).random() <= likeRate

    /** Kiểm tra xem có nên lưu video không */
    fun shouldSave(): Boolean = enableSave && (1..100).random() <= saveRate

    /** Kiểm tra xem có nên mở đọc bình luận không */
    fun shouldReadComments(): Boolean = enableComment && (1..100).random() <= readCommentRate

    /** Kiểm tra xem có nên viết bình luận không */
    fun shouldWriteComment(): Boolean = enableComment && (1..100).random() <= writeCommentRate
}
