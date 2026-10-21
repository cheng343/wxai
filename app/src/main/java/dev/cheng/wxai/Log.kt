package dev.cheng.wxai

import android.util.Log as AndroidLog

/**
 * 统一日志出口。
 *
 * 这个类会被两个进程加载：微信进程（有 XposedBridge）和模块 App 自己的进程（没有）。
 * 所以这里不能在对象初始化阶段碰 XposedBridge，改为首次写入时探测 + try/catch 兜底。
 */
object L {
    private const val TAG = "WXAI"
    private const val PREFIX = "[WXAI] "

    @Volatile
    var verbose: Boolean = true

    @Volatile
    private var xposed: Boolean? = null

    private fun hasXposed(): Boolean {
        xposed?.let { return it }
        val ok = try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, L::class.java.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
        xposed = ok
        return ok
    }

    private fun log(msg: String) {
        if (hasXposed()) {
            try {
                de.robv.android.xposed.XposedBridge.log(msg)
                return
            } catch (_: Throwable) {
                // 落到 android.util.Log
            }
        }
        AndroidLog.i(TAG, msg)
    }

    private fun logThrowable(t: Throwable) {
        if (hasXposed()) {
            try {
                de.robv.android.xposed.XposedBridge.log(t)
                return
            } catch (_: Throwable) {
            }
        }
        AndroidLog.e(TAG, "throwable", t)
    }

    fun i(msg: String) {
        if (!verbose) return
        log(PREFIX + msg)
    }

    fun w(msg: String) {
        log(PREFIX + "WARN " + msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        log(PREFIX + "ERROR " + msg)
        if (t != null) logThrowable(t)
    }
}
