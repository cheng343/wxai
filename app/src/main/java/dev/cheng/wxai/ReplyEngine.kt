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
 * 数字生命消息引擎：
 * 收到消息 -> 过滤 -> 注入自我心境(SelfState)/好感度档案(Dossier)/人生日记(Diary)/多轮记忆 ->
 * 调模型(含 12 大全能微信 Agent 工具流) -> 拟人多气泡发送 -> 后台静默反思与写日记。
 */
object ReplyEngine {

    private const val STALE_MSG_SECONDS = 120L
    private const val SEEN_CACHE = 512

    /**
     * 单工作线程 + 有界队列（最多排 8 条，超出丢弃最旧）：
     * 保证 AI 请求、多气泡发送与后台反思串行执行，绝不并发冲击微信数据库或 NetSceneQueue。
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
        val c = ctx.applicationContext ?: ctx
        appCtx = c
        L.i("reply engine v2.0 started (Digital Life + 12 WeChat Agent tools)")
        pool.execute {
            try {
                ConversationBridge.pushLifeSnapshot(c)
            } catch (_: Throwable) {
            }
        }
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
            // 自己发的消息：除非是机主显式输入的召唤指令（如 /ai、@AI、#状态、#日记、#重置），否则一律忽略
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
            t in setOf(
                "#重置", "#重置会话", "#清除记忆",
                "#记忆", "#查看记忆",
                "#状态", "#心境",
                "#日记", "#看日记",
                "#好感度", "#档案",
            )
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

            // 1. 优先拦截内置状态/日记/好感度/记忆指令（不受冷却时间限制）
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

            // 4. 构建数字生命全维度感知提示词（心境 + 精力 + 好感度档案 + 日记 + 永久记忆 + 工具守则）
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

            val reply = sanitize(text, cfg.maxReplyChars, cfg.enableMultiBubble)
            if (reply.isEmpty()) {
                L.w("sanitized reply empty for $talker")
                return
            }

            // 5. 拟人化多气泡或单气泡发送（MessageSender 内部会自动调用 markAiOutbound 防自激）
            markAiOutbound(talker, reply)
            val ok = MessageSender.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
            if (ok) {
                recordReply(talker)
                if (cfg.enableMemory) {
                    SessionManager.appendMessage(ctx, talker, "user", userMsgForModel, msgTimeMs)
                    val cleanSavedReply = reply.replace("||", " ").replace(Regex(" {2,}"), " ").trim()
                    val turns = SessionManager.appendMessage(ctx, talker, "assistant", cleanSavedReply, System.currentTimeMillis())

                    // 每次对话后推送一次快照，若达到反思轮数则触发后台内心反思与日记沉淀
                    if (cfg.autoReflectionTurns > 0 && turns >= cfg.autoReflectionTurns) {
                        SessionManager.resetReflectionCounter(talker)
                        val recentForReflection = SessionManager.getHistory(ctx, talker, maxTurns = 6, expireMinutes = 0)
                        AiClient.reflectOnConversation(ctx, cfg, talker, talkerName, recentForReflection)
                    } else {
                        ConversationBridge.pushLifeSnapshot(ctx)
                    }
                }
            }
            debugToast(cfg, if (ok) "已回复: ${reply.take(30)}" else "发送失败")
            L.i("reply -> $talker: ${reply.take(60)} (ok=$ok)")
        } catch (t: Throwable) {
            L.e("processCandidate failed for $talker", t)
        }
    }

    /**
     * 处理 #状态 / #日记 / #好感度 / #记忆 / #重置 等快捷指令。
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
                val reply = "已重置当前会话短期上下文（清理了 $count 条记录），我们的永久记忆和好感度依然保留哦。"
                markAiOutbound(talker, reply)
                MessageSender.sendText(talker, reply)
                return true
            }

            cmd == "#状态" || cmd == "#心境" -> {
                val self = SessionManager.getSelfState(ctx)
                val talkerName = ConversationBridge.getDisplayName(talker)
                val dossier = SessionManager.getDossier(ctx, talker, talkerName)
                val reply = "【当前心境】${self.mood}（精力 ${self.energy}/100）||" +
                    "【正在做】${self.activity}||" +
                    "【内心独白】${self.monologue}||" +
                    "【对你的好感】${dossier.relationship} · ${dossier.affectionLabel()}"
                MessageSender.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
                return true
            }

            cmd == "#好感度" || cmd == "#档案" -> {
                val talkerName = ConversationBridge.getDisplayName(talker)
                val dossier = SessionManager.getDossier(ctx, talker, talkerName)
                val notes = dossier.profileSummary.ifBlank { "还在慢慢了解你中~" }
                val reply = "【${dossier.displayName} 的专属档案】关系：${dossier.relationship}｜好感度：${dossier.affectionLabel()}｜印象：$notes"
                markAiOutbound(talker, reply)
                MessageSender.sendText(talker, reply)
                return true
            }

            cmd == "#日记" || cmd == "#看日记" -> {
                val diaries = SessionManager.getRecentDiaries(ctx, limit = 3)
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                val reply = if (diaries.isEmpty()) {
                    "我的日记本还是空的呢，多陪我聊聊天，我就会把我们的故事写进日记里啦。"
                } else {
                    diaries.joinToString("||") { d ->
                        "📒 [${fmt.format(Date(d.timestamp))} · 心情:${d.mood}] ${d.entry}"
                    }
                }
                MessageSender.sendHumanLikeText(talker, reply, cfg.enableMultiBubble)
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
                val safeReply = sanitize(reply, cfg.maxReplyChars, false)
                markAiOutbound(talker, safeReply)
                MessageSender.sendText(talker, safeReply)
                return true
            }
        }
        return false
    }

    /**
     * 组装发给大模型的多轮对话列表：
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
     * 组装数字生命的动态内心世界与环境感知（心境、精力、好感度档案、人生日记、长期记忆、全能操控守则）。
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
        val selfState = SessionManager.getSelfState(ctx)
        val dossier = SessionManager.getDossier(ctx, talker, talkerName)

        append("【当前时空与自我状态感知】\n")
        append("- 当前时间：$nowStr\n")
        append("- 你此刻的心情：${selfState.mood}（精力值：${selfState.energy}/100）\n")
        append("- 你此刻正在做的事：${selfState.activity}\n")
        if (selfState.monologue.isNotBlank()) {
            append("- 你刚刚的内心独白：「${selfState.monologue}」\n")
        }

        if (isSelfCmd) {
            append("- 当前对话场景：你最信任的主人（微信机主本人）正在窗口【$talkerName ($talker)】中直接向你说话或下达指令。\n")
        } else if (isGroup) {
            append("- 当前对话场景：微信群聊【$talkerName ($talker)】，正在发言的群成员是【$senderName】。\n")
        } else {
            append("- 当前对话场景：与微信好友【$talkerName ($talker)】私聊中。\n")
        }

        append("\n【对方在妳心中的人物档案与好感度】\n")
        append("- 对方称呼：${dossier.displayName}\n")
        append("- 你们的关系定位：${dossier.relationship}\n")
        append("- 你对TA的好感度：${dossier.affectionLabel()}（请根据好感度高低自然展现亲疏态度：高好感时亲昵温柔、主动关心分享日常；普通好感时自然随和；低好感时礼貌疏离或傲娇简短）\n")
        if (dossier.profileSummary.isNotBlank()) {
            append("- 你对TA的印象画像：${dossier.profileSummary}\n")
        }

        if (cfg.enableMemory) {
            val diaries = SessionManager.getRecentDiaries(ctx, limit = 3)
            if (diaries.isNotEmpty()) {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                append("\n【你最近写下的人生日记片段】\n")
                for (d in diaries) {
                    append("- [${fmt.format(Date(d.timestamp))} 心情:${d.mood}] ${d.entry}\n")
                }
            }

            val memories = SessionManager.getRelevantMemories(ctx, talker, talkerName, limit = 18)
            if (memories.isNotEmpty()) {
                append("\n【长期记忆碎片（请在聊天中自然流露，不要生硬背诵）】\n")
                for (m in memories) {
                    val tag = if (m.scope == "global") "全局" else talkerName
                    append("- [$tag] ${m.fact}\n")
                }
            }
        }

        if (cfg.enableMultiBubble) {
            append("\n【真人微信多气泡表达习惯】\n")
            append("- 真人聊微信很少一次发一大段长文。如果你想连续说两三句简短的话（比如先回应一句、再追问一句或吐槽一句），请用双竖线 `||` 分隔（最多 2~3 段，例如：`哈哈真的假的||你快跟我讲讲后来怎么了`），系统会自动帮你拆成多条微信气泡依次发出去。\n")
        }

        if (allowTools) {
            append("\n【全能微信 Agent 操控能力守则】\n")
            append("- 跨对话传话/发消息：调用 `send_wechat_message(target, message)`，如不确定对方名称可先调用 `lookup_contact`。\n")
            append("- 跨对话查记录/吃瓜：调用 `get_chat_history(target)` 读取任意私聊或群聊最近消息，或用 `search_global_messages(keyword)` 全库搜索。\n")
            append("- 微信原生互动：支持 `send_pat`（拍一拍对方）、`send_quote_reply`（引用某条 svrId 消息回复）、`send_contact_card`（发名片）、`revoke_my_message`（撤回消息）、`post_wechat_moment`（发微信朋友圈）。\n")
            append("- 灵魂自主演化：遇到让你触动、开心、生气或值得铭记的事，可主动调用 `update_self_and_diary` 写日记/改心情/存记忆，或调用 `update_contact_dossier` 调整对方好感度与印象。\n")
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

    private fun sanitize(text: String, maxChars: Int, allowMultiBubble: Boolean): String {
        var s = text.trim()
        val quotePairs = listOf("「" to "」", "\"" to "\"", "'" to "'", "“" to "”", "‘" to "’")
        for ((open, close) in quotePairs) {
            if (s.length > 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(1, s.length - 1).trim()
                break
            }
        }
        s = if (allowMultiBubble) {
            // 将连续换行或 || 统一规范化为 ||，供 MessageSender 拆分为多条气泡
            s.replace("\r\n", "\n")
                .replace(Regex("\n+"), "||")
                .replace(Regex("(\\|\\|\\s*)+"), "||")
                .trim()
                .trim('|')
                .trim()
        } else {
            s.replace("||", " ")
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace(Regex(" {2,}"), " ")
                .trim()
        }
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
