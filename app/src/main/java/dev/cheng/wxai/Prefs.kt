package dev.cheng.wxai

import android.content.Context
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
        prefs(ctx).edit().putString(KEY_CONFIG_JSON, json).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext ?: ctx
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
