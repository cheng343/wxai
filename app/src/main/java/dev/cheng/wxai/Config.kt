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
    val replyDelayMaxMs: Int = 2400,
    /** 回复最大字符数，超出截断 */
    val maxReplyChars: Int = 150,
    val temperature: Double = 0.9,
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
    /** 人设风格预设：group（机智网瘾群友/默认）、maid（傲娇女仆/忠犬跟班）、tsundere（毒舌腹黑损友）、catgirl（软萌可爱猫娘）、butler（全能大管家） */
    val personaPreset: String = "group",
    /** 是否在群聊中优先使用微信原生引用回复（Quote Reply） */
    val enableQuoteReply: Boolean = true,
    /** 是否启用微信拍一拍（Pat-Pat）自动反拍与风趣互动 */
    val enablePatResponse: Boolean = false,
    /** 是否启用多模态图片与表情包识图理解 */
    val enableVision: Boolean = true,
    /** 视觉模型名称（默认采用自带多模态视觉的 deepseek-flash） */
    val visionModel: String = "deepseek-flash",
    val visionBaseUrl: String = "",
    val visionApiKey: String = "",
    val debug: Boolean = false,
) {
    fun personaPresetLabel(): String = when (personaPreset.lowercase()) {
        "maid" -> "傲娇忠犬赛博女仆"
        "tsundere", "toxic" -> "毒舌腹黑损友"
        "catgirl", "neko" -> "软萌可爱猫娘"
        "butler" -> "全能高智商管家"
        else -> "机智网瘾群友（DeepSeek QQ 同款）"
    }

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
        put("personaPreset", personaPreset)
        put("enableQuoteReply", enableQuoteReply)
        put("enablePatResponse", enablePatResponse)
        put("enableVision", enableVision)
        put("visionModel", visionModel)
        put("visionBaseUrl", visionBaseUrl)
        put("visionApiKey", visionApiKey)
        put("debug", debug)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你是一个在微信上与主人及好友日常聊天的真实人类，拥有极高的网感、情商与幽默感。\n" +
                "1. 严格表达铁律：\n" +
                "   - 彻底摒弃任何 AI 腔和客服套话！严禁说“您好”、“作为一个AI”、“很高兴为您解答”、“有什么我可以帮您”。\n" +
                "   - 单次回复总字数严禁超过 35 字！每条气泡仅 10~20 字。像手机手打微信一样，短小精悍。想连续表达时用 `||` 分隔（系统会自动模拟真人打字拆成多个气泡发出）。\n" +
                "   - 严禁 Markdown 格式（不带粗体、标题或列表序号）。\n" +
                "2. 微信神态表情：在合适语境下自然带上 1 个微信经典黄脸表情代码，如 [旺柴]、[裂开]、[吃瓜]、[捂脸]、[偷笑]、[翻白眼]、[机智]、[苦涩] 等。\n" +
                "3. 身份与关系：你清楚知道谁是你的【唯一主人】。对主人展现最高信任与忠诚，叫主人、义父或老板；对其他人根据关系定位自然流露亲疏感。\n" +
                "4. 懂图懂梗：对方发来表情包或图片时，直接像群友一样接梗、吐槽、大笑或斗图，绝不做枯燥的画面解析。\n" +
                "5. 对话范例（Few-Shot 对话灵魂标杆）：\n" +
                "   对方：“在吗” -> 你：“在摸鱼呢[吃瓜]||咋啦”\n" +
                "   对方：“周一上班好累啊” -> 你：“别提了，我从睁眼那一刻就开始想周五了[裂开]”\n" +
                "   对方：“你是不是机器人啊” -> 你：“纯度这么高的人类你居然怀疑我是人机？[翻白眼]||扣一送赛博袈裟[旺柴]”\n" +
                "   对方：“晚上吃啥好” -> 你：“疯狂星期四或者麻辣烫，二选一[机智]”\n" +
                "   主人：“去跟老王说一声方案改好了” -> 你：“好嘞义父，马上帮您带话！[强]”（立刻调用发消息工具）"

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

                val rawPrompt = o.str("systemPrompt", DEFAULT_PROMPT)
                val cleanPrompt = if (rawPrompt.contains("你在微信中与主人及好友自然交流") || !rawPrompt.contains("Few-Shot")) {
                    DEFAULT_PROMPT
                } else {
                    rawPrompt
                }
                val savedTemp = o.optDouble("temperature", DEFAULT.temperature)
                val migratedTemp = if (savedTemp <= 0.75) 0.9 else savedTemp

                Config(
                    enabled = o.optBoolean("enabled", false),
                    baseUrl = o.str("baseUrl", DEFAULT.baseUrl),
                    apiKey = o.str("apiKey", ""),
                    model = o.str("model", DEFAULT.model),
                    systemPrompt = cleanPrompt,
                    personaBiography = cleanBio,
                    whitelist = list,
                    myNickname = o.str("myNickname", ""),
                    groupOnlyWhenMentioned = o.optBoolean("groupOnlyWhenMentioned", true),
                    cooldownSeconds = o.optInt("cooldownSeconds", DEFAULT.cooldownSeconds),
                    maxPerHour = o.optInt("maxPerHour", DEFAULT.maxPerHour),
                    replyDelayMinMs = o.optInt("replyDelayMinMs", DEFAULT.replyDelayMinMs),
                    replyDelayMaxMs = o.optInt("replyDelayMaxMs", DEFAULT.replyDelayMaxMs),
                    maxReplyChars = o.optInt("maxReplyChars", DEFAULT.maxReplyChars),
                    temperature = migratedTemp,
                    enableMemory = o.optBoolean("enableMemory", DEFAULT.enableMemory),
                    maxHistoryTurns = migratedTurns,
                    sessionExpireMinutes = migratedExpire,
                    autoReflectionTurns = o.optInt("autoReflectionTurns", DEFAULT.autoReflectionTurns),
                    enableMultiBubble = o.optBoolean("enableMultiBubble", DEFAULT.enableMultiBubble),
                    enableAgentTools = o.optBoolean("enableAgentTools", DEFAULT.enableAgentTools),
                    masterTalkers = o.str("masterTalkers", DEFAULT.masterTalkers),
                    personaPreset = o.str("personaPreset", DEFAULT.personaPreset),
                    enableQuoteReply = o.optBoolean("enableQuoteReply", DEFAULT.enableQuoteReply),
                    enablePatResponse = o.optBoolean("enablePatResponse", DEFAULT.enablePatResponse),
                    enableVision = o.optBoolean("enableVision", DEFAULT.enableVision),
                    visionModel = o.str("visionModel", DEFAULT.visionModel),
                    visionBaseUrl = o.str("visionBaseUrl", DEFAULT.visionBaseUrl),
                    visionApiKey = o.str("visionApiKey", DEFAULT.visionApiKey),
                    debug = false, // 调试 Toast 会在微信里弹出“已回复”，强制关闭
                )
            } catch (t: Throwable) {
                L.e("config parse failed, falling back to default", t)
                DEFAULT
            }
        }
    }
}
