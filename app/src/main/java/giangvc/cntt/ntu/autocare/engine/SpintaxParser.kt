package giangvc.cntt.ntu.autocare.engine

import kotlin.random.Random

/**
 * Bộ giải mã Spintax — biến mẫu câu bình luận thành chuỗi ngẫu nhiên duy nhất.
 *
 * Hỗ trợ Spintax lồng nhau nhiều cấp.
 *
 * Ví dụ:
 *   Input:  "{Video|Clip} {rất hay|ý nghĩa quá}, {cảm ơn|thank} {bạn|ad}!"
 *   Output: "Clip ý nghĩa quá, cảm ơn ad!"
 *
 *   Input lồng: "{Hay {quá|thật}|Tuyệt vời} luôn"
 *   Output:      "Hay thật luôn"  hoặc  "Tuyệt vời luôn"
 */
object SpintaxParser {

    private val random = Random.Default

    /**
     * Giải mã 1 chuỗi Spintax, trả về chuỗi ngẫu nhiên đã chọn.
     *
     * Thuật toán:
     *  1. Tìm cặp ngoặc nhọn trong cùng nhất (không chứa '{' bên trong).
     *  2. Tách các lựa chọn bằng '|'.
     *  3. Chọn ngẫu nhiên 1 lựa chọn và thay thế toàn bộ cặp ngoặc.
     *  4. Lặp lại cho đến khi không còn ngoặc nhọn nào.
     */
    fun parse(template: String): String {
        var result = template

        // Regex tìm cặp ngoặc nhọn trong cùng: { nội dung không chứa { hoặc } }
        val innerBracePattern = Regex("\\{([^{}]+)\\}")

        // Lặp cho đến khi xử lý hết mọi cặp ngoặc (bao gồm lồng nhau)
        var safetyCounter = 0
        val maxIterations = 100 // chống vòng lặp vô hạn

        while (innerBracePattern.containsMatchIn(result) && safetyCounter < maxIterations) {
            result = innerBracePattern.replace(result) { matchResult ->
                val options = matchResult.groupValues[1].split('|')
                if (options.isNotEmpty()) {
                    options[random.nextInt(options.size)]
                } else {
                    "" // ngoặc rỗng → chuỗi rỗng
                }
            }
            safetyCounter++
        }

        return result.trim()
    }

    /**
     * Chọn ngẫu nhiên 1 dòng từ từ điển bình luận (multi-line) rồi parse Spintax.
     *
     * @param dictionary Chuỗi chứa nhiều mẫu câu, mỗi dòng 1 mẫu.
     * @return Chuỗi bình luận đã giải mã, hoặc null nếu dictionary rỗng.
     */
    fun generateComment(dictionary: String): String? {
        val lines = dictionary
            .split(Regex("[\r\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return null

        val template = lines.random()
        return parse(template)
    }
}

