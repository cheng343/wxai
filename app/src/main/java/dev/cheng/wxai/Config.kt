package dev.cheng.wxai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置。JSON 是模块 App 与微信进程之间唯一的配置载体：
 * 模块 App 写 SharedPreferences 并序列化成 JSON，微信进程经 ConfigBridge 读 ContentProvider。
 *
 * 改动字段时三处必须同步：这里、SettingsActivity 的表单、ConfigProvider 的输出。
 */
data class Config(
    val enabled: Boolean = false,
    /** OpenAI 兼容 base url，末尾可带 /v1，不要带 /chat/completions */
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String = DEFAULT_PROMPT,
    /** 白名单：wxid 或 xxx@chatroom；空 = 不回复任何人（安全默认） */
    val whitelist: List<String> = emptyList(),
    /** 自己的昵称，群聊判定 @我 时用；留空则群聊仅在内容含 @ 时回复 */
    val myNickname: String = "",
    /** 群聊只在似乎被 @ 时回复 */
    val groupOnlyWhenMentioned: Boolean = true,
    /** 同一会话两次自动回复的最小间隔（秒） */
    val cooldownSeconds: Int = 20,
    /** 每会话每小时上限 */
    val maxPerHour: Int = 30,
    val replyDelayMinMs: Int = 800,
    val replyDelayMaxMs: Int = 2500,
    /** 回复最大字符数，超出截断 */
    val maxReplyChars: Int = 300,
    val temperature: Double = 0.7,
    val debug: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("systemPrompt", systemPrompt)
        put("whitelist", JSONArray(whitelist))
        put("myNickname", myNickname)
        put("groupOnlyWhenMentioned", groupOnlyWhenMentioned)
        put("cooldownSeconds", cooldownSeconds)
        put("maxPerHour", maxPerHour)
        put("replyDelayMinMs", replyDelayMinMs)
        put("replyDelayMaxMs", replyDelayMaxMs)
        put("maxReplyChars", maxReplyChars)
        put("temperature", temperature)
        put("debug", debug)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你正在用微信和对方聊天。对方刚发来一条消息，请用简短、自然、口语化的中文直接回复，" +
                "不要加任何前缀、称呼解释或引号，不要解释你在做什么。回复控制在两句话以内。"

        val DEFAULT = Config()

        private fun JSONObject.str(key: String, def: String): String =
            if (has(key) && !isNull(key)) optString(key, def) else def

        fun fromJson(raw: String?): Config {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(raw)
                val list = ArrayList<String>()
                o.optJSONArray("whitelist")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) list.add(s)
                    }
                }
                Config(
                    enabled = o.optBoolean("enabled", false),
                    baseUrl = o.str("baseUrl", DEFAULT.baseUrl),
                    apiKey = o.str("apiKey", ""),
                    model = o.str("model", DEFAULT.model),
                    systemPrompt = o.str("systemPrompt", DEFAULT_PROMPT),
                    whitelist = list,
                    myNickname = o.str("myNickname", ""),
                    groupOnlyWhenMentioned = o.optBoolean("groupOnlyWhenMentioned", true),
                    cooldownSeconds = o.optInt("cooldownSeconds", DEFAULT.cooldownSeconds),
                    maxPerHour = o.optInt("maxPerHour", DEFAULT.maxPerHour),
                    replyDelayMinMs = o.optInt("replyDelayMinMs", DEFAULT.replyDelayMinMs),
                    replyDelayMaxMs = o.optInt("replyDelayMaxMs", DEFAULT.replyDelayMaxMs),
                    maxReplyChars = o.optInt("maxReplyChars", DEFAULT.maxReplyChars),
                    temperature = o.optDouble("temperature", DEFAULT.temperature),
                    debug = o.optBoolean("debug", true),
                )
            } catch (t: Throwable) {
                L.e("config parse failed, falling back to default", t)
                DEFAULT
            }
        }
    }
}
