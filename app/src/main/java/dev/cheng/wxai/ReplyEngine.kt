package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 收到消息 -> 过滤 -> 调模型 -> 发送。
 *
 * 过滤链（任何一步不通过就静默返回）：
 *   开关 / 文本消息 / 不是自己发的 / 时间够新（毫秒对齐） / 白名单 / 群聊@ / 频率限制 / 去重
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

    @Volatile
    private var appCtx: Context? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        L.i("reply engine started")
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

        val isSend = values.getAsInteger("isSend") ?: 0
        if (isSend != 0) return // 自己发的，别自问自答

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

        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isEmpty()) return

        // 如果本地已经有配置缓存，直接在微秒级内快速过滤非白名单会话
        if (ConfigBridge.hasLoadedConfig()) {
            val fastCfg = ConfigBridge.get(ctx)
            if (!fastCfg.enabled || !inWhitelist(fastCfg.whitelist, talker)) return
        }

        val rawContent = values.getAsString("content").orEmpty()
        if (rawContent.isEmpty()) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val svrId = values.getAsLong("msgSvrId") ?: 0L

        pool.execute {
            val cfg = ConfigBridge.ensureLoadedOnWorker(ctx)
            if (!cfg.enabled || !inWhitelist(cfg.whitelist, talker)) return@execute
            processCandidate(cfg, talker, rawContent, msgId, svrId, msgTimeMs)
        }
    }

    // ---------------------------------------------------------------- 后台过滤与发送流程

    private fun processCandidate(
        cfg: Config,
        talker: String,
        rawContent: String,
        msgId: Long,
        svrId: Long,
        msgTimeMs: Long,
    ) {
        try {
            val isGroup = talker.endsWith("@chatroom")
            var content = if (isGroup) stripGroupSenderPrefix(rawContent) else rawContent
            content = content.trim()
            if (content.isEmpty()) return

            if (looksLikeSystemOrMedia(content)) return
            if (isGroup && cfg.groupOnlyWhenMentioned && !mentionsMe(content, cfg)) {
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

            if (!passRateLimit(cfg, talker)) return

            L.i("trigger <- $talker: ${content.take(60)}")

            val delay = if (cfg.replyDelayMaxMs > cfg.replyDelayMinMs) {
                Random.nextLong(cfg.replyDelayMinMs.toLong(), cfg.replyDelayMaxMs.toLong() + 1)
            } else {
                cfg.replyDelayMinMs.toLong()
            }
            if (delay > 0) Thread.sleep(delay)

            val extra = if (isGroup) "当前是微信群聊，回复要更口语、更短。" else null
            val result = AiClient.complete(cfg, content, extra)
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

            val ok = MessageSender.sendText(talker, reply)
            recordReply(talker)
            debugToast(cfg, if (ok) "已回复: ${reply.take(30)}" else "发送失败")
            L.i("reply -> $talker: ${reply.take(60)} (ok=$ok)")
        } catch (t: Throwable) {
            L.e("processCandidate failed for $talker", t)
        }
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

    /** 群消息 content 形如 "wxid_xxx:\n真实内容"，只对群内他人消息成立 */
    private fun stripGroupSenderPrefix(content: String): String {
        val idx = content.indexOf(":\n")
        if (idx in 1..80) {
            val head = content.substring(0, idx)
            if (head.none { it == ' ' || it == '\n' || it == '：' }) return content.substring(idx + 2)
        }
        return content
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
