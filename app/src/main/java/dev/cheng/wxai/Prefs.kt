package dev.cheng.wxai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * 模块 App 侧的配置落盘：SharedPreferences("wxai_config") 里只存一个键 "config_json"，
 * 值是 Config.toJson().toString()。
 *
 * 微信进程不直接读这个文件，而是通过 ConfigProvider 走 ContentProvider 拿同一份 JSON，
 * 这样两边永远只有一种序列化格式（见 Config.kt 顶部的说明）。
 */
object Prefs {

    const val PREFS_NAME = "wxai_config"
    const val KEY_CONFIG_JSON = "config_json"

    /** 微信进程推上来的会话列表快照（ConvSnapshot.toJson()） */
    const val KEY_CONVERSATIONS_JSON = "conversations_json"

    /**
     * 读配置。任何异常（含 SP 损坏、Config.fromJson 内部 L 在模块 App 进程里抛
     * NoClassDefFoundError）都回落到 Config.DEFAULT（enabled=false，安全默认）。
     */
    fun load(ctx: Context): Config {
        return try {
            val raw = prefs(ctx).getString(KEY_CONFIG_JSON, null)
            Config.fromJson(raw)
        } catch (t: Throwable) {
            Config.DEFAULT
        }
    }

    /** 写配置。写失败时向上抛，由调用方（SettingsActivity）提示用户。 */
    fun save(ctx: Context, cfg: Config) {
        val json = cfg.toJson().toString()
        prefs(ctx).edit().putString(KEY_CONFIG_JSON, json).commit()
        broadcastJson(ctx, json)
    }

    /** 将当前保存的配置通过广播同步给正在运行的微信进程。 */
    fun broadcastCurrent(ctx: Context) {
        val raw = prefs(ctx).getString(KEY_CONFIG_JSON, null)
        if (!raw.isNullOrBlank()) {
            broadcastJson(ctx, raw)
        }
    }

    private fun broadcastJson(ctx: Context, json: String) {
        try {
            val intent = Intent(ConversationProtocol.ACTION_CONFIG_UPDATED)
                .setPackage(ConversationProtocol.WECHAT_PACKAGE)
                .putExtra(ConversationProtocol.EXTRA_CONFIG_JSON, json)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }

    /** 读微信进程推上来的会话列表快照；从未推送过返回 EMPTY。 */
    fun loadConversations(ctx: Context): ConvSnapshot {
        return try {
            ConvSnapshot.fromJson(prefs(ctx).getString(KEY_CONVERSATIONS_JSON, null))
        } catch (t: Throwable) {
            ConvSnapshot.EMPTY
        }
    }

    /** 写会话列表快照（由 ConfigProvider 在微信进程调用 update() 时落盘）。 */
    fun saveConversations(ctx: Context, rawJson: String) {
        prefs(ctx).edit().putString(KEY_CONVERSATIONS_JSON, rawJson).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext ?: ctx
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
