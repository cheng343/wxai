package dev.cheng.wxai

import android.content.Context
import android.net.Uri
import android.os.SystemClock

/**
 * 微信进程侧读取配置：查模块 App 的 ContentProvider，带 TTL 缓存。
 * 模块未安装/Provider 未响应时回落到 DEFAULT（enabled=false，也就是不干活）。
 */
object ConfigBridge {
    private const val AUTHORITY = "dev.cheng.wxai.config"
    private const val TTL_MS = 15_000L

    @Volatile
    private var cached: Config? = null

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var lastError: String? = null

    fun get(ctx: Context, force: Boolean = false): Config {
        val now = SystemClock.elapsedRealtime()
        val c = cached
        if (!force && c != null && now - cachedAt < TTL_MS) return c

        val fresh = try {
            ctx.contentResolver.query(Uri.parse("content://$AUTHORITY/config"), null, null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) {
                        val idx = cur.getColumnIndex("json")
                        if (idx >= 0) Config.fromJson(cur.getString(idx)) else null
                    } else null
                }
        } catch (t: Throwable) {
            val msg = t.toString()
            if (msg != lastError) {
                lastError = msg
                L.e("config provider query failed (module app installed?)", t)
            }
            null
        }

        val result = fresh ?: c ?: Config.DEFAULT
        cached = result
        cachedAt = now
        return result
    }

    fun invalidate() {
        cached = null
        cachedAt = 0L
    }
}
