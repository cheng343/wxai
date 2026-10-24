package dev.cheng.wxai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信进程侧读取配置：
 * 1. 本地镜像缓存（微信自身 SharedPreferences("wxai_mirror_config")）+ 内存缓存，确保 get() 永不阻塞调用线程；
 * 2. 监听模块 App 保存配置时发出的广播 ACTION_CONFIG_UPDATED，秒级同步；
 * 3. 后台单线程异步通过 ContentProvider 拉取最新配置，彻底杜绝在微信数据库锁或主线程上跨进程卡死。
 */
object ConfigBridge {
    private const val AUTHORITY = "dev.cheng.wxai.config"
    private const val MIRROR_PREFS = "wxai_mirror_config"
    private const val KEY_JSON = "config_json"
    private const val TTL_MS = 15_000L

    @Volatile
    private var cached: Config? = null

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var lastError: String? = null

    private val initialized = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)

    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wxai-cfg").apply { isDaemon = true }
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (initialized.compareAndSet(false, true)) {
            loadMirror(app)?.let {
                cached = it
                L.verbose = it.debug
            }
            registerUpdateReceiver(app)
        }
        refreshAsync(app)
    }

    /**
     * 非阻塞获取配置：永远立即返回内存缓存或本地镜像，过期时仅触发后台异步刷新。
     */
    fun get(ctx: Context, force: Boolean = false): Config {
        val app = ctx.applicationContext ?: ctx
        var c = cached
        if (c == null) {
            c = loadMirror(app) ?: Config.DEFAULT
            cached = c
        }
        val now = SystemClock.elapsedRealtime()
        if (force || now - cachedAt >= TTL_MS) {
            refreshAsync(app)
        }
        return c
    }

    fun invalidate() {
        cachedAt = 0L
    }

    private fun refreshAsync(app: Context) {
        if (!refreshing.compareAndSet(false, true)) return
        bgExecutor.execute {
            try {
                val raw = queryProviderRaw(app)
                if (!raw.isNullOrBlank()) {
                    val fresh = Config.fromJson(raw)
                    cached = fresh
                    cachedAt = SystemClock.elapsedRealtime()
                    L.verbose = fresh.debug
                    saveMirror(app, raw)
                } else {
                    cachedAt = SystemClock.elapsedRealtime()
                }
            } catch (t: Throwable) {
                L.e("refreshAsync unexpected error", t)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun queryProviderRaw(ctx: Context): String? {
        return try {
            ctx.contentResolver.query(Uri.parse("content://$AUTHORITY/config"), null, null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) {
                        val idx = cur.getColumnIndex("json")
                        if (idx >= 0) cur.getString(idx) else null
                    } else null
                }
        } catch (t: Throwable) {
            val msg = t.toString()
            if (msg != lastError) {
                lastError = msg
                L.e("config provider query failed (module app installed or background restricted?)", t)
            }
            null
        }
    }

    private fun loadMirror(app: Context): Config? = try {
        val raw = app.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (!raw.isNullOrBlank()) Config.fromJson(raw) else null
    } catch (_: Throwable) {
        null
    }

    private fun saveMirror(app: Context, rawJson: String) {
        try {
            app.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, rawJson)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun registerUpdateReceiver(app: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val raw = intent?.getStringExtra(ConversationProtocol.EXTRA_CONFIG_JSON)
                    if (!raw.isNullOrBlank()) {
                        val fresh = Config.fromJson(raw)
                        cached = fresh
                        cachedAt = SystemClock.elapsedRealtime()
                        L.verbose = fresh.debug
                        saveMirror(app, raw)
                        L.i("config updated via broadcast: enabled=${fresh.enabled} whitelist=${fresh.whitelist.size}")
                    } else {
                        refreshAsync(app)
                    }
                }
            }
            val filter = IntentFilter(ConversationProtocol.ACTION_CONFIG_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            L.e("register config update receiver failed", t)
        }
    }
}
