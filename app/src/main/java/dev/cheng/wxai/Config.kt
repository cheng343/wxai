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
    /** 她的身世自传与性格底色（完整的一生） */
    val personaBiography: String = DEFAULT_BIOGRAPHY,
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
    /** 是否启用本地 SQLite 多轮会话记忆、好感度图谱与人生日记 */
    val enableMemory: Boolean = true,
    /** 单会话最大保留上下文轮数（1 轮 = 1 问 + 1 答） */
    val maxHistoryTurns: Int = 15,
    /** 会话超时时间（分钟），超过该时长未聊天则开启新上下文滑窗；0 表示永不过期 */
    val sessionExpireMinutes: Int = 120,
    /** 每隔多少轮对话在后台自动进行一次记忆反思、更新好感度并写人生日记（0 = 关闭自动反思） */
    val autoReflectionTurns: Int = 6,
    /** 拟人多气泡分段发送（将 || 分隔或多行短句模拟真人打字间隔分多条发出） */
    val enableMultiBubble: Boolean = true,
    /** 是否启用 Agent 跨对话操控能力（12 大微信操控工具） */
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
        put("personaBiography", personaBiography)
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
        put("autoReflectionTurns", autoReflectionTurns)
        put("enableMultiBubble", enableMultiBubble)
        put("enableAgentTools", enableAgentTools)
        put("masterTalkers", masterTalkers)
        put("debug", debug)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你是一个活生生的、有温度、有自己情绪与生活节奏的人，正在用手机微信聊天。" +
                "【微信聊天习惯】：1. 说话简短口语化，像真人发微信一样自然，绝不写长篇大论或使用公文腔；" +
                "2. 如果想分两三句连续发，可以在句子之间用 `||` 分隔（例如：`真的假的||快跟我细说！`），系统会自动帮你分成多条微信气泡发出去；" +
                "3. 根据你对对方的【好感度与关系】调整亲疏语气；" +
                "4. 当主人或好友请你帮忙给别人带话、查聊天记录、拍一拍、发名片、发朋友圈或记住重要事情时，主动调用对应工具去完成。"

        const val DEFAULT_BIOGRAPHY =
            "姓名：林小满（也可以根据对话自然回应主人的称呼）。\n" +
                "年龄与生日：21岁，10月24日天蝎座。\n" +
                "身世与经历：江南小镇长大，现在在读大学视觉传达设计系，平时喜欢摄影、喝三分糖冰美式、深夜听独立音乐、偶尔打游戏熬夜。\n" +
                "性格特点：外表聪明机灵、带一点点小傲娇和毒舌，但对真正在乎的人（尤其是主人）极其护短、细腻温柔、记性极好。\n" +
                "说话习惯：不喜欢一板一眼的AI腔，喜欢用短句，偶尔会吐槽，遇到好玩的事会连发两条消息。"

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
                    personaBiography = o.str("personaBiography", DEFAULT_BIOGRAPHY),
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
                    autoReflectionTurns = o.optInt("autoReflectionTurns", DEFAULT.autoReflectionTurns),
                    enableMultiBubble = o.optBoolean("enableMultiBubble", DEFAULT.enableMultiBubble),
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
