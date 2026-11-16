package giangvc.cntt.ntu.autocare

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * Lớp Application tùy biến của AutoCare.
 *
 * Cung cấp:
 *  - Bộ bắt lỗi Uncaught Exception toàn cục (Global Crash Catcher):
 *    Ghi lại toàn bộ stack trace của bất kỳ ngoại lệ nào chưa được xử lý vào SharedPreferences
 *    để MainActivity có thể hiển thị thông báo chi tiết cho người dùng thay vì crash âm thầm.
 */
class AutoCareApp : Application() {

    companion object {
        const val PREF_CRASH = "autocare_crash_logs"
        const val KEY_LAST_CRASH = "key_last_crash_trace"
        const val KEY_LAST_CRASH_TIME = "key_last_crash_time"
    }

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e("AutoCareCrash", "FATAL UNCAUGHT EXCEPTION on thread ${thread.name}: $stackTrace")

            try {
                val prefs = getSharedPreferences(PREF_CRASH, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_LAST_CRASH, "${throwable.javaClass.simpleName}: ${throwable.message}\n\n$stackTrace")
                    .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                    .commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Chuyển tiếp cho Android xử lý mặc định
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
