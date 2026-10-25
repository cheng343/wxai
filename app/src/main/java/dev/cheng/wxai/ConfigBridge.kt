package dev.cheng.wxai

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信进程侧读取与回写配置：
 * 1. 启动期只加载微信自身 SharedPreferences("wxai_mirror_config") 与注册 ACTION_CONFIG_UPDATED 广播；
 * 2. 仅当首次收到新消息且本地无镜像时，才在后台工作线程按需拉取一次 ContentProvider；
 * 3. 支持在微信内执行 #认主 等指令时直接修改配置并回写至模块 App。
 */
object ConfigBridge {
    private const val AUTHORITY = "dev.cheng.wxai.config"
    private const val MIRROR_PREFS = ".mm_sync_prefs"
    private const val KEY_JSON = "config_json"

    @Volatile
    private var cached: Config? = null

    private val initialized = AtomicBoolean(false)
    private val providerTriedOnce = AtomicBoolean(false)

    fun init(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (initialized.compareAndSet(false, true)) {
            loadMirror(app)?.let {
                cached = it
                L.verbose = it.debug
                L.i("config loaded from mirror: enabled=${it.enabled} whitelist=${it.whitelist.size}")
            }
            registerUpdateReceiver(app)
        }
    }

    fun hasLoadedConfig(): Boolean = cached != null

    /**
     * 纯内存读取：永不阻塞，永不触发跨进程调用。
     */
    fun get(ctx: Context): Config {
        cached?.let { return it }
        val app = ctx.applicationContext ?: ctx
        val m = loadMirror(app)
        if (m != null) {
            cached = m
            return m
        }
        return Config.DEFAULT
    }

    /**
     * 在微信进程内更新配置（如执行 #认主 指令时自动绑定主人 wxid）并同步回模块 App。
     */
    fun updateFromWechat(ctx: Context, transform: (Config) -> Config): Config {
        val app = ctx.applicationContext ?: ctx
        val current = ensureLoadedOnWorker(app)
        val updated = transform(current)
        val raw = updated.toJson().toString()
        cached = updated
        saveMirror(app, raw)
        try {
            val cv = ContentValues().apply {
                put(ConfigProvider.COLUMN_JSON, raw)
            }
            app.contentResolver.update(Uri.parse("content://$AUTHORITY/config"), cv, null, null)
        } catch (t: Throwable) {
            L.w("push config to provider skipped: ${t.message}")
        }
        return updated
    }

    /**
     * 仅在 ReplyEngine 后台工作线程上调用：若本地已有镜像直接返回；
     * 若从未同步过，则尝试向模块 ContentProvider 查询一次并落盘到微信本地镜像。
     */
    fun ensureLoadedOnWorker(ctx: Context): Config {
        cached?.let { return it }
        val app = ctx.applicationContext ?: ctx
        loadMirror(app)?.let {
            cached = it
            return it
        }
        if (providerTriedOnce.compareAndSet(false, true)) {
            val raw = queryProviderRaw(app)
            if (!raw.isNullOrBlank()) {
                val fresh = Config.fromJson(raw)
                cached = fresh
                L.verbose = fresh.debug
                saveMirror(app, raw)
                return fresh
            }
        }
        val def = Config.DEFAULT
        cached = def
        return def
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
            L.w("config provider query skipped: ${t.message}")
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
                        L.verbose = fresh.debug
                        saveMirror(app, raw)
                        L.i("config updated via broadcast: enabled=${fresh.enabled} whitelist=${fresh.whitelist.size}")
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
