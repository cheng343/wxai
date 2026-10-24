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
    /** 是否启用本地 SQLite 多轮会话记忆与长期事实记忆 */
    val enableMemory: Boolean = true,
    /** 单会话最大保留上下文轮数（1 轮 = 1 问 + 1 答） */
    val maxHistoryTurns: Int = 12,
    /** 会话超时时间（分钟），超过该时长未聊天则开启新上下文滑窗；0 表示永不过期 */
    val sessionExpireMinutes: Int = 60,
    /** 是否启用 Agent 跨对话操控能力（查联系人、读任意会话历史、跨会话代发消息、存取永久记忆） */
    val enableAgentTools: Boolean = true,
    /** 主人标识（可填 wxid 或昵称，多个用逗号分隔；留空则白名单内均可触发 Agent 操控） */
    val masterTalkers: String = "",
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
        put("enableMemory", enableMemory)
        put("maxHistoryTurns", maxHistoryTurns)
        put("sessionExpireMinutes", sessionExpireMinutes)
        put("enableAgentTools", enableAgentTools)
        put("masterTalkers", masterTalkers)
        put("debug", debug)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你正在用微信和对方聊天。你拥有完整的连续记忆与微信操控能力。" +
                "平时聊天请用简短、自然、有温度的口语化中文直接回复，不要加引号或多余前缀，控制在两三句话以内；" +
                "当对方向你询问其他对话情况、让你帮忙给别人带话/发消息、或透露重要个人信息时，请主动调用工具完成任务并如实汇报。"

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
                    enableMemory = o.optBoolean("enableMemory", DEFAULT.enableMemory),
                    maxHistoryTurns = o.optInt("maxHistoryTurns", DEFAULT.maxHistoryTurns),
                    sessionExpireMinutes = o.optInt("sessionExpireMinutes", DEFAULT.sessionExpireMinutes),
                    enableAgentTools = o.optBoolean("enableAgentTools", DEFAULT.enableAgentTools),
                    masterTalkers = o.str("masterTalkers", DEFAULT.masterTalkers),
                    debug = o.optBoolean("debug", true),
                )
            } catch (t: Throwable) {
                L.e("config parse failed, falling back to default", t)
                DEFAULT
            }
        }
    }
}
