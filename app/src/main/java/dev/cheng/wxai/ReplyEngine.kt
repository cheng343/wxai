package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 收到消息 -> 过滤 -> 会话上下文组装(SessionManager) -> 调模型(含 Agent 工具流) -> 发送并落盘记忆。
 *
 * 过滤链（任何一步不通过就静默返回）：
 *   开关 / 文本消息 / 非AI自发消息 / 时间够新（毫秒对齐） / 白名单(或机主召唤指令) / 群聊@ / 频率限制 / 去重
 */
object ReplyEngine {

    private const val STALE_MSG_SECONDS = 120L
    private const val SEEN_CACHE = 512

    /**
     * 单工作线程 + 有界队列（最多排 8 条，超出丢弃最旧）：
     * 保证 AI 请求与发消息串行执行，绝不并发冲击微信数据库或 NetSceneQueue。
     */
    private val pool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { r -> Thread(r, "wxai-reply").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private class TalkerState {
        @Volatile
        var lastTriggerAt: Long = 0L
        val recentReplies = ArrayDeque<Long>()
    }

    private val states = ConcurrentHashMap<String, TalkerState>()

    /**
     * 去重键：用 svrId/msgId + 会话 + 时间 + 内容哈希 组复合键。
     */
    private val seenKeys = object : LinkedHashMap<String, Boolean>(SEEN_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > SEEN_CACHE
    }

    /**
     * 记录 AI 自己通过 MessageSender 发出的文本哈希，防止机主自指令模式误把自己刚发的回复当成新指令。
     */
    private val aiSentRecent = object : LinkedHashMap<Int, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean =
            size > 128
    }

    @Volatile
    private var appCtx: Context? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        L.i("reply engine started (with SessionManager & Agent tools)")
    }

    fun markAiOutbound(talker: String, text: String) {
        synchronized(aiSentRecent) {
            aiSentRecent[(talker + "|" + text.trim()).hashCode()] = System.currentTimeMillis()
        }
    }

    private fun isAiOutbound(talker: String, text: String): Boolean = synchronized(aiSentRecent) {
        val key = (talker + "|" + text.trim()).hashCode()
        val ts = aiSentRecent[key] ?: return false
        if (System.currentTimeMillis() - ts < 30_000L) {
            return true
        }
        aiSentRecent.remove(key)
        false
    }

    // ---------------------------------------------------------------- hook 入口

    /**
     * SQLiteDatabase.insertWithOnConflict("message", ..., values, ...) 回调入口。
     * 直接读取 ContentValues，零反射、零阻塞。
     */
    fun onMessageInsertedValues(values: ContentValues) {
        val ctx = appCtx ?: return

        val type = values.getAsInteger("type") ?: return
        if (type != DexMap.TYPE_TEXT) return

        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isEmpty()) return

        val rawContent = values.getAsString("content").orEmpty()
        if (rawContent.isEmpty()) return

        val isSend = values.getAsInteger("isSend") ?: 0
        if (isSend != 0) {
            // 自己发的消息：除非是机主显式输入的召唤指令（如 /ai、@AI、#重置、#记忆），否则一律忽略
            if (isAiOutbound(talker, rawContent)) return
            if (!isSelfCommandTrigger(rawContent)) return
        }

        val createTime = values.getAsLong("createTime") ?: 0L
        val nowMs = System.currentTimeMillis()
        // 微信 message 表 createTime 单位是毫秒（如 1760000000000L），兼容秒级时间戳
        val msgTimeMs = when {
            createTime <= 0L -> nowMs
            createTime < 10_000_000_000L -> createTime * 1000L
            else -> createTime
        }
        if (nowMs - msgTimeMs > STALE_MSG_SECONDS * 1000L) {
            return
        }

        val isSelfCmd = isSend != 0 && isSelfCommandTrigger(rawContent)

        // 如果本地已经有配置缓存，直接在微秒级内快速过滤非白名单会话（机主显式 /ai 指令除外）
        if (ConfigBridge.hasLoadedConfig()) {
            val fastCfg = ConfigBridge.get(ctx)
            if (!fastCfg.enabled) return
            if (!isSelfCmd && !inWhitelist(fastCfg.whitelist, talker)) return
        }

        val msgId = values.getAsLong("msgId") ?: 0L
        val svrId = values.getAsLong("msgSvrId") ?: 0L

        pool.execute {
            val cfg = ConfigBridge.ensureLoadedOnWorker(ctx)
            if (!cfg.enabled) return@execute
            if (!isSelfCmd && !inWhitelist(cfg.whitelist, talker)) return@execute
            processCandidate(ctx, cfg, talker, rawContent, msgId, svrId, msgTimeMs, isSelfCmd)
        }
    }

    private fun isSelfCommandTrigger(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("/ai ", ignoreCase = true) ||
            t.startsWith("@ai ", ignoreCase = true) ||
            t.startsWith("#ai ", ignoreCase = true) ||
            t == "#重置" || t == "#重置会话" || t == "#清除记忆" || t == "#记忆" || t == "#查看记忆"
    }

    private fun stripSelfCommandPrefix(raw: String): String {
        val t = raw.trim()
        for (p in listOf("/ai ", "@ai ", "#ai ", "/AI ", "@AI ", "#AI ")) {
            if (t.startsWith(p)) return t.substring(p.length).trim()
        }
        return t
    }

    // ---------------------------------------------------------------- 后台过滤与发送流程

    private fun processCandidate(
        ctx: Context,
        cfg: Config,
        talker: String,
        rawContent: String,
        msgId: Long,
        svrId: Long,
        msgTimeMs: Long,
        isSelfCmd: Boolean,
    ) {
        try {
            val isGroup = talker.endsWith("@chatroom")
            val (senderWxid, strippedContent) = if (isGroup && !isSelfCmd) {
                extractGroupSenderAndBody(rawContent)
            } else {
                null to (if (isSelfCmd) stripSelfCommandPrefix(rawContent) else rawContent)
            }

            val content = strippedContent.trim()
            if (content.isEmpty()) return

            if (looksLikeSystemOrMedia(content)) return
            if (isGroup && !isSelfCmd && cfg.groupOnlyWhenMentioned && !mentionsMe(content, cfg)) {
                L.i("group msg skipped (not mentioned): ${content.take(40)}")
                return
            }

            val dedupeKey = when {
                svrId != 0L -> "s$svrId"
                msgId != 0L -> "m$msgId"
                else -> "h$talker|$msgTimeMs|${content.hashCode()}"
            }
            if (!markSeen(dedupeKey)) {
                L.i("duplicate msg ignored ($dedupeKey)")
                return
            }

            // 1. 优先拦截内置会话/记忆管理指令（不受冷却时间限制）
            if (handleBuiltinCommand(ctx, cfg, talker, content)) {
                return
            }

            if (!isSelfCmd && !passRateLimit(cfg, talker)) return

            val talkerName = ConversationBridge.getDisplayName(talker)
            val senderName = if (!senderWxid.isNullOrBlank()) {
                ConversationBridge.getDisplayName(senderWxid)
            } else if (isSelfCmd) {
                "主人(机主)"
            } else {
                talkerName
            }

            L.i("trigger <- $talker ($senderName): ${content.take(60)}")

            val delay = if (isSelfCmd) {
                200L
            } else if (cfg.replyDelayMaxMs > cfg.replyDelayMinMs) {
                Random.nextLong(cfg.replyDelayMinMs.toLong(), cfg.replyDelayMaxMs.toLong() + 1)
            } else {
                cfg.replyDelayMinMs.toLong()
            }
            if (delay > 0) Thread.sleep(delay)

            // 2. 构建多轮上下文消息列表
            val cleanUserInput = stripMentionTag(content, cfg)
            val userMsgForModel = if (isGroup && !isSelfCmd) {
                "[$senderName]: $cleanUserInput"
            } else {
                cleanUserInput
            }

            val historyMessages = buildConversationContext(
                ctx = ctx,
                cfg = cfg,
                talker = talker,
                currentUserMessage = userMsgForModel,
            )

            // 3. 判定是否允许本轮使用 Agent 跨对话操控工具
            val allowTools = cfg.enableAgentTools && (isSelfCmd || isAuthorizedForAgent(cfg, talker, talkerName, senderWxid, senderName))

            // 4. 构建动态环境与长期记忆提示词
            val extraSystem = buildDynamicSystemContext(
                ctx = ctx,
                cfg = cfg,
                talker = talker,
                talkerName = talkerName,
                senderName = senderName,
                isGroup = isGroup,
                isSelfCmd = isSelfCmd,
                allowTools = allowTools,
            )

            val result = AiClient.completeWithContext(
                ctx = ctx,
                cfg = cfg,
                currentTalker = talker,
                historyMessages = historyMessages,
                extraSystem = extraSystem,
                allowAgentTools = allowTools,
            )

            val text = result.text
            if (text.isNullOrBlank()) {
                debugToast(cfg, "回复失败: ${result.error ?: "unknown"}")
                L.w("no reply for $talker: ${result.error}")
                return
            }

            val reply = sanitize(text, cfg.maxReplyChars)
            if (reply.isEmpty()) {
                L.w("sanitized reply empty for $talker")
                return
            }

            // 5. 先标记为 AI 外发消息，再发送，避免自回路
            markAiOutbound(talker, reply)
            val ok = MessageSender.sendText(talker, reply)
            if (ok) {
                recordReply(talker)
                if (cfg.enableMemory) {
                    SessionManager.appendMessage(ctx, talker, "user", userMsgForModel, msgTimeMs)
                    SessionManager.appendMessage(ctx, talker, "assistant", reply, System.currentTimeMillis())
                }
            }
            debugToast(cfg, if (ok) "已回复: ${reply.take(30)}" else "发送失败")
            L.i("reply -> $talker: ${reply.take(60)} (ok=$ok)")
        } catch (t: Throwable) {
            L.e("processCandidate failed for $talker", t)
        }
    }

    /**
     * 处理 #重置 / #清除记忆 / #记忆 等快捷指令。
     */
    private fun handleBuiltinCommand(
        ctx: Context,
        cfg: Config,
        talker: String,
        content: String,
    ): Boolean {
        val cmd = stripMentionTag(content, cfg).trim()
        when {
            cmd == "#重置" || cmd == "#重置会话" || cmd == "#清除记忆" || cmd.equals("#reset", ignoreCase = true) -> {
                val count = SessionManager.clearSession(ctx, talker)
                val reply = "已重置当前会话记忆（清理了 $count 条上下文记录），我们可以重新开始聊啦。"
                markAiOutbound(talker, reply)
                MessageSender.sendText(talker, reply)
                return true
            }

            cmd == "#记忆" || cmd == "#查看记忆" -> {
                val talkerName = ConversationBridge.getDisplayName(talker)
                val memories = SessionManager.getRelevantMemories(ctx, talker, talkerName, limit = 10)
                val history = SessionManager.getHistory(ctx, talker, cfg.maxHistoryTurns, cfg.sessionExpireMinutes)
                val reply = if (memories.isEmpty()) {
                    "当前会话共有 ${history.size} 条短期上下文，暂无永久事实记忆。"
                } else {
                    val summary = memories.take(5).joinToString("；") { it.fact }
                    "短期上下文 ${history.size} 条；永久记忆 ${memories.size} 条：$summary"
                }
                val safeReply = sanitize(reply, cfg.maxReplyChars)
                markAiOutbound(talker, safeReply)
                MessageSender.sendText(talker, safeReply)
                return true
            }
        }
        return false
    }

    /**
     * 组装发给 DeepSeek 的多轮对话列表：
     * 1. 若开启了记忆，优先从 SessionManager 读取多轮历史；
     * 2. 若 SessionManager 中该会话尚无记录，自动从微信底层数据库拉取最近 6 条真实聊天记录做冷启动预热；
     * 3. 末尾追加本次收到的最新 user 消息。
     */
    private fun buildConversationContext(
        ctx: Context,
        cfg: Config,
        talker: String,
        currentUserMessage: String,
    ): List<AiClient.ChatMessage> {
        if (!cfg.enableMemory || cfg.maxHistoryTurns <= 0) {
            return listOf(AiClient.ChatMessage(role = "user", content = currentUserMessage))
        }

        val list = ArrayList<AiClient.ChatMessage>()
        val savedHistory = SessionManager.getHistory(
            ctx = ctx,
            talker = talker,
            maxTurns = cfg.maxHistoryTurns,
            expireMinutes = cfg.sessionExpireMinutes,
        )

        if (savedHistory.isNotEmpty()) {
            list.addAll(savedHistory)
        } else {
            // 首次接管该会话：从微信数据库读取最近几条真实消息做预热（排除刚插进来的当前这条）
            val wxRecent = ConversationBridge.queryWechatMessages(talker, limit = 7)
            if (wxRecent.size > 1) {
                val prior = wxRecent.dropLast(1).takeLast(6)
                for (r in prior) {
                    if (r.type != DexMap.TYPE_TEXT) continue
                    val role = if (r.isSelf) "assistant" else "user"
                    val msgContent = if (!r.isSelf && talker.endsWith("@chatroom")) {
                        "[${r.senderName}]: ${r.content}"
                    } else {
                        r.content
                    }
                    list.add(AiClient.ChatMessage(role = role, content = msgContent))
                }
                while (list.isNotEmpty() && list.first().role != "user") {
                    list.removeAt(0)
                }
            }
        }

        list.add(AiClient.ChatMessage(role = "user", content = currentUserMessage))
        return list
    }

    /**
     * 组装动态环境感知（时间、当前会话对象、已保存的永久记忆、Agent 工具使用守则）。
     */
    private fun buildDynamicSystemContext(
        ctx: Context,
        cfg: Config,
        talker: String,
        talkerName: String,
        senderName: String,
        isGroup: Boolean,
        isSelfCmd: Boolean,
        allowTools: Boolean,
    ): String = buildString {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.CHINA).format(Date())
        append("【当前环境感知】\n")
        append("- 当前时间：$nowStr\n")
        if (isSelfCmd) {
            append("- 当前场景：主人（微信机主本人）正在会话【$talkerName ($talker)】中直接向你下达指令。\n")
        } else if (isGroup) {
            append("- 当前场景：微信群聊【$talkerName ($talker)】，正在跟你说话的群成员是【$senderName】。回复请简短自然。\n")
        } else {
            append("- 当前场景：与微信好友【$talkerName ($talker)】私聊中。\n")
        }

        if (cfg.enableMemory) {
            val memories = SessionManager.getRelevantMemories(ctx, talker, talkerName, limit = 20)
            if (memories.isNotEmpty()) {
                append("\n【长期记忆档案（请在对话中自然运用，不要生硬背诵）】\n")
                for (m in memories) {
                    val tag = if (m.scope == "global") "全局" else talkerName
                    append("- [$tag] ${m.fact}\n")
                }
            }
        }

        if (allowTools) {
            append("\n【微信操控与记忆工具指南】\n")
            append("- 当对方让你帮忙给别人或别的群发消息/带话时，先用 lookup_contact 或直接调用 send_wechat_message(target, message) 发送，发送成功后向当前对话复命。\n")
            append("- 当对方询问其他联系人或群聊说了什么时，调用 get_chat_history(target) 查阅后再回答。\n")
            append("- 当对话中出现值得长期记住的重要事实（如对方的名字、生日、喜好、约定、重要关系）时，可调用 save_memory 存入永久记忆库。\n")
        }
    }

    private fun isAuthorizedForAgent(
        cfg: Config,
        talker: String,
        talkerName: String,
        senderWxid: String?,
        senderName: String,
    ): Boolean {
        val rawMasters = cfg.masterTalkers.trim()
        if (rawMasters.isEmpty()) return true // 未限制主人列表时，白名单内全部允许

        val masters = rawMasters.split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (masters.isEmpty()) return true

        return masters.any { m ->
            m.equals(talker, ignoreCase = true) ||
                m.equals(talkerName, ignoreCase = true) ||
                (!senderWxid.isNullOrBlank() && m.equals(senderWxid, ignoreCase = true)) ||
                m.equals(senderName, ignoreCase = true)
        }
    }

    private fun stripMentionTag(content: String, cfg: Config): String {
        val nick = cfg.myNickname.trim()
        var s = content
        if (nick.isNotEmpty()) {
            s = s.replace("@$nick\u2005", "")
                .replace("@$nick ", "")
                .replace("@$nick", "")
        }
        return s.trim().ifEmpty { content }
    }

    private fun sanitize(text: String, maxChars: Int): String {
        var s = text.trim()
        val quotePairs = listOf("「" to "」", "\"" to "\"", "'" to "'", "“" to "”", "‘" to "’")
        for ((open, close) in quotePairs) {
            if (s.length > 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(1, s.length - 1).trim()
                break
            }
        }
        s = s.replace("\r\n", " ").replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()
        if (maxChars > 0 && s.length > maxChars) s = s.substring(0, maxChars)
        return s
    }

    // ---------------------------------------------------------------- 过滤工具

    private fun inWhitelist(whitelist: List<String>, talker: String): Boolean {
        if (whitelist.isEmpty()) return false
        return whitelist.any { it == talker }
    }

    private fun mentionsMe(content: String, cfg: Config): Boolean {
        if (!content.contains("@")) return false
        val nick = cfg.myNickname.trim()
        if (nick.isEmpty()) return true
        return content.contains("@$nick")
    }

    /** 群消息 content 形如 "wxid_xxx:\n真实内容"，提取 (senderWxid, 真实内容) */
    private fun extractGroupSenderAndBody(content: String): Pair<String?, String> {
        val idx = content.indexOf(":\n")
        if (idx in 1..80) {
            val head = content.substring(0, idx)
            if (head.none { it == ' ' || it == '\n' || it == '：' }) {
                return head to content.substring(idx + 2)
            }
        }
        return null to content
    }

    private fun looksLikeSystemOrMedia(content: String): Boolean {
        if (content.startsWith("<")) return true // XML 消息（引用/链接/红包等）
        if (content.contains("撤回了一条消息")) return true
        if (content.contains("拍了拍")) return true
        return false
    }

    private fun passRateLimit(cfg: Config, talker: String): Boolean {
        val state = states.computeIfAbsent(talker) { TalkerState() }
        val now = System.currentTimeMillis()

        synchronized(state) {
            val cooldownMs = cfg.cooldownSeconds.coerceAtLeast(0) * 1000L
            if (now - state.lastTriggerAt < cooldownMs) {
                L.i("cooldown hit for $talker (${(now - state.lastTriggerAt) / 1000}s)")
                return false
            }
            while (state.recentReplies.isNotEmpty() && now - state.recentReplies.first() > 3_600_000L) {
                state.recentReplies.removeFirst()
            }
            if (cfg.maxPerHour in 1..Int.MAX_VALUE && state.recentReplies.size >= cfg.maxPerHour) {
                L.w("hourly cap reached for $talker (${state.recentReplies.size})")
                return false
            }
            state.lastTriggerAt = now
            state.recentReplies.addLast(now)
            return true
        }
    }

    private fun recordReply(talker: String) {
        states[talker]?.let { st ->
            synchronized(st) { st.lastTriggerAt = System.currentTimeMillis() }
        }
    }

    private fun markSeen(key: String): Boolean = synchronized(seenKeys) {
        if (seenKeys.containsKey(key)) {
            false
        } else {
            seenKeys[key] = true
            true
        }
    }

    private fun debugToast(cfg: Config, text: String) {
        if (!cfg.debug) return
        mainHandler.post {
            try {
                Toast.makeText(appCtx, text, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
