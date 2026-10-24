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
 * OpenAI / DeepSeek 兼容多轮对话与 Agent 工具调用客户端（/chat/completions）。
 *
 * 核心能力：
 * 1. 支持标准 List<ChatMessage> 多轮上下文对话；
 * 2. 支持 Tool Calling（Function Calling）多步推理循环（最多 4 步）：
 *    - lookup_contact: 按备注/昵称搜索微信好友或群聊
 *    - get_chat_history: 跨对话读取任意好友/群聊的最近聊天记录
 *    - send_wechat_message: 跨对话向任意好友/群聊发送微信消息
 *    - save_memory: 将重要人物信息、偏好、事件写入永久 SQLite 记忆库
 *    - recall_memories: 搜索永久 SQLite 记忆库
 * 3. 若上游模型/中转接口不支持 tools 参数，自动无感降级为纯多轮对话。
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

    private const val MAX_TOOL_STEPS = 4

    /**
     * 供 UI「测试连接」按钮或极简场景使用的单轮接口。
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
     * 完整多轮上下文 + Agent 工具调用入口。
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
            { toolName, args -> executeAgentTool(ctx, currentTalker, toolName, args) }
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

    private fun buildSystemPrompt(cfg: Config, extraSystem: String?): String = buildString {
        append(cfg.systemPrompt.trim())
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
        val workingMessages = ArrayList<ChatMessage>(messages.size + 6)
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

            // 如果上游模型不支持 tools 字段（如部分 reasoner 模型报 400），自动关闭 tools 重试一次
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

            // 如果模型没有发起 tool_calls，或者已达到最后一步，直接返回文本回复
            if (!toolsEnabled || toolCalls == null || toolCalls.length() == 0 || step == MAX_TOOL_STEPS - 1) {
                val finalContent = stripReasoningTags(content).trim()
                return if (finalContent.isEmpty()) {
                    L.e("empty completion: ${raw.take(300)}")
                    Result(null, "模型返回空内容")
                } else {
                    Result(finalContent, null)
                }
            }

            // 模型发起了 tool_calls：先将 assistant 的 tool_calls 消息加入上下文
            workingMessages.add(
                ChatMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                )
            )

            // 依次执行每个工具调用，并将结果以 role="tool" 塞回上下文
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

    /** 剔除部分模型在 content 中夹带的 <think>...</think> 思考块 */
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

    // ---------------------------------------------------------------- Agent 工具定义与执行

    private fun buildAgentToolsSpec(): JSONArray = JSONArray().apply {
        // 1. 搜索联系人/群聊
        put(
            toolSpec(
                name = "lookup_contact",
                description = "根据备注名、昵称或微信号搜索微信联系人或群聊，返回对应的微信内部ID(wxid/chatroom)与显示名称。当关键词留空时返回最近活跃的会话列表。",
                params = JSONObject()
                    .put(
                        "keyword",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "要查找的联系人昵称、备注名或群名；传空字符串则列出最近活跃会话")
                    ),
                required = listOf("keyword")
            )
        )

        // 2. 跨对话读取聊天历史
        put(
            toolSpec(
                name = "get_chat_history",
                description = "跨对话读取指定微信好友或群聊的最近聊天记录。target 可以直接填对方的昵称/备注名（如“张三”）或 wxid。",
                params = JSONObject()
                    .put(
                        "target",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "目标联系人/群聊的名称、备注名或 wxid")
                    )
                    .put(
                        "count",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "要读取的消息条数，默认 15，最大 40")
                    ),
                required = listOf("target")
            )
        )

        // 3. 跨对话发送微信消息
        put(
            toolSpec(
                name = "send_wechat_message",
                description = "跨对话向指定的微信好友或群聊发送一条文字消息。仅在用户明确要求给他人或别的群带话、发消息、通知时调用。",
                params = JSONObject()
                    .put(
                        "target",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "接收方联系人的备注名、昵称、群名或 wxid")
                    )
                    .put(
                        "message",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "要发送给对方的具体文字内容")
                    ),
                required = listOf("target", "message")
            )
        )

        // 4. 保存长期记忆
        put(
            toolSpec(
                name = "save_memory",
                description = "将重要的事实、用户的个人信息（姓名、生日、喜好、讨厌的事、职业、关系）、重要约定或长期指令保存到永久记忆库中，以便日后跨对话依然记得。",
                params = JSONObject()
                    .put(
                        "scope",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "记忆归属对象：填 'global' 表示全局记忆，或填具体的联系人名称/wxid")
                    )
                    .put(
                        "fact",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "简明扼要的事实描述，例如：'张三喜欢喝冰美式，不吃香菜'")
                    ),
                required = listOf("scope", "fact")
            )
        )

        // 5. 检索长期记忆
        put(
            toolSpec(
                name = "recall_memories",
                description = "从永久记忆库中搜索历史记录的事实、人物画像或重要事件。",
                params = JSONObject()
                    .put(
                        "query",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "搜索关键词（如人名、事件词），或填 'all' 列出最近的所有记忆")
                    ),
                required = listOf("query")
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
                            append("- 名称: ${c.name} | wxid: ${c.username} | 类型: $kind$unreadInfo\n")
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
                            append("[$ts] ${r.senderName}: ${r.content}\n")
                        }
                    }.trim()
                } else {
                    // 回退查本地 SessionManager
                    val local = SessionManager.searchLocalChatHistory(ctx, resolved.username, limit = count)
                    if (local.isEmpty()) {
                        "会话【${resolved.name} (${resolved.username})】暂无历史消息记录。"
                    } else {
                        buildString {
                            append("会话【${resolved.name} (${resolved.username})】本地记忆记录：\n")
                            for (m in local) {
                                val who = if (m.role == "assistant") "我(AI)" else resolved.name
                                append("$who: ${m.content}\n")
                            }
                        }.trim()
                    }
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

                // 如果目标恰好就是当前正在回复的对话，提示模型直接在最终回复里说即可，避免重复发两次
                if (resolved.username == currentTalker) {
                    return "目标【${resolved.name}】就是当前对话窗口，无需调用此工具，请直接在你的最终回复中输出内容即可。"
                }

                ReplyEngine.markAiOutbound(resolved.username, msgText)
                val ok = MessageSender.sendText(resolved.username, msgText)
                if (ok) {
                    // 把跨对话主动发出的消息也记入对应联系人的 SessionManager 短期记忆中，保持上下文连贯
                    SessionManager.appendMessage(
                        ctx = ctx,
                        talker = resolved.username,
                        role = "assistant",
                        content = msgText,
                    )
                    "已成功向【${resolved.name} (${resolved.username})】发送消息：$msgText"
                } else {
                    "向【${resolved.name} (${resolved.username})】发送消息失败（底层发送队列未就绪）。"
                }
            }

            "save_memory" -> {
                val scope = args.optString("scope", "global").trim().ifEmpty { "global" }
                val fact = args.optString("fact", "").trim()
                if (fact.isEmpty()) return "错误：fact 不能为空。"

                // 若 scope 是人名，尝试顺带解析出其标准名称
                val normalizedScope = if (scope.equals("global", ignoreCase = true)) {
                    "global"
                } else {
                    ConversationBridge.resolveTargetTalker(scope)?.username ?: scope
                }
                val saved = SessionManager.saveMemory(ctx, normalizedScope, fact)
                if (saved) {
                    "成功保存长期记忆 [归属: $normalizedScope]: $fact"
                } else {
                    "保存长期记忆失败。"
                }
            }

            "recall_memories" -> {
                val query = args.optString("query", "all").trim()
                val items = SessionManager.searchMemories(ctx, query, limit = 30)
                if (items.isEmpty()) {
                    "永久记忆库中未找到关于 '$query' 的记录。"
                } else {
                    buildString {
                        append("检索到 ${items.size} 条长期记忆：\n")
                        for (item in items) {
                            val displayScope = if (item.scope == "global") {
                                "全局"
                            } else {
                                ConversationBridge.getDisplayName(item.scope)
                            }
                            append("- [$displayScope] ${item.fact}\n")
                        }
                    }.trim()
                }
            }

            else -> "未知工具: $toolName"
        }
    }
}
