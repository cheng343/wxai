package dev.cheng.wxai

import android.util.Log as AndroidLog

/**
 * 统一日志出口（纯 LibXposed 102 架构）。
 */
object L {
    private const val TAG = "WXAI"
    private const val PREFIX = "[WXAI] "

    @Volatile
    var verbose: Boolean = true

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
