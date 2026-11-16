package giangvc.cntt.ntu.autocare

import giangvc.cntt.ntu.autocare.engine.SpintaxParser
import giangvc.cntt.ntu.autocare.model.FarmConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test cho Automation Engine logic (SpintaxParser & FarmConfig).
 */
class AutomationEngineUnitTest {

    @Test
    fun testSpintaxBasic() {
        val template = "{Hay quá|Tuyệt vời}"
        val possibleResults = setOf("Hay quá", "Tuyệt vời")

        for (i in 0 until 20) {
            val result = SpintaxParser.parse(template)
            assertTrue("Kết quả '$result' phải nằm trong tập $possibleResults", result in possibleResults)
        }
    }

    @Test
    fun testSpintaxNested() {
        val template = "{Hay {quá|thật}|Tuyệt vời} luôn"
        val possibleResults = setOf("Hay quá luôn", "Hay thật luôn", "Tuyệt vời luôn")

        for (i in 0 until 30) {
            val result = SpintaxParser.parse(template)
            assertTrue("Kết quả '$result' phải nằm trong tập $possibleResults", result in possibleResults)
        }
    }

    @Test
    fun testGenerateCommentFromDictionary() {
        val dictionary = """
            {Video|Clip} {rất hay|ý nghĩa quá}!
            {Cảm ơn|Thank} {bạn|ad} nhiều!
        """.trimIndent()

        for (i in 0 until 20) {
            val comment = SpintaxParser.generateComment(dictionary)
            assertNotNull("Comment không được null", comment)
            assertFalse("Comment không được chứa dấu ngoặc nhọn", comment!!.contains("{") || comment.contains("}"))
            assertFalse("Comment không được chứa dấu gạch đứng", comment.contains("|"))
        }
    }

    @Test
    fun testFarmConfigValidation() {
        // Cấu hình hợp lệ mặc định
        val validConfig = FarmConfig()
        assertTrue("Cấu hình mặc định phải hợp lệ", validConfig.validate().isEmpty())

        // Khi bật tìm kiếm theo chủ đề: thiếu từ khóa phải báo lỗi
        val emptyKwConfig = validConfig.copy(enableSearchByKeyword = true, keywords = emptyList())
        assertFalse("Bật chủ đề nhưng không có từ khóa phải báo lỗi", emptyKwConfig.validate().isEmpty())

        // Khi TẮT tìm kiếm theo chủ đề (lướt tự nhiên FYP): không cần từ khóa vẫn hợp lệ!
        val fypConfig = validConfig.copy(enableSearchByKeyword = false, keywords = emptyList())
        assertTrue("Tắt chủ đề thì không cần từ khóa vẫn hợp lệ", fypConfig.validate().isEmpty())

        // Cấu hình thời gian xem sai (min > max)
        val invalidTimeConfig = validConfig.copy(watchTimeMinSec = 30, watchTimeMaxSec = 10)
        assertFalse("Thời gian min > max phải báo lỗi", invalidTimeConfig.validate().isEmpty())

        // Cấu hình tỷ lệ tương tác sai (> 100%)
        val invalidRateConfig = validConfig.copy(likeRate = 150)
        assertFalse("Tỷ lệ like > 100 phải báo lỗi", invalidRateConfig.validate().isEmpty())

        // Khi TẮT bình luận: từ điển rỗng vẫn hợp lệ
        val noCommentConfig = validConfig.copy(enableComment = false, commentDictionary = "")
        assertTrue("Tắt bình luận thì từ điển rỗng vẫn hợp lệ", noCommentConfig.validate().isEmpty())

        // Cấu hình hẹn giờ dừng sai (< 1 phút)
        val invalidTimerConfig = validConfig.copy(enableTimeLimit = true, timeLimitMinutes = 0)
        assertFalse("Hẹn giờ < 1 phút khi bật phải báo lỗi", invalidTimerConfig.validate().isEmpty())

        // Cấu hình hẹn giờ dừng đúng (30 phút)
        val validTimerConfig = validConfig.copy(enableTimeLimit = true, timeLimitMinutes = 30)
        assertTrue("Hẹn giờ 30 phút khi bật phải hợp lệ", validTimerConfig.validate().isEmpty())
    }

    @Test
    fun testFarmConfigHelpers() {
        val config = FarmConfig(
            watchTimeMinSec = 5,
            watchTimeMaxSec = 10,
            enableLike = true,
            likeRate = 100, // luôn like
            enableSave = false, // tắt lưu video
            saveRate = 100
        )

        val watchTime = config.randomWatchTimeMs()
        assertTrue("Thời gian xem phải trong khoảng 5000-10000ms", watchTime in 5000L..10000L)

        assertTrue("Tỷ lệ 100% like và enableLike = true phải luôn true", config.shouldLike())
        assertFalse("enableSave = false thì shouldSave phải luôn false dù rate = 100", config.shouldSave())
    }

    @Test
    fun testTimerFormatting() {
        val timeLimitMinutes = 30
        val elapsedSeconds = 585L // 9 phút 45 giây

        val totalSec = timeLimitMinutes * 60L
        val remainingSec = (totalSec - elapsedSeconds).coerceAtLeast(0)
        val remMm = remainingSec / 60
        val remSs = remainingSec % 60
        val formattedRemaining = String.format(java.util.Locale.getDefault(), "%02d:%02d", remMm, remSs)

        assertEquals("20:15", formattedRemaining)
    }
}
