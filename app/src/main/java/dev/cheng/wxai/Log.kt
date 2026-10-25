package dev.cheng.wxai

import android.util.Log as AndroidLog

/**
 * 统一日志出口（纯 LibXposed 102 架构，深度脱敏防侦察版）。
 * 微信进程内绝不向公开 logcat 抛出 WXAI 标签日志，仅写入 LSPosed 根级私有通道。
 */
object L {
    private const val STEALTH_TAG = "mm-worker"

    @Volatile
    var verbose: Boolean = false

    private fun log(msg: String) {
        val lxp = try {
            LxpEntry.module
        } catch (_: Throwable) {
            null
        }
        if (lxp != null) {
            try {
                lxp.log(AndroidLog.INFO, STEALTH_TAG, msg, null)
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun logThrowable(t: Throwable) {
        val lxp = try {
            LxpEntry.module
        } catch (_: Throwable) {
            null
        }
        if (lxp != null) {
            try {
                lxp.log(AndroidLog.ERROR, STEALTH_TAG, t.message ?: t.javaClass.simpleName, t)
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun i(msg: String) {
        if (!verbose) return
        log(msg)
    }

    fun w(msg: String) {
        log(msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        log(msg)
        if (t != null) logThrowable(t)
    }
}
