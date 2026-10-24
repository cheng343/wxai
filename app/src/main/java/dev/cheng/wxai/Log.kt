package dev.cheng.wxai

import android.util.Log as AndroidLog

/**
 * 统一日志出口。
 *
 * 优先走 LibXposed 102 的 XposedModule.log（不触碰 Legacy XposedBridge）；
 * 若由旧版 Xposed 加载则回退到 XposedBridge.log，模块自身进程则写 Android Logcat。
 */
object L {
    private const val TAG = "WXAI"
    private const val PREFIX = "[WXAI] "

    @Volatile
    var verbose: Boolean = true

    @Volatile
    private var legacyXposed: Boolean? = null

    private fun hasLegacyXposed(): Boolean {
        if (LxpEntry.module != null) return false
        legacyXposed?.let { return it }
        val ok = try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, L::class.java.classLoader)
            true
        } catch (_: Throwable) {
            false
        }
        legacyXposed = ok
        return ok
    }

    private fun log(msg: String) {
        val lxp = try {
            LxpEntry.module
        } catch (_: Throwable) {
            null
        }
        if (lxp != null) {
            try {
                lxp.log(AndroidLog.INFO, TAG, msg, null)
                return
            } catch (_: Throwable) {
            }
        }
        if (hasLegacyXposed()) {
            try {
                de.robv.android.xposed.XposedBridge.log(msg)
                return
            } catch (_: Throwable) {
            }
        }
        AndroidLog.i(TAG, msg)
    }

    private fun logThrowable(t: Throwable) {
        val lxp = try {
            LxpEntry.module
        } catch (_: Throwable) {
            null
        }
        if (lxp != null) {
            try {
                lxp.log(AndroidLog.ERROR, TAG, t.message ?: t.javaClass.simpleName, t)
                return
            } catch (_: Throwable) {
            }
        }
        if (hasLegacyXposed()) {
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
