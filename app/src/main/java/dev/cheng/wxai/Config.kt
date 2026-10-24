package dev.cheng.wxai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置。JSON 是模块 App 与微信进程之间唯一的配置载体：
 * 模块 App 写 SharedPreferences 并序列化成 JSON，微信进程经 ConfigBridge 读 ContentProvider。
 *
 * 改动字段时三处必须同步：这里、SettingsScreen 的表单、ConfigProvider 的输出。
 */
data class Config(
    val enabled: Boolean = false,
    /** OpenAI 兼容 base url，末尾可带 /v1，不要带 /chat/completions */
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String = DEFAULT_PROMPT,
    /** 自定义背景设定（默认留空，不强加任何虚构人设） */
    val personaBiography: String = DEFAULT_BIOGRAPHY,
    /** 白名单：wxid 或 xxx@chatroom；空 = 不回复任何人（安全默认） */
    val whitelist: List<String> = emptyList(),
    /** 自己的昵称，群聊判定 @我 时用；留空则自动读取当前登录微信号昵称 */
    val myNickname: String = "",
    /** 群聊只在似乎被 @ 时回复 */
    val groupOnlyWhenMentioned: Boolean = true,
    /** 同一会话两次自动回复的最小间隔（秒） */
    val cooldownSeconds: Int = 3,
    /** 每会话每小时上限 */
    val maxPerHour: Int = 60,
    val replyDelayMinMs: Int = 1200,
    val replyDelayMaxMs: Int = 2600,
    /** 回复最大字符数，超出截断 */
    val maxReplyChars: Int = 500,
    val temperature: Double = 0.7,
    /** 是否启用本地 SQLite 多轮会话记忆、好感度图谱与人生日记 */
    val enableMemory: Boolean = true,
    /** 单会话最大保留上下文轮数（1 轮 = 1 问 + 1 答，默认 100 轮 = 200 条消息，采用分块锚定保障 Prefix KV 缓存命中） */
    val maxHistoryTurns: Int = 100,
    /** 会话超时时间（分钟），0 表示永不断片（跨天保持完整上下文与 KV 缓存） */
    val sessionExpireMinutes: Int = 0,
    /** 每隔多少轮对话在后台自动进行一次记忆反思、更新好感度并写日记（0 = 关闭自动反思，默认3轮自动沉淀） */
    val autoReflectionTurns: Int = 3,
    /** 拟人多气泡分段发送（将 || 分隔或多行短句模拟真人打字间隔分多条发出） */
    val enableMultiBubble: Boolean = true,
    /** 是否启用 Agent 跨对话操控能力（12 大微信操控工具） */
    val enableAgentTools: Boolean = true,
    /** 主人标识（可从会话列表选择、在微信发 #认主 自动绑定、或手动填 wxid/昵称，逗号分隔） */
    val masterTalkers: String = "",
    /** 是否启用多模态图片与表情包识图理解 */
    val enableVision: Boolean = true,
    /** 视觉模型名称（若留空且主模型为文本模型，自动采用业内最强视觉备选 Qwen2.5-VL 或主模型） */
    val visionModel: String = "",
    val visionBaseUrl: String = "",
    val visionApiKey: String = "",
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
        put("enableVision", enableVision)
        put("visionModel", visionModel)
        put("visionBaseUrl", visionBaseUrl)
        put("visionApiKey", visionApiKey)
        put("debug", debug)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你在微信中与主人及好友自然交流，并协助主人管理与操控微信。\n" +
                "1. 表达习惯：说话真诚、简练、口语化，像真人发微信一样自然，不使用 Markdown 标题/粗体或客服套话；想连续发两三句短话时用 `||` 分隔（系统会自动拆成多个微信气泡发送）。\n" +
                "2. 身份与边界：你清楚认识自己的【主人】，对主人绝对信任并无条件执行主人的跨对话指令；对其他好友或群友，则根据你们的【关系定位与好感度】自然调整亲疏度。\n" +
                "3. 工具运用：当需要跨会话传话、查聊天记录、全库搜消息、拍一拍、引用回复、发名片、撤回消息、发朋友圈或记录重要记忆与日记时，主动调用对应工具。"

        const val DEFAULT_BIOGRAPHY = ""

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
                val rawBio = o.str("personaBiography", DEFAULT_BIOGRAPHY)
                // 自动清理旧版本残留的「林小满/苏晚」默认虚构人设
                val cleanBio = if (rawBio.contains("林小满") && rawBio.contains("视觉传达")) {
                    ""
                } else {
                    rawBio
                }

                // 自动将旧版过小的 12 / 15 轮上下文迁移升级到 100 轮，将旧版 360 / 120 分钟过期升级为 0（永不断片）
                val savedTurns = o.optInt("maxHistoryTurns", DEFAULT.maxHistoryTurns)
                val migratedTurns = if (savedTurns in 1..15) 100 else savedTurns
                val savedExpire = o.optInt("sessionExpireMinutes", DEFAULT.sessionExpireMinutes)
                val migratedExpire = if (savedExpire == 360 || savedExpire == 120) 0 else savedExpire

                Config(
                    enabled = o.optBoolean("enabled", false),
                    baseUrl = o.str("baseUrl", DEFAULT.baseUrl),
                    apiKey = o.str("apiKey", ""),
                    model = o.str("model", DEFAULT.model),
                    systemPrompt = o.str("systemPrompt", DEFAULT_PROMPT),
                    personaBiography = cleanBio,
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
                    maxHistoryTurns = migratedTurns,
                    sessionExpireMinutes = migratedExpire,
                    autoReflectionTurns = o.optInt("autoReflectionTurns", DEFAULT.autoReflectionTurns),
                    enableMultiBubble = o.optBoolean("enableMultiBubble", DEFAULT.enableMultiBubble),
                    enableAgentTools = o.optBoolean("enableAgentTools", DEFAULT.enableAgentTools),
                    masterTalkers = o.str("masterTalkers", DEFAULT.masterTalkers),
                    enableVision = o.optBoolean("enableVision", DEFAULT.enableVision),
                    visionModel = o.str("visionModel", DEFAULT.visionModel),
                    visionBaseUrl = o.str("visionBaseUrl", DEFAULT.visionBaseUrl),
                    visionApiKey = o.str("visionApiKey", DEFAULT.visionApiKey),
                    debug = o.optBoolean("debug", true),
                )
            } catch (t: Throwable) {
                L.e("config parse failed, falling back to default", t)
                DEFAULT
            }
        }
    }
}
