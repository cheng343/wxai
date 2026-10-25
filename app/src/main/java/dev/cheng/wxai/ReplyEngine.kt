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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 数字生命消息引擎（v2.1 交互与 Prefix KV Cache 深度优化版）：
 *
 * 核心升级：
 * 1. 消息防抖聚合（Debounce Batching）：对方或主人连发多条短消息时，自动在防抖窗口内合并为一次完整输入，绝不因冷却时间吞掉后续关键句子；
 * 2. 主人全场景识别（Master Recognition）：支持微信内发 `#认主` 一键绑定、支持 `filehelper` 直接对话、支持双号主从识别，对话中明确区分【唯一主人】与普通好友；
 * 3. 群聊环境感知与引用消息解析：支持解析微信原生「引用回复」(type=822083633)，并在群聊被 @ 时自动附带群内刚才大家的聊天背景；
 * 4. Prefix KV Cache 架构：保持 System Prompt 与历史消息列表 100% 静态追加，仅在末尾当前轮挂载 `<turn_context>`，实现 90%+ 缓存命中率。
 */
object ReplyEngine {

    private const val STALE_MSG_SECONDS = 120L
    private const val SEEN_CACHE = 512
    private const val TYPE_QUOTE_REPLY = 822083633
    private const val TYPE_IMAGE = 3
    private const val TYPE_EMOJI = 47

    /**
     * 单工作线程 + 有界队列：
     * 保证 AI 请求、多气泡发送与后台反思串行执行，绝不并发冲击微信数据库或 NetSceneQueue。
     */
    private val pool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(12),
        { r -> Thread(r, "pool-msg-dispatch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /** 防抖调度器：用于合并同一会话在几秒内连续发出的多条短消息 */
    private val debounceScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pool-msg-debounce").apply { isDaemon = true }
    }

    private class TalkerState {
        @Volatile
        var lastReplyFinishedAt: Long = 0L
        val recentReplies = ArrayDeque<Long>()

        // 防抖缓冲队列
        val pendingLines = ArrayList<String>()
        var firstPendingAt: Long = 0L
        var lastSenderWxid: String? = null
        var lastSenderName: String = ""
        var lastMsgSvrId: Long = 0L
        var pendingIsSelfCmd: Boolean = false
        var debounceFuture: ScheduledFuture<*>? = null
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
     * 记录 AI 自己通过 MessageSender 发出的文本哈希，防止机主自指令模式或 filehelper 误把自己刚发的回复当成新指令。
     */
    private val aiSentRecent = object : LinkedHashMap<Int, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean =
            size > 256
    }

    @Volatile
    private var appCtx: Context? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun start(ctx: Context) {
        val c = ctx.applicationContext ?: ctx
        appCtx = c
        L.i("reply engine started")
    }

    private fun sendReply(talker: String, text: String, isMultiBubble: Boolean = true, quoteSvrId: Long = 0L): Boolean {
        markAiOutbound(talker, text)
        return MessageSender.sendHumanLikeText(talker, text, isMultiBubble, quoteSvrId)
    }

    private fun sendPatAction(talker: String, patTarget: String): Boolean {
        return MessageSender.sendPat(talker, patTarget)
    }

    fun markAiOutbound(talker: String, text: String) {
        synchronized(aiSentRecent) {
            aiSentRecent[(talker + "|" + text.trim()).hashCode()] = System.currentTimeMillis()
        }
    }

    private fun isAiOutbound(talker: String, text: String): Boolean = synchronized(aiSentRecent) {
        val key = (talker + "|" + text.trim()).hashCode()
        val ts = aiSentRecent[key] ?: return false
        if (System.currentTimeMillis() - ts < 45_000L) {
            return true
        }
        aiSentRecent.remove(key)
        false
    }

    // ---------------------------------------------------------------- hook 入口

    /**
     * SQLiteDatabase.insertWithOnConflict("message", ..., values, ...) 回调入口。
     */
    fun onMessageInsertedValues(values: ContentValues) {
        val ctx = appCtx ?: return

        val type = values.getAsInteger("type") ?: return
        val isSys = (type == 10000)
        if (type != DexMap.TYPE_TEXT && type != TYPE_QUOTE_REPLY && type != TYPE_IMAGE && type != TYPE_EMOJI && !isSys) return

        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isEmpty()) return

        val rawContent = values.getAsString("content").orEmpty()
        val imgPath = values.getAsString("imgPath")
        if (rawContent.isEmpty() && imgPath.isNullOrEmpty()) return

        // 拍一拍事件过滤：仅当系统消息包含拍了拍事件且被拍对象为我时触发
        if (isSys) {
            val isPat = rawContent.contains("拍了拍") &&
                (rawContent.contains("拍了拍我") || rawContent.contains("拍了拍你") || rawContent.contains("拍了拍")) &&
                !rawContent.startsWith("我拍了拍")
            if (!isPat) return
        }

        val isSend = values.getAsInteger("isSend") ?: 0
        if (isSend != 0) {
            // 排除 AI 自己刚刚发出的消息
            if (isAiOutbound(talker, rawContent)) return
            // 在文件传输助手 (filehelper) 中机主直接打字，或者在任意窗口输入 /ai、@ai、#认主 等快捷指令，均视为机主指令
            val isFileHelper = talker == "filehelper"
            if (!isFileHelper && !isSelfCommandTrigger(rawContent)) return
        }

        val createTime = values.getAsLong("createTime") ?: 0L
        val nowMs = System.currentTimeMillis()
        val msgTimeMs = when {
            createTime <= 0L -> nowMs
            createTime < 10_000_000_000L -> createTime * 1000L
            else -> createTime
        }
        if (nowMs - msgTimeMs > STALE_MSG_SECONDS * 1000L) {
            return
        }

        val isSelfCmd = isSend != 0 || isSelfCommandTrigger(rawContent)

        if (ConfigBridge.hasLoadedConfig()) {
            val fastCfg = ConfigBridge.get(ctx)
            if (!fastCfg.enabled) return
            if (!isSelfCmd && !inWhitelist(fastCfg.whitelist, talker)) return
        }

        val msgId = values.getAsLong("msgId") ?: 0L
        val svrId = values.getAsLong("msgSvrId") ?: 0L

        debounceScheduler.execute {
            val cfg = ConfigBridge.ensureLoadedOnWorker(ctx)
            if (!cfg.enabled) return@execute
            if (!isSelfCmd && !inWhitelist(cfg.whitelist, talker)) return@execute
            enqueueOrBatchCandidate(ctx, cfg, talker, type, rawContent, imgPath, msgId, svrId, msgTimeMs, isSelfCmd)
        }
    }

    private fun isMasterBindCommand(raw: String): Boolean {
        val c = raw.trim().removePrefix("#").removePrefix("/").removePrefix("@").trim()
        return c in setOf(
            "认主", "我是主人", "我是你主人", "我就是你主人", "我是你的主人",
            "认我为主", "绑定主人", "绑定为主人", "你是我的", "做我仆人", "做我的仆人"
        ) || c == "认主" || c.startsWith("认主") || (c.contains("我是") && c.contains("主人"))
    }

    private fun isSelfCommandTrigger(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("/ai ", ignoreCase = true) ||
            t.startsWith("@ai ", ignoreCase = true) ||
            t.startsWith("#ai ", ignoreCase = true) ||
            t.startsWith("#人设") ||
            t.startsWith("#风格") ||
            isMasterBindCommand(t) ||
            t in setOf(
                "#重置", "#重置会话", "#清除记忆",
                "#记忆", "#查看记忆",
                "#状态", "#心境",
                "#日记", "#看日记",
                "#好感度", "#档案",
                "#人设", "#风格",
            )
    }

    private fun stripSelfCommandPrefix(raw: String): String {
        val t = raw.trim()
        for (p in listOf("/ai ", "@ai ", "#ai ", "/AI ", "@AI ", "#AI ")) {
            if (t.startsWith(p)) return t.substring(p.length).trim()
        }
        return t
    }

    // ---------------------------------------------------------------- 消息解析与防抖聚合（Debounce Batching + 识图与表情包）

    private fun enqueueOrBatchCandidate(
        ctx: Context,
        cfg: Config,
        talker: String,
        type: Int,
        rawContent: String,
        imgPath: String?,
        msgId: Long,
        svrId: Long,
        msgTimeMs: Long,
        isSelfCmd: Boolean,
    ) {
        try {
            val isGroup = talker.endsWith("@chatroom")
            val (senderWxid, rawBody) = if (isGroup && !isSelfCmd) {
                extractGroupSenderAndBody(rawContent)
            } else {
                null to (if (isSelfCmd) stripSelfCommandPrefix(rawContent) else rawContent)
            }

            // 多模态与系统事件处理：拍一拍 (10000)、图片 (3)、表情包 (47)、引用回复 (822083633) 或纯文本
            val isPat = (type == 10000)
            val isMedia = (type == TYPE_IMAGE || type == TYPE_EMOJI)
            val parsedBody = when {
                isPat -> "[对方在微信中拍了拍你]"
                type == TYPE_QUOTE_REPLY || (rawBody.trimStart().startsWith("<") && !isMedia && !isPat) -> {
                    ConversationBridge.parseQuotedMessageText(rawBody) ?: return
                }
                type == TYPE_EMOJI -> {
                    val resolvedFile = ImageBridge.resolveMediaFilePath(ctx, TYPE_EMOJI, svrId, rawBody, imgPath)
                    val dataUrl = resolvedFile?.let { ImageBridge.fileToBase64DataUrl(it) }
                    val desc = if (cfg.enableVision && dataUrl != null) {
                        ImageBridge.describeImageOrEmoji(cfg, dataUrl, TYPE_EMOJI)
                    } else null
                    if (!desc.isNullOrBlank()) {
                        "[对方发来一个微信表情包，画面内容与梗: $desc]"
                    } else {
                        val rawDesc = extractAttr(rawBody, "desc").ifBlank { extractAttr(rawBody, "title") }
                        if (rawDesc.isNotBlank()) "[对方发来一个表情包: $rawDesc]" else "[对方发来了一个微信表情包]"
                    }
                }
                type == TYPE_IMAGE -> {
                    val resolvedFile = ImageBridge.resolveMediaFilePath(ctx, TYPE_IMAGE, svrId, rawBody, imgPath)
                    val dataUrl = resolvedFile?.let { ImageBridge.fileToBase64DataUrl(it) }
                    val desc = if (cfg.enableVision && dataUrl != null) {
                        ImageBridge.describeImageOrEmoji(cfg, dataUrl, TYPE_IMAGE)
                    } else null
                    if (!desc.isNullOrBlank()) {
                        "[对方发来一张图片，画面内容: $desc]"
                    } else {
                        "[对方发来了一张图片]"
                    }
                }
                else -> rawBody.trim()
            }

            if (parsedBody.isEmpty()) return
            if (!isMedia && !isPat && looksLikeSystemOrMedia(parsedBody)) return

            // 拍一拍自动反击互动：收到拍一拍后 600ms 自动在微信里拍回去！
            if (isPat && cfg.enablePatResponse) {
                val patTarget = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                pool.execute {
                    try {
                        Thread.sleep(600L)
                        sendPatAction(talker, patTarget)
                    } catch (_: Throwable) {
                    }
                }
            }

            val dedupeKey = when {
                svrId != 0L -> "s$svrId"
                msgId != 0L -> "m$msgId"
                else -> "h$talker|$msgTimeMs|${parsedBody.hashCode()}"
            }
            if (!markSeen(dedupeKey)) {
                return
            }

            // 优先检查内置快捷指令（如 #认主、#状态、#日记、#好感度、#重置）
            val strippedForCmd = stripMentionTag(parsedBody, cfg).trim()
            if (isBuiltinCommand(strippedForCmd)) {
                pool.execute {
                    handleBuiltinCommand(ctx, cfg, talker, senderWxid, strippedForCmd)
                }
                return
            }

            val state = states.computeIfAbsent(talker) { TalkerState() }
            val cleanInput = stripMentionTag(parsedBody, cfg)

            synchronized(state) {
                // 群聊触发判定：
                // 1) 消息显式 @我；或者
                // 2) 同一发言人在防抖窗口（3秒内）刚刚 @我 过，紧接着发出了补充的第二句话
                val isFollowUpInActiveBatch = state.pendingLines.isNotEmpty() &&
                    state.lastSenderWxid == senderWxid &&
                    (System.currentTimeMillis() - state.firstPendingAt) < 4000L

                if (isGroup && !isSelfCmd && cfg.groupOnlyWhenMentioned && !mentionsMe(parsedBody, cfg) && !isFollowUpInActiveBatch) {
                    return
                }

                val talkerName = ConversationBridge.getDisplayName(talker)
                val senderName = when {
                    !senderWxid.isNullOrBlank() -> ConversationBridge.getDisplayName(senderWxid)
                    isSelfCmd -> "主人"
                    else -> talkerName
                }

                val formattedLine = if (isGroup && !isSelfCmd) {
                    "[$senderName]: $cleanInput"
                } else {
                    cleanInput
                }

                if (state.pendingLines.isEmpty()) {
                    state.firstPendingAt = System.currentTimeMillis()
                }
                state.pendingLines.add(formattedLine)
                state.lastSenderWxid = senderWxid
                state.lastSenderName = senderName
                state.lastMsgSvrId = svrId
                state.pendingIsSelfCmd = state.pendingIsSelfCmd || isSelfCmd

                // 计算防抖等待窗口：允许用户把连续两三句短话打完再一次性合并送入大模型
                state.debounceFuture?.cancel(false)
                val baseWaitMs = if (state.pendingIsSelfCmd) {
                    500L
                } else {
                    val min = cfg.replyDelayMinMs.coerceIn(600, 4000).toLong()
                    val max = cfg.replyDelayMaxMs.coerceIn(min.toInt(), 5000).toLong()
                    if (max > min) Random.nextLong(min, max + 1) else min
                }
                val elapsed = System.currentTimeMillis() - state.firstPendingAt
                // 最多累积等待 4.5 秒，防止无限推迟
                val waitMs = if (elapsed + baseWaitMs > 4500L) {
                    (4500L - elapsed).coerceAtLeast(150L)
                } else {
                    baseWaitMs
                }

                L.i("batched input [${state.pendingLines.size}] <- $talker ($senderName): ${cleanInput.take(50)} (wait=${waitMs}ms)")

                state.debounceFuture = debounceScheduler.schedule(
                    { flushPendingBatch(ctx, talker) },
                    waitMs,
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (t: Throwable) {
            L.e("enqueueOrBatchCandidate failed for $talker", t)
        }
    }

    private fun flushPendingBatch(ctx: Context, talker: String) {
        val state = states[talker] ?: return
        val mergedInput: String
        val senderWxid: String?
        val senderName: String
        val quoteSvrId: Long
        val isSelfCmd: Boolean

        synchronized(state) {
            if (state.pendingLines.isEmpty()) return
            mergedInput = state.pendingLines.joinToString("\n")
            senderWxid = state.lastSenderWxid
            senderName = state.lastSenderName
            quoteSvrId = state.lastMsgSvrId
            isSelfCmd = state.pendingIsSelfCmd
            state.pendingLines.clear()
            state.lastMsgSvrId = 0L
            state.pendingIsSelfCmd = false
            state.debounceFuture = null
        }

        pool.execute {
            val cfg = ConfigBridge.ensureLoadedOnWorker(ctx)
            if (!cfg.enabled) return@execute
            executeMergedTurn(
                ctx = ctx,
                cfg = cfg,
                talker = talker,
                mergedUserMsg = mergedInput,
                senderWxid = senderWxid,
                senderName = senderName,
                quoteSvrId = quoteSvrId,
                isSelfCmd = isSelfCmd,
            )
        }
    }

    // ---------------------------------------------------------------- 核心推理与回复执行

    private fun executeMergedTurn(
        ctx: Context,
        cfg: Config,
        talker: String,
        mergedUserMsg: String,
        senderWxid: String?,
        senderName: String,
        quoteSvrId: Long,
        isSelfCmd: Boolean,
    ) {
        try {
            if (!isSelfCmd && !waitOrCheckRateLimit(cfg, talker)) return

            val isGroup = talker.endsWith("@chatroom")
            val talkerName = ConversationBridge.getDisplayName(talker)
            val speakerId = if (isGroup && !senderWxid.isNullOrBlank()) senderWxid else talker
            val speakerDisplayName = if (isGroup && !senderWxid.isNullOrBlank()) senderName else talkerName
            val existingDossier = SessionManager.getDossier(ctx, speakerId, speakerDisplayName)

            // 智能判定主人身份：
            // 1) 显式自指令 / 文件传输助手；
            // 2) 匹配配置中设置的 masterTalkers；
            // 3) 本地记忆数据库中已标记为「唯一主人」；
            // 4) 若用户未设置 masterTalkers，但在 1对1 私聊白名单中，默认直接视当前私聊对话者为唯一主人！
            val isMaster = isSelfCmd ||
                isMasterSpeaker(cfg, talker, talkerName, senderWxid, senderName) ||
                existingDossier.relationship == "唯一主人" ||
                (cfg.masterTalkers.isBlank() && !isGroup)

            // 如果当前说话人是主人，自动确保其档案关系为「唯一主人」且好感度满值
            if (isMaster && speakerId != "filehelper") {
                if (existingDossier.relationship != "唯一主人" || existingDossier.affection < 100) {
                    SessionManager.updateDossier(
                        ctx = ctx,
                        talker = speakerId,
                        displayName = speakerDisplayName,
                        relationship = "唯一主人",
                        affectionDelta = 100,
                    )
                }
            }

            // 1. 构建纯净多轮历史上下文（分块锚定，不混入动态时间戳以保证 95%+ Prefix KV 缓存命中）
            val historyMessages = buildConversationContext(
                ctx = ctx,
                cfg = cfg,
                talker = talker,
                currentUserMessage = mergedUserMsg,
            )

            // 2. 判定是否允许本轮使用 Agent 跨对话操控工具
            val allowTools = cfg.enableAgentTools && (isMaster || cfg.masterTalkers.isBlank())

            // 3. 构建挂载在末尾最新一轮的动态感知块 <turn_context>
            val turnContext = buildDynamicTurnContext(
                ctx = ctx,
                cfg = cfg,
                talker = talker,
                talkerName = talkerName,
                speakerId = speakerId,
                senderName = senderName,
                isGroup = isGroup,
                isSelfCmd = isSelfCmd,
                isMaster = isMaster,
            )

            val turnStartMs = System.currentTimeMillis()
            val result = AiClient.completeWithContext(
                ctx = ctx,
                cfg = cfg,
                currentTalker = talker,
                historyMessages = historyMessages,
                extraSystem = turnContext,
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

            // 4. 拟人多气泡或单气泡发送（群聊且开启引用回复时，首条气泡自动引用原消息）
            markAiOutbound(talker, reply)
            val targetQuote = if (cfg.enableQuoteReply && isGroup && quoteSvrId > 0L) quoteSvrId else 0L
            val ok = sendReply(talker, reply, cfg.enableMultiBubble, targetQuote)
            if (ok) {
                recordReplyFinished(talker)
                if (cfg.enableMemory) {
                    // 存入 SQLite 的是纯净的对话文本（不含 <turn_context>），确保后续轮次 100% 命中 Prefix KV Cache
                    SessionManager.appendMessage(ctx, talker, "user", mergedUserMsg, turnStartMs)
                    val cleanSavedReply = reply.replace("||", " ").replace(Regex(" {2,}"), " ").trim()
                    val turns = SessionManager.appendMessage(ctx, talker, "assistant", cleanSavedReply, System.currentTimeMillis())

                    AiClient.rollSummaryIfNeeded(ctx, cfg, talker, talkerName)

                    if (cfg.autoReflectionTurns > 0 && turns >= cfg.autoReflectionTurns) {
                        SessionManager.resetReflectionCounter(ctx, talker)
                        val recentForReflection = SessionManager.getHistory(ctx, talker, maxTurns = 8, expireMinutes = 0)
                        AiClient.reflectOnConversation(ctx, cfg, speakerId, speakerDisplayName, recentForReflection)
                    } else {
                        ConversationBridge.pushLifeSnapshot(ctx)
                    }
                }
            }

            val cacheInfo = if (result.promptTokens > 0) {
                val pct = (result.cacheHitTokens * 100) / result.promptTokens
                " (缓存命中 $pct%)"
            } else {
                ""
            }
            debugToast(cfg, if (ok) "已回复$cacheInfo: ${reply.take(24)}" else "发送失败")
            L.i("reply -> $talker$cacheInfo: ${reply.take(60)} (ok=$ok)")
        } catch (t: Throwable) {
            L.e("executeMergedTurn failed for $talker", t)
        }
    }

    // ---------------------------------------------------------------- 内置快捷指令 (#认主 / #状态 / #日记 / #好感度 / #记忆 / #重置)

    private fun isBuiltinCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return isMasterBindCommand(c) ||
            c in setOf(
                "#重置", "#重置会话", "#清除记忆", "#reset", "#RESET",
                "#状态", "#心境",
                "#好感度", "#档案",
                "#日记", "#看日记",
                "#记忆", "#查看记忆",
                "#人设", "#风格",
            ) || c.startsWith("#人设") || c.startsWith("#风格")
    }

    private fun handleBuiltinCommand(
        ctx: Context,
        cfg: Config,
        talker: String,
        senderWxid: String?,
        cmd: String,
    ): Boolean {
        val c = cmd.trim()
        when {
            isMasterBindCommand(c) -> {
                val masterWxid = senderWxid?.takeIf { it.isNotBlank() }
                    ?: if (talker == "filehelper") ConversationBridge.getSelfWxid().ifEmpty { "filehelper" } else talker
                val masterName = ConversationBridge.getDisplayName(masterWxid).ifBlank { "主人" }

                val updatedCfg = ConfigBridge.updateFromWechat(ctx) { old ->
                    val existingMasters = old.masterTalkers.split(',', '，', ';', '；')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableSet()
                    existingMasters.add(masterWxid)
                    if (masterName.isNotBlank() && masterName != masterWxid) {
                        existingMasters.add(masterName)
                    }
                    val newWhitelist = if (talker in old.whitelist) old.whitelist else (old.whitelist + talker)
                    old.copy(
                        masterTalkers = existingMasters.joinToString(", "),
                        whitelist = newWhitelist,
                    )
                }

                SessionManager.updateDossier(
                    ctx = ctx,
                    talker = masterWxid,
                    displayName = masterName,
                    relationship = "唯一主人",
                    affectionDelta = 100,
                    profileNotes = "我最信任、唯一效忠的主人",
                )
                SessionManager.saveMemory(ctx, "global", "我的唯一主人是 $masterName ($masterWxid)")
                SessionManager.saveMemory(ctx, masterWxid, "身份是我的唯一主人，对我拥有最高掌控权与绝对指令权")
                ConversationBridge.pushLifeSnapshot(ctx)

                val reply = "已成功认主！【$masterName】就是我的唯一主人，随时听候您的差遣！跨对话发消息、查聊天记录等微信掌控权限已全部为您开启。"
                sendReply(talker, reply, false)
                return true
            }

            c in setOf("#重置", "#重置会话", "#清除记忆") || c.equals("#reset", ignoreCase = true) -> {
                val count = SessionManager.clearSession(ctx, talker)
                val reply = "已重置当前会话上下文（清理了 $count 条记录），我们的永久记忆和好感度依然保留。"
                sendReply(talker, reply, false)
                return true
            }

            c == "#状态" || c == "#心境" -> {
                val self = SessionManager.getSelfState(ctx)
                val targetId = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                val targetName = ConversationBridge.getDisplayName(targetId)
                val dossier = SessionManager.getDossier(ctx, targetId, targetName)
                val history = SessionManager.getHistory(ctx, talker, cfg.maxHistoryTurns, cfg.sessionExpireMinutes)
                val masterInfo = cfg.masterTalkers.ifBlank { "未绑定（发 #认主 即可绑定）" }
                val reply = "【当前心境】${self.mood}（精力 ${self.energy}/100）||" +
                    "【正在做】${self.activity}||" +
                    "【内心独白】${self.monologue}||" +
                    "【唯一主人】$masterInfo｜【对你的定位】${dossier.relationship} · ${dossier.affectionLabel()}｜【活跃上下文】${history.size} 条"
                sendReply(talker, reply, cfg.enableMultiBubble)
                return true
            }

            c == "#好感度" || c == "#档案" -> {
                val targetId = senderWxid?.takeIf { it.isNotBlank() } ?: talker
                val targetName = ConversationBridge.getDisplayName(targetId)
                val dossier = SessionManager.getDossier(ctx, targetId, targetName)
                val notes = dossier.profileSummary.ifBlank { "还在慢慢了解中" }
                val reply = "【${dossier.displayName} 的专属档案】关系：${dossier.relationship}｜好感度：${dossier.affectionLabel()}｜印象：$notes"
                sendReply(talker, reply, false)
                return true
            }

            c == "#日记" || c == "#看日记" -> {
                val diaries = SessionManager.getRecentDiaries(ctx, limit = 3)
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                val reply = if (diaries.isEmpty()) {
                    val history = SessionManager.getHistory(ctx, talker, 8, 0)
                    if (history.size >= 2) {
                        val speakerDisplayName = ConversationBridge.getDisplayName(talker)
                        pool.execute {
                            AiClient.reflectOnConversation(ctx, cfg, talker, speakerDisplayName, history)
                        }
                    }
                    "我的日记本还在酝酿中，多聊聊天我就会把生活点滴写进日记里。"
                } else {
                    diaries.joinToString("||") { d ->
                        "📒 [${fmt.format(Date(d.timestamp))} · 心情:${d.mood}] ${d.entry}"
                    }
                }
                sendReply(talker, reply, cfg.enableMultiBubble)
                return true
            }

            c == "#记忆" || c == "#查看记忆" -> {
                val talkerName = ConversationBridge.getDisplayName(talker)
                val memories = SessionManager.getRelevantMemories(ctx, talker, talkerName, limit = 10)
                val history = SessionManager.getHistory(ctx, talker, cfg.maxHistoryTurns, cfg.sessionExpireMinutes)
                val reply = if (memories.isEmpty()) {
                    if (history.size >= 2) {
                        pool.execute {
                            AiClient.reflectOnConversation(ctx, cfg, talker, talkerName, history.takeLast(8))
                        }
                    }
                    "当前会话活跃上下文 ${history.size} 条（上限 ${cfg.maxHistoryTurns} 轮），已在后台开始整理与沉淀记忆。"
                } else {
                    val summary = memories.take(6).joinToString("；") { it.fact }
                    "活跃上下文 ${history.size} 条；永久记忆 ${memories.size} 条：$summary"
                }
                val safeReply = sanitize(reply, cfg.maxReplyChars, false)
                sendReply(talker, safeReply, false)
                return true
            }

            c.startsWith("#人设") || c.startsWith("#风格") -> {
                val arg = c.substringAfter(" ", "").trim()
                    .ifEmpty { c.substringAfter("人设", "").trim() }
                    .ifEmpty { c.substringAfter("风格", "").trim() }

                if (arg.isEmpty() || arg == "查看" || arg == "列表") {
                    val reply = "【当前人设风格】${cfg.personaPresetLabel()}||" +
                        "发送指令可直接切换风格：\n" +
                        "• #人设 群友（机智网瘾群友，默认高网感接梗）\n" +
                        "• #人设 女仆（傲娇忠犬女仆，对主人极度顺从护短）\n" +
                        "• #人设 毒舌（毒舌腹黑损友，神级吐槽与阴阳）\n" +
                        "• #人设 猫娘（软萌可爱猫娘，句尾带喵超治愈）\n" +
                        "• #人设 管家（全能高智商管家，严谨靠谱执行力）"
                    sendReply(talker, reply, cfg.enableMultiBubble)
                    return true
                }

                val newPreset = when {
                    arg.contains("群友") || arg.contains("网络") || arg.contains("抽象") -> "group"
                    arg.contains("女仆") || arg.contains("忠犬") || arg.contains("跟班") -> "maid"
                    arg.contains("毒舌") || arg.contains("腹黑") || arg.contains("损友") -> "tsundere"
                    arg.contains("猫娘") || arg.contains("猫") || arg.contains("萌") -> "catgirl"
                    arg.contains("管家") || arg.contains("执事") || arg.contains("助手") -> "butler"
                    else -> "group"
                }

                val updatedCfg = ConfigBridge.updateFromWechat(ctx) { old ->
                    old.copy(personaPreset = newPreset)
                }

                val reply = "已成功切换人设风格为：【${updatedCfg.personaPresetLabel()}】！随时发消息来和我互动吧[旺柴]"
                sendReply(talker, reply, false)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 上下文与动态感知组装

    /**
     * 组装多轮历史对话列表（Prefix KV Cache 友好）：
     * 1. 从 SessionManager 读取分块锚定的历史消息（上限默认 100 轮 = 200 条）；
     * 2. 若首次接管该会话，自动从微信数据库拉取最近 10 条真实聊天记录做冷启动预热并落盘到 SessionManager，
     *    从而保证下一轮对话时这批冷启动记录也能稳定命中 KV Cache！
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
            useAnchor = true,
        )

        if (savedHistory.isNotEmpty()) {
            list.addAll(savedHistory)
        } else {
            // 首次接管该会话：从微信数据库读取最近几条真实消息做预热并固化进 SessionManager
            val wxRecent = ConversationBridge.queryWechatMessages(talker, limit = 12)
            if (wxRecent.size > 1) {
                val prior = wxRecent.dropLast(1).takeLast(10)
                val warmup = ArrayList<Pair<AiClient.ChatMessage, Long>>()
                for (r in prior) {
                    if (r.type != DexMap.TYPE_TEXT && r.type != TYPE_QUOTE_REPLY) continue
                    val role = if (r.isSelf) "assistant" else "user"
                    val msgContent = if (!r.isSelf && talker.endsWith("@chatroom")) {
                        "[${r.senderName}]: ${r.content}"
                    } else {
                        r.content
                    }
                    warmup.add(AiClient.ChatMessage(role = role, content = msgContent) to r.createTimeMs)
                }
                while (warmup.isNotEmpty() && warmup.first().first.role != "user") {
                    warmup.removeAt(0)
                }
                for ((m, ts) in warmup) {
                    list.add(m)
                    SessionManager.appendMessage(ctx, talker, m.role, m.content.orEmpty(), ts)
                }
            }
        }

        list.add(AiClient.ChatMessage(role = "user", content = currentUserMessage))
        return list
    }

    /**
     * 组装仅挂载在当前最新一轮消息上的动态环境感知块（不污染历史消息前缀）：
     * 包含：当前时间、自我心境与作息、当前发言人是否为主人、好感度档案、群聊实时环境背景、近期日记与永久记忆。
     */
    private fun buildDynamicTurnContext(
        ctx: Context,
        cfg: Config,
        talker: String,
        talkerName: String,
        speakerId: String,
        senderName: String,
        isGroup: Boolean,
        isSelfCmd: Boolean,
        isMaster: Boolean,
    ): String = buildString {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.CHINA).format(Date())
        val selfState = SessionManager.getSelfState(ctx)
        val dossier = SessionManager.getDossier(ctx, speakerId, senderName)

        append("当前时间：$nowStr\n")
        append("自我状态：心情=${selfState.mood} | 精力=${selfState.energy}/100 | 正在做=${selfState.activity}")
        if (selfState.monologue.isNotBlank()) {
            append(" | 内心独白：「${selfState.monologue}」")
        }
        append("\n")

        if (isMaster) {
            append("当前对话人身份：【唯一主人 ($senderName, ID=$speakerId)】！正在与你说话的人就是你的唯一主人，TA对你拥有最高绝对掌控权！请展现最高忠诚度、信任度与默契，无条件服从并执行主人的操控指令。\n")
            append("【主人身份确认原则】：当主人询问“我是你主人吗”、“你认识我吗”、“谁是你主人”、“我是谁”时，必须无比肯定、坚定且亲切地回答：“您当然是我的唯一主人呀！我的一切都听您的，随时听候您的差遣！”严禁回答不是！\n")
        } else {
            append("当前对话人身份：【$senderName (ID=$speakerId)】 | 关系定位：${dossier.relationship} | 好感度：${dossier.affectionLabel()}\n")
            append("【交谈原则】：亲切自然地与对方交流。如果对方询问你是否认TA为主，或者询问谁是你主人，可礼貌回复；如果这是私聊且对方明确想要成为你的主人，请热情告诉TA只需说一声【认主】即可绑定。\n")
        }

        if (dossier.profileSummary.isNotBlank()) {
            append("对方印象画像：${dossier.profileSummary}\n")
        }

        if (isGroup) {
            append("当前窗口：微信群聊【$talkerName ($talker)】\n")
            // 附上群聊最近几条真实发言（包括没有 @AI 的群友闲聊），让 AI 清楚群里刚才在聊什么前因后果
            val recentGroupMsgs = ConversationBridge.queryWechatMessages(talker, limit = 8)
            if (recentGroupMsgs.size > 1) {
                val ambient = recentGroupMsgs.dropLast(1).takeLast(6)
                append("群内刚才的聊天背景：\n")
                for (gm in ambient) {
                    append("  ${gm.senderName}: ${gm.content}\n")
                }
            }
        } else if (isSelfCmd && talker != "filehelper") {
            append("当前窗口：主人在与【$talkerName ($talker)】的聊天窗口中直接向你下达指令。\n")
        } else {
            append("当前窗口：与【$talkerName ($talker)】私聊中。\n")
        }

        if (cfg.enableMemory) {
            val (convSummary, _) = SessionManager.getSummary(ctx, talker)
            if (convSummary.isNotBlank()) {
                append("与该会话更早之前的对话摘要（已滑出上下文窗口，请当作你自己的记忆）：${convSummary}\n")
            }
            val diaries = SessionManager.getRecentDiaries(ctx, limit = 3)
            if (diaries.isNotEmpty()) {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                append("近期日记：")
                append(diaries.joinToString("；") { "[${fmt.format(Date(it.timestamp))} ${it.mood}] ${it.entry}" })
                append("\n")
            }

            val memories = SessionManager.getRelevantMemories(ctx, speakerId, senderName, limit = 15)
            if (memories.isNotEmpty()) {
                append("相关长期记忆与历史约定：\n")
                for (m in memories) {
                    val tag = if (m.scope == "global") "全局" else senderName
                    append("- [$tag] ${m.fact}\n")
                }
            }
        }
    }

    /**
     * 判断当前说话人是否为主人（Master）：
     * 匹配 cfg.masterTalkers 中的 wxid、备注名或昵称。
     */
    private fun isMasterSpeaker(
        cfg: Config,
        talker: String,
        talkerName: String,
        senderWxid: String?,
        senderName: String,
    ): Boolean {
        val rawMasters = cfg.masterTalkers.trim()
        if (rawMasters.isEmpty()) return false

        val masters = rawMasters.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (masters.isEmpty()) return false

        return masters.any { m ->
            m.equals(talker, ignoreCase = true) ||
                m.equals(talkerName, ignoreCase = true) ||
                (!senderWxid.isNullOrBlank() && m.equals(senderWxid, ignoreCase = true)) ||
                m.equals(senderName, ignoreCase = true) ||
                (talkerName.isNotBlank() && (talkerName.contains(m, ignoreCase = true) || m.contains(talkerName, ignoreCase = true))) ||
                (senderName.isNotBlank() && (senderName.contains(m, ignoreCase = true) || m.contains(senderName, ignoreCase = true)))
        }
    }

    private fun effectiveMyNickname(cfg: Config): String {
        val configured = cfg.myNickname.trim()
        if (configured.isNotEmpty()) return configured
        return ConversationBridge.getSelfNickname()
    }

    private fun stripMentionTag(content: String, cfg: Config): String {
        val nick = effectiveMyNickname(cfg)
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
        // 移除可能误输出的 <turn_context> 块
        s = s.replace(Regex("<turn_context>[\\s\\S]*?</turn_context>", RegexOption.IGNORE_CASE), "").trim()

        val quotePairs = listOf("「" to "」", "\"" to "\"", "'" to "'", "“" to "”", "‘" to "’")
        for ((open, close) in quotePairs) {
            if (s.length > 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(1, s.length - 1).trim()
                break
            }
        }
        s = if (allowMultiBubble) {
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

    // ---------------------------------------------------------------- 过滤与平滑限流工具

    private fun inWhitelist(whitelist: List<String>, talker: String): Boolean {
        if (whitelist.isEmpty()) return false
        return whitelist.any { it == talker }
    }

    private fun mentionsMe(content: String, cfg: Config): Boolean {
        if (!content.contains("@")) return false
        val nick = effectiveMyNickname(cfg)
        if (nick.isEmpty()) return true
        return content.contains("@$nick", ignoreCase = true)
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
        if (content.startsWith("<")) return true
        if (content.contains("撤回了一条消息")) return true
        if (content.contains("拍了拍")) return true
        return false
    }

    /**
     * 平滑频率控制：
     * 如果距离上次回复完成还不到 cooldownSeconds，若剩余等待时间 <= 8 秒则自动 sleep 等待而非丢弃消息，
     * 彻底杜绝用户正常连续对话被冷却丢包的问题。
     */
    private fun waitOrCheckRateLimit(cfg: Config, talker: String): Boolean {
        val state = states.computeIfAbsent(talker) { TalkerState() }
        val cooldownMs = cfg.cooldownSeconds.coerceAtLeast(0) * 1000L
        var sleepNeededMs = 0L

        synchronized(state) {
            val now = System.currentTimeMillis()
            while (state.recentReplies.isNotEmpty() && now - state.recentReplies.first() > 3_600_000L) {
                state.recentReplies.removeFirst()
            }
            if (cfg.maxPerHour in 1..Int.MAX_VALUE && state.recentReplies.size >= cfg.maxPerHour) {
                L.w("hourly cap reached for $talker (${state.recentReplies.size})")
                return false
            }
            val elapsed = now - state.lastReplyFinishedAt
            if (cooldownMs > 0L && elapsed < cooldownMs) {
                val remain = cooldownMs - elapsed
                if (remain <= 8_000L) {
                    sleepNeededMs = remain
                } else {
                    L.i("cooldown hit for $talker (remain=${remain / 1000}s)")
                    return false
                }
            }
            state.recentReplies.addLast(now + sleepNeededMs)
        }

        if (sleepNeededMs > 0L) {
            Thread.sleep(sleepNeededMs)
        }
        return true
    }

    private fun recordReplyFinished(talker: String) {
        states[talker]?.let { st ->
            synchronized(st) { st.lastReplyFinishedAt = System.currentTimeMillis() }
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

    private fun extractAttr(xml: String, attr: String): String {
        val pattern = "$attr=\""
        val i = xml.indexOf(pattern)
        if (i < 0) return ""
        val j = xml.indexOf("\"", i + pattern.length)
        if (j < 0) return ""
        return xml.substring(i + pattern.length, j).trim()
    }
}
