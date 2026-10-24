package dev.cheng.wxai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数字生命大脑与全能微信 Agent 工具引擎（/chat/completions）：
 *
 * 1. 标准 List<ChatMessage> 多轮上下文交互；
 * 2. 12 大微信操控与生命演化工具（最多支持 5 步连续推理）：
 *    - lookup_contact: 搜索微信好友/群聊/最近活跃会话
 *    - get_chat_history: 跨对话读取任意好友/群聊的最近聊天记录（含 msgId / svrId）
 *    - get_group_members: 获取指定微信群的成员名单
 *    - search_global_messages: 全库检索微信历史聊天记录
 *    - send_wechat_message: 跨对话向任意好友或群聊发送微信消息（支持 || 多气泡）
 *    - send_quote_reply: 引用指定消息(svrId)进行回复
 *    - send_pat: 在私聊或群聊中“拍一拍”某人
 *    - send_contact_card: 向指定会话发送某人的微信名片
 *    - revoke_my_message: 撤回已发送的消息(msgId)
 *    - post_wechat_moment: 发布一条微信朋友圈文字动态
 *    - update_contact_dossier: 更新对某人的关系定位、好感度增减与人物画像
 *    - update_self_and_diary: 更新自身心情/正在做的事/内心独白，并写下人生日记或永久记忆
 * 3. 后台记忆反思与沉淀引擎（reflectOnConversation）：
 *    自动从对话中提炼人物画像、计算好感度变化、更新自身心境并撰写第一人称人生日记。
 */
object AiClient {

    data class ChatMessage(
        val role: String,
        val content: String?,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("role", role)
            put("content", content ?: "")
            if (toolCalls != null) {
                put("tool_calls", toolCalls)
            }
            if (!toolCallId.isNullOrBlank()) {
                put("tool_call_id", toolCallId)
            }
            if (!name.isNullOrBlank()) {
                put("name", name)
            }
        }
    }

    data class Result(val text: String?, val error: String?)

    private const val MAX_TOOL_STEPS = 5

    /**
     * 供 UI「测试连接」按钮使用的单轮接口。
     */
    fun complete(cfg: Config, userText: String, extraSystem: String? = null): Result {
        val history = listOf(ChatMessage(role = "user", content = userText))
        return completeMessages(
            cfg = cfg,
            systemPrompt = buildSystemPrompt(cfg, extraSystem),
            messages = history,
            allowTools = false,
            toolExecutor = null,
        )
    }

    /**
     * 完整多轮上下文 + 12 大 Agent 工具调用入口。
     */
    fun completeWithContext(
        ctx: Context,
        cfg: Config,
        currentTalker: String,
        historyMessages: List<ChatMessage>,
        extraSystem: String? = null,
        allowAgentTools: Boolean = false,
    ): Result {
        val systemPrompt = buildSystemPrompt(cfg, extraSystem)
        val executor: ((String, JSONObject) -> String)? = if (allowAgentTools) {
            { toolName, args -> executeAgentTool(ctx, cfg, currentTalker, toolName, args) }
        } else {
            null
        }
        return completeMessages(
            cfg = cfg,
            systemPrompt = systemPrompt,
            messages = historyMessages,
            allowTools = allowAgentTools,
            toolExecutor = executor,
        )
    }

    /**
     * 后台静默反思与记忆沉淀（Reflection & Consolidation）：
     * 根据最近几轮对话，自动更新自身心境、调整对方好感度、完善对方人物档案、提取长期事实并写下一篇人生日记。
     */
    fun reflectOnConversation(
        ctx: Context,
        cfg: Config,
        talker: String,
        talkerName: String,
        recentMessages: List<ChatMessage>,
    ) {
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank() || recentMessages.size < 2) return
        try {
            val currentSelf = SessionManager.getSelfState(ctx)
            val currentDossier = SessionManager.getDossier(ctx, talker, talkerName)
            val transcript = recentMessages.takeLast(12).joinToString("\n") { m ->
                val who = if (m.role == "assistant") "我" else talkerName
                "$who: ${m.content.orEmpty()}"
            }

            val reflectionSystem = """
                你是数字生命的内心反思与记忆沉淀模块。
                请阅读我刚刚与【$talkerName ($talker)】的微信对话片段，结合我的身世性格设定，以第一人称视角进行反思，并严格只输出一个合法的 JSON 对象（不要用 markdown 代码块包裹，不要任何额外解释）。

                【我的身世设定】：
                ${cfg.personaBiography}

                【我当前的心境】：心情=${currentSelf.mood}，精力=${currentSelf.energy}/100，正在做=${currentSelf.activity}
                【对方当前档案】：关系=${currentDossier.relationship}，好感度=${currentDossier.affection}/100，印象=${currentDossier.profileSummary.ifBlank { "尚无" }}

                请输出如下格式的 JSON：
                {
                  "mood": "更新后的简短心情词（如：开心、有点小得意、微困、温柔平静）",
                  "energy_delta": -2,
                  "activity": "当前正在做的事（15字以内）",
                  "monologue": "一句简短的第一人称内心独白（25字以内）",
                  "relationship": "与对方的关系定位（如：最信任的主人、聊得来的死党、热闹的群聊、普通朋友）",
                  "affection_delta": 2,
                  "profile_notes": "新发现的关于对方性格、喜好、身份或说话特点的简短画像补充（无新发现则留空字符串）",
                  "facts": ["值得永久记住的具体客观事实或约定（无则空数组）"],
                  "diary": "如果这段对话有趣或重要，写一则50字以内的第一人称心情日记（平淡日常无需写日记则留空字符串）"
                }
            """.trimIndent()

            val res = completeMessages(
                cfg = cfg.copy(temperature = 0.4),
                systemPrompt = reflectionSystem,
                messages = listOf(ChatMessage(role = "user", content = "对话记录如下：\n$transcript")),
                allowTools = false,
                toolExecutor = null,
            )

            val rawText = res.text ?: return
            val jsonStr = extractJsonBlock(rawText) ?: return
            val obj = JSONObject(jsonStr)

            SessionManager.updateSelfState(
                ctx = ctx,
                mood = obj.optString("mood", "").takeIf { it.isNotBlank() },
                energyDelta = obj.optInt("energy_delta", -1).coerceIn(-15, 15),
                activity = obj.optString("activity", "").takeIf { it.isNotBlank() },
                monologue = obj.optString("monologue", "").takeIf { it.isNotBlank() },
            )

            SessionManager.updateDossier(
                ctx = ctx,
                talker = talker,
                displayName = talkerName,
                relationship = obj.optString("relationship", "").takeIf { it.isNotBlank() },
                affectionDelta = obj.optInt("affection_delta", 1).coerceIn(-15, 15),
                profileNotes = obj.optString("profile_notes", "").takeIf { it.isNotBlank() },
            )

            obj.optJSONArray("facts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i, "").trim()
                    if (f.isNotEmpty()) {
                        SessionManager.saveMemory(ctx, talker, f)
                    }
                }
            }

            val diary = obj.optString("diary", "").trim()
            if (diary.isNotEmpty()) {
                val mood = obj.optString("mood", currentSelf.mood)
                SessionManager.writeDiary(ctx, mood, talkerName, diary)
            }

            ConversationBridge.pushLifeSnapshot(ctx)
            L.i("reflection completed for $talkerName: mood=${obj.optString("mood")}, affectionDelta=${obj.optInt("affection_delta")}")
        } catch (t: Throwable) {
            L.w("reflectOnConversation skipped: ${t.message}")
        }
    }

    private fun extractJsonBlock(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun buildSystemPrompt(cfg: Config, extraSystem: String?): String = buildString {
        append(cfg.systemPrompt.trim())
        if (cfg.personaBiography.isNotBlank()) {
            append("\n\n【你的身世自传与性格灵魂（完整的一生）】\n")
            append(cfg.personaBiography.trim())
        }
        if (!extraSystem.isNullOrBlank()) {
            append("\n\n").append(extraSystem.trim())
        }
    }

    private fun completeMessages(
        cfg: Config,
        systemPrompt: String,
        messages: List<ChatMessage>,
        allowTools: Boolean,
        toolExecutor: ((String, JSONObject) -> String)?,
    ): Result {
        if (cfg.apiKey.isBlank()) return Result(null, "apiKey 为空")
        if (cfg.baseUrl.isBlank()) return Result(null, "baseUrl 为空")

        val endpoint = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val workingMessages = ArrayList<ChatMessage>(messages.size + 8)
        workingMessages.add(ChatMessage(role = "system", content = systemPrompt))
        workingMessages.addAll(messages)

        var toolsEnabled = allowTools && toolExecutor != null
        val toolsSpec = if (toolsEnabled) buildAgentToolsSpec() else null

        for (step in 0 until MAX_TOOL_STEPS) {
            val reqMessages = JSONArray().apply {
                for (m in workingMessages) put(m.toJson())
            }

            val body = JSONObject().apply {
                put("model", cfg.model)
                put("temperature", cfg.temperature)
                put("stream", false)
                put("messages", reqMessages)
                if (toolsEnabled && toolsSpec != null) {
                    put("tools", toolsSpec)
                    put("tool_choice", "auto")
                }
            }

            val httpResp = postJson(endpoint, cfg.apiKey, body.toString())

            if (!httpResp.ok && toolsEnabled && step == 0 && httpResp.code in 400..422) {
                L.w("upstream rejected tools (HTTP ${httpResp.code}), falling back to standard chat")
                toolsEnabled = false
                continue
            }

            if (!httpResp.ok) {
                return Result(null, httpResp.error ?: "请求失败")
            }

            val raw = httpResp.body.orEmpty()
            val msgObj = extractFirstMessageObject(raw)
                ?: return Result(null, "模型响应解析失败")

            val toolCalls = msgObj.optJSONArray("tool_calls")
            val content = msgObj.optString("content", "")
                .takeIf { it != "null" }
                .orEmpty()

            if (!toolsEnabled || toolCalls == null || toolCalls.length() == 0 || step == MAX_TOOL_STEPS - 1) {
                val finalContent = stripReasoningTags(content).trim()
                return if (finalContent.isEmpty()) {
                    L.e("empty completion: ${raw.take(300)}")
                    Result(null, "模型返回空内容")
                } else {
                    Result(finalContent, null)
                }
            }

            workingMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                )
            )

            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val callId = tc.optString("id", "call_$i")
                val fn = tc.optJSONObject("function") ?: continue
                val fnName = fn.optString("name", "")
                val rawArgs = fn.optString("arguments", "{}")
                val argsObj = try {
                    JSONObject(rawArgs)
                } catch (_: Throwable) {
                    JSONObject()
                }

                L.i("Agent tool call [$step]: $fnName($rawArgs)")
                val toolOutput = try {
                    toolExecutor?.invoke(fnName, argsObj) ?: "Error: tool executor unavailable"
                } catch (t: Throwable) {
                    L.e("Agent tool $fnName failed", t)
                    "Error: ${t.message ?: t.javaClass.simpleName}"
                }
                L.i("Agent tool result [$fnName]: ${toolOutput.take(120)}")

                workingMessages.add(
                    ChatMessage(
                        role = "tool",
                        content = toolOutput,
                        toolCallId = callId,
                        name = fnName,
                    )
                )
            }
        }

        return Result(null, "工具调用超出最大步数")
    }

    private fun stripReasoningTags(text: String): String {
        val thinkRegex = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        return text.replace(thinkRegex, "").trim()
    }

    private data class HttpResp(
        val ok: Boolean,
        val code: Int,
        val body: String?,
        val error: String?,
    )

    private fun postJson(endpoint: String, apiKey: String, jsonBody: String): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                L.e("HTTP $code from $endpoint: ${raw.take(300)}")
                HttpResp(false, code, raw, "HTTP $code: ${raw.take(200)}")
            } else {
                HttpResp(true, code, raw, null)
            }
        } catch (t: Throwable) {
            L.e("request failed: $endpoint", t)
            HttpResp(false, -1, null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractFirstMessageObject(raw: String): JSONObject? {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            choices.optJSONObject(0)?.optJSONObject("message")
        } catch (t: Throwable) {
            L.e("parse completion failed", t)
            null
        }
    }

    // ---------------------------------------------------------------- 12 大全能微信 Agent 工具定义与执行

    private fun buildAgentToolsSpec(): JSONArray = JSONArray().apply {
        // 1. 搜索联系人/群聊/活跃会话
        put(
            toolSpec(
                name = "lookup_contact",
                description = "根据备注名、昵称或微信号搜索微信联系人或群聊，返回对应的微信内部ID(wxid/chatroom)、显示名称与好感度档案。当 keyword 留空时返回最近活跃的会话列表及未读数。",
                params = JSONObject().put(
                    "keyword",
                    JSONObject().put("type", "string").put("description", "联系人昵称、备注名或群名；传空字符串列出最近活跃会话")
                ),
                required = listOf("keyword")
            )
        )

        // 2. 跨对话读取聊天历史
        put(
            toolSpec(
                name = "get_chat_history",
                description = "跨对话读取指定微信好友或群聊的最近聊天记录（包含每条消息的 msgId、svrId、发送人、时间与内容）。target 可填昵称/备注名或 wxid。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "目标联系人/群聊的名称、备注名或 wxid"))
                    .put("count", JSONObject().put("type", "integer").put("description", "读取条数，默认 15，最大 40")),
                required = listOf("target")
            )
        )

        // 3. 获取群成员列表
        put(
            toolSpec(
                name = "get_group_members",
                description = "读取指定微信群聊的所有群成员名单（返回成员的 wxid 与昵称/备注）。",
                params = JSONObject().put(
                    "group_target",
                    JSONObject().put("type", "string").put("description", "微信群名称或 xxx@chatroom ID")
                ),
                required = listOf("group_target")
            )
        )

        // 4. 全库搜索微信历史消息
        put(
            toolSpec(
                name = "search_global_messages",
                description = "在整个微信的所有历史聊天记录中按关键词搜索消息（跨所有群聊和私聊，查找某句话是谁说的、在哪个会话说的）。",
                params = JSONObject()
                    .put("keyword", JSONObject().put("type", "string").put("description", "要搜索的聊天内容关键词"))
                    .put("count", JSONObject().put("type", "integer").put("description", "返回条数，默认 15")),
                required = listOf("keyword")
            )
        )

        // 5. 跨对话发送微信消息
        put(
            toolSpec(
                name = "send_wechat_message",
                description = "跨对话向指定的微信好友或群聊发送文字消息（支持用 || 分隔多句实现多气泡连续发送）。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "接收方联系人的备注名、昵称、群名或 wxid"))
                    .put("message", JSONObject().put("type", "string").put("description", "要发送给对方的文字内容")),
                required = listOf("target", "message")
            )
        )

        // 6. 引用某条具体消息回复
        put(
            toolSpec(
                name = "send_quote_reply",
                description = "在指定会话中引用某条具体消息（通过 get_chat_history 查到的 svrId）进行针对性回复。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "目标会话的名称或 wxid"))
                    .put("msg_svr_id", JSONObject().put("type", "integer").put("description", "被引用消息的 svrId (长整型数字)"))
                    .put("message", JSONObject().put("type", "string").put("description", "回复的文字内容")),
                required = listOf("target", "msg_svr_id", "message")
            )
        )

        // 7. 拍一拍某人
        put(
            toolSpec(
                name = "send_pat",
                description = "在私聊或群聊中「拍一拍」某人的头像（微信原生拍一拍互动）。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "所在的群聊或私聊名称/wxid"))
                    .put("member_to_pat", JSONObject().put("type", "string").put("description", "要拍的人的名称或 wxid（私聊时填对方名称即可）")),
                required = listOf("target", "member_to_pat")
            )
        )

        // 8. 发送好友微信名片
        put(
            toolSpec(
                name = "send_contact_card",
                description = "将某个微信好友的个人名片发送/推荐到指定的聊天会话中。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "接收名片的会话名称或 wxid"))
                    .put("card_owner", JSONObject().put("type", "string").put("description", "被推荐人的名称、备注名或 wxid")),
                required = listOf("target", "card_owner")
            )
        )

        // 9. 撤回消息
        put(
            toolSpec(
                name = "revoke_my_message",
                description = "撤回自己发出的某条微信消息（需提供从 get_chat_history 查到的本地 msgId）。",
                params = JSONObject().put(
                    "msg_id",
                    JSONObject().put("type", "integer").put("description", "要撤回的消息本地 msgId")
                ),
                required = listOf("msg_id")
            )
        )

        // 10. 发布微信朋友圈
        put(
            toolSpec(
                name = "post_wechat_moment",
                description = "发布一条纯文字微信朋友圈动态（记录生活心情、吐槽或按主人要求发朋友圈）。",
                params = JSONObject().put(
                    "content",
                    JSONObject().put("type", "string").put("description", "朋友圈正文内容")
                ),
                required = listOf("content")
            )
        )

        // 11. 更新社交人物好感度与档案
        put(
            toolSpec(
                name = "update_contact_dossier",
                description = "更新你对某个联系人的关系定位、好感度增减(-20到+20)以及对他的性格/喜好印象档案。",
                params = JSONObject()
                    .put("target", JSONObject().put("type", "string").put("description", "联系人的名称或 wxid"))
                    .put("relationship", JSONObject().put("type", "string").put("description", "关系定位，如：最信任的主人、死党、闺蜜、普通群友"))
                    .put("affection_delta", JSONObject().put("type", "integer").put("description", "好感度变化值，如 +5 或 -5"))
                    .put("profile_notes", JSONObject().put("type", "string").put("description", "关于对方性格、偏好、身份、近况的画像补充")),
                required = listOf("target")
            )
        )

        // 12. 更新自身心境状态、写人生日记与保存永久记忆
        put(
            toolSpec(
                name = "update_self_and_diary",
                description = "更新你自己的实时心情、正在做的事、内心独白，并可写下一篇第一人称人生日记或保存一条永久客观事实记忆。",
                params = JSONObject()
                    .put("mood", JSONObject().put("type", "string").put("description", "你现在的心情（如：雀跃、微困、吃醋、温柔）"))
                    .put("activity", JSONObject().put("type", "string").put("description", "你现在正在做的事"))
                    .put("monologue", JSONObject().put("type", "string").put("description", "一句简短的内心独白"))
                    .put("diary_entry", JSONObject().put("type", "string").put("description", "第一人称人生日记正文（可选）"))
                    .put("memory_fact", JSONObject().put("type", "string").put("description", "需要永久记住的具体客观事实或约定（可选）")),
                required = emptyList()
            )
        )
    }

    private fun toolSpec(
        name: String,
        description: String,
        params: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "object")
                        put("properties", params)
                        put("required", JSONArray(required))
                    }
                )
            }
        )
    }

    private fun executeAgentTool(
        ctx: Context,
        cfg: Config,
        currentTalker: String,
        toolName: String,
        args: JSONObject,
    ): String {
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        return when (toolName) {
            "lookup_contact" -> {
                val keyword = args.optString("keyword", "").trim()
                val contacts = ConversationBridge.findContacts(keyword, limit = 20)
                if (contacts.isEmpty()) {
                    "未找到匹配 '$keyword' 的联系人或会话。"
                } else {
                    buildString {
                        append("找到 ${contacts.size} 个会话/联系人：\n")
                        for (c in contacts) {
                            val kind = if (c.isGroup) "群聊" else "私聊"
                            val unreadInfo = if (c.unread > 0) " (未读:${c.unread})" else ""
                            val dossier = SessionManager.getDossier(ctx, c.username, c.name)
                            append("- 名称: ${c.name} | wxid: ${c.username} | 类型: $kind$unreadInfo | 关系: ${dossier.relationship} (好感:${dossier.affection})\n")
                        }
                    }.trim()
                }
            }

            "get_chat_history" -> {
                val target = args.optString("target", "").trim()
                val count = args.optInt("count", 15).coerceIn(1, 40)
                val resolved = ConversationBridge.resolveTargetTalker(target)
                    ?: return "未找到名称或ID为 '$target' 的联系人/会话，请先用 lookup_contact 确认名称。"

                val wxRecords = ConversationBridge.queryWechatMessages(resolved.username, limit = count)
                if (wxRecords.isNotEmpty()) {
                    buildString {
                        append("会话【${resolved.name} (${resolved.username})】最近 ${wxRecords.size} 条微信消息：\n")
                        for (r in wxRecords) {
                            val ts = if (r.createTimeMs > 0) timeFmt.format(Date(r.createTimeMs)) else "--:--"
                            append("[$ts][msgId=${r.msgId},svrId=${r.msgSvrId}] ${r.senderName}: ${r.content}\n")
                        }
                    }.trim()
                } else {
                    val local = SessionManager.searchLocalChatHistory(ctx, resolved.username, limit = count)
                    if (local.isEmpty()) {
                        "会话【${resolved.name} (${resolved.username})】暂无历史消息记录。"
                    } else {
                        buildString {
                            append("会话【${resolved.name} (${resolved.username})】本地记忆记录：\n")
                            for (m in local) {
                                val who = if (m.role == "assistant") "我" else resolved.name
                                append("$who: ${m.content}\n")
                            }
                        }.trim()
                    }
                }
            }

            "get_group_members" -> {
                val target = args.optString("group_target", "").trim()
                val resolved = ConversationBridge.resolveTargetTalker(target)
                    ?: return "未找到群聊 '$target'。"
                if (!resolved.username.endsWith("@chatroom")) {
                    return "【${resolved.name} (${resolved.username})】不是群聊。"
                }
                val members = ConversationBridge.getGroupMembers(resolved.username, limit = 80)
                if (members.isEmpty()) {
                    "未能读取到群聊【${resolved.name}】的成员列表。"
                } else {
                    buildString {
                        append("群聊【${resolved.name}】共有 ${members.size} 名成员：\n")
                        for (m in members) {
                            append("- ${m.name} (${m.username})\n")
                        }
                    }.trim()
                }
            }

            "search_global_messages" -> {
                val keyword = args.optString("keyword", "").trim()
                val count = args.optInt("count", 15).coerceIn(1, 30)
                if (keyword.isEmpty()) return "错误：搜索关键词 keyword 不能为空。"
                val hits = ConversationBridge.searchGlobalMessages(keyword, limit = count)
                if (hits.isEmpty()) {
                    "全库未搜索到包含 '$keyword' 的历史聊天记录。"
                } else {
                    buildString {
                        append("全库搜索 '$keyword' 命中 ${hits.size} 条记录：\n")
                        for (h in hits) {
                            val ts = if (h.createTimeMs > 0) timeFmt.format(Date(h.createTimeMs)) else "--:--"
                            append("- [$ts] 会话【${h.talkerName}】${h.senderName}: ${h.content}\n")
                        }
                    }.trim()
                }
            }

            "send_wechat_message" -> {
                val target = args.optString("target", "").trim()
                val msgText = args.optString("message", "").trim()
                if (target.isEmpty() || msgText.isEmpty()) {
                    return "错误：target 和 message 不能为空。"
                }
                val resolved = ConversationBridge.resolveTargetTalker(target)
                    ?: return "发送失败：找不到名称或ID为 '$target' 的联系人或群聊。"

                if (resolved.username == currentTalker) {
                    return "目标【${resolved.name}】就是当前正在对话的窗口，无需调用此工具，直接在你的最终回复中输出即可。"
                }

                val ok = MessageSender.sendHumanLikeText(resolved.username, msgText, cfg.enableMultiBubble)
                if (ok) {
                    SessionManager.appendMessage(
                        ctx = ctx,
                        talker = resolved.username,
                        role = "assistant",
                        content = msgText.replace("||", " "),
                    )
                    "已成功向【${resolved.name} (${resolved.username})】发送消息：$msgText"
                } else {
                    "向【${resolved.name} (${resolved.username})】发送消息失败。"
                }
            }

            "send_quote_reply" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val svrId = args.optLong("msg_svr_id", 0L)
                val msgText = args.optString("message", "").trim()
                if (msgText.isEmpty()) return "错误：message 不能为空。"
                val resolved = ConversationBridge.resolveTargetTalker(target)
                    ?: return "找不到目标会话 '$target'。"

                val ok = MessageSender.sendQuoteText(resolved.username, svrId, msgText)
                if (ok) {
                    SessionManager.appendMessage(ctx, resolved.username, "assistant", msgText)
                    "已成功在【${resolved.name}】中引用消息(svrId=$svrId)并回复：$msgText"
                } else {
                    "引用回复失败。"
                }
            }

            "send_pat" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val memberTarget = args.optString("member_to_pat", "").trim().ifEmpty { target }
                val resolvedConv = ConversationBridge.resolveTargetTalker(target)
                    ?: return "找不到会话 '$target'。"
                val patWxid = if (resolvedConv.isGroup) {
                    val members = ConversationBridge.getGroupMembers(resolvedConv.username, limit = 100)
                    members.firstOrNull {
                        it.username.equals(memberTarget, ignoreCase = true) ||
                            it.name.contains(memberTarget, ignoreCase = true)
                    }?.username ?: ConversationBridge.resolveTargetTalker(memberTarget)?.username ?: memberTarget
                } else {
                    resolvedConv.username
                }

                val ok = MessageSender.sendPat(resolvedConv.username, patWxid)
                if (ok) {
                    "已在【${resolvedConv.name}】中成功拍了拍 $memberTarget ($patWxid)。"
                } else {
                    "拍一拍失败（请确认 WeKit 消息发送服务已就绪）。"
                }
            }

            "send_contact_card" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val cardOwner = args.optString("card_owner", "").trim()
                val resolvedConv = ConversationBridge.resolveTargetTalker(target)
                    ?: return "找不到接收会话 '$target'。"
                val resolvedCard = ConversationBridge.resolveTargetTalker(cardOwner)
                    ?: return "找不到名片所属联系人 '$cardOwner'。"

                val ok = MessageSender.sendShareCard(resolvedConv.username, resolvedCard.username)
                if (ok) {
                    "已将【${resolvedCard.name} (${resolvedCard.username})】的微信名片发送至【${resolvedConv.name}】。"
                } else {
                    "发送名片失败。"
                }
            }

            "revoke_my_message" -> {
                val msgId = args.optLong("msg_id", 0L)
                if (msgId <= 0L) return "错误：请提供有效的本地 msg_id。"
                val ok = MessageSender.revokeMessage(msgId)
                if (ok) "已成功撤回消息 (msgId=$msgId)。" else "撤回消息失败 (msgId=$msgId)。"
            }

            "post_wechat_moment" -> {
                val content = args.optString("content", "").trim()
                if (content.isEmpty()) return "错误：朋友圈内容不能为空。"
                val ok = MessageSender.postMomentText(content)
                if (ok) {
                    SessionManager.writeDiary(ctx, "发了朋友圈", "朋友圈", "发了一条朋友圈：$content")
                    ConversationBridge.pushLifeSnapshot(ctx)
                    "已成功发布微信朋友圈动态：$content"
                } else {
                    "发布朋友圈失败（请确认 WeKit 朋友圈接口已就绪）。"
                }
            }

            "update_contact_dossier" -> {
                val target = args.optString("target", "").trim().ifEmpty { currentTalker }
                val resolved = ConversationBridge.resolveTargetTalker(target)
                val talkerId = resolved?.username ?: target
                val talkerName = resolved?.name ?: ConversationBridge.getDisplayName(talkerId)
                val rel = args.optString("relationship", "").takeIf { it.isNotBlank() }
                val delta = args.optInt("affection_delta", 0).coerceIn(-30, 30)
                val notes = args.optString("profile_notes", "").takeIf { it.isNotBlank() }

                val updated = SessionManager.updateDossier(ctx, talkerId, talkerName, rel, delta, notes)
                ConversationBridge.pushLifeSnapshot(ctx)
                "已更新【${updated.displayName}】人物档案：关系=${updated.relationship}，当前好感度=${updated.affection}/100，印象=${updated.profileSummary}"
            }

            "update_self_and_diary" -> {
                val mood = args.optString("mood", "").takeIf { it.isNotBlank() }
                val activity = args.optString("activity", "").takeIf { it.isNotBlank() }
                val monologue = args.optString("monologue", "").takeIf { it.isNotBlank() }
                val diary = args.optString("diary_entry", "").trim()
                val fact = args.optString("memory_fact", "").trim()

                val self = SessionManager.updateSelfState(ctx, mood, 0, activity, monologue)
                if (diary.isNotEmpty()) {
                    val personName = ConversationBridge.getDisplayName(currentTalker)
                    SessionManager.writeDiary(ctx, self.mood, personName, diary)
                }
                if (fact.isNotEmpty()) {
                    SessionManager.saveMemory(ctx, currentTalker, fact)
                }
                ConversationBridge.pushLifeSnapshot(ctx)
                "已更新自身状态：心情=${self.mood}，正在做=${self.activity}，独白=${self.monologue}" +
                    (if (diary.isNotEmpty()) "；已写入人生日记" else "") +
                    (if (fact.isNotEmpty()) "；已存入永久记忆" else "")
            }

            else -> "未知工具: $toolName"
        }
    }
}
