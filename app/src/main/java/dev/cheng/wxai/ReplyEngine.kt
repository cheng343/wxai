package dev.cheng.wxai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 收到消息 -> 过滤 -> 调模型 -> 发送。
 *
 * 过滤链（任何一步不通过就静默返回）：
 *   开关 / 文本消息 / 不是自己发的 / 时间够新 / 白名单 / 群聊@ / 频率限制 / 去重
 */
object ReplyEngine {

    private const val STALE_MSG_SECONDS = 120L
    private const val SEEN_CACHE = 512

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "wxai-reply").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private class TalkerState {
        @Volatile
        var lastTriggerAt: Long = 0L
        val recentReplies = ArrayDeque<Long>()
    }

    private val states = ConcurrentHashMap<String, TalkerState>()

    /**
     * 去重键：insertMessage 与 apiInsert 两条 hook 可能对同一条消息各触发一次，
     * 且字段在写库瞬间未必齐全，所以用 svrId/msgId + 会话 + 时间 + 内容哈希 组复合键。
     */
    private val seenKeys = object : LinkedHashMap<String, Boolean>(SEEN_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > SEEN_CACHE
    }

    private val fieldCache = ConcurrentHashMap<String, Field?>()

    @Volatile
    private var appCtx: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        L.i("reply engine started")
    }

    // ---------------------------------------------------------------- hook 入口

    fun onMessageInserted(msg: Any) {
        val ctx = appCtx ?: return
        val cfg = ConfigBridge.get(ctx)
        if (!cfg.enabled) return

        if ((intField(msg, DexMap.F_TYPE) ?: return) != DexMap.TYPE_TEXT) return
        if ((intField(msg, DexMap.F_IS_SEND) ?: return) != 0) return // 自己发的，别自问自答

        val talker = strField(msg, DexMap.F_TALKER).orEmpty().trim()
        if (talker.isEmpty()) return

        val isGroup = talker.endsWith("@chatroom")
        var content = strField(msg, DexMap.F_CONTENT).orEmpty()
        if (isGroup) content = stripGroupSenderPrefix(content)
        content = content.trim()
        if (content.isEmpty()) return

        val msgId = longField(msg, DexMap.F_MSG_ID) ?: 0L
        val svrId = longField(msg, DexMap.F_SVR_ID) ?: 0L
        val createTime = longField(msg, DexMap.F_CREATE_TIME) ?: 0L

        val dedupeKey = when {
            svrId != 0L -> "s$svrId"
            msgId != 0L -> "m$msgId"
            else -> "h$talker|$createTime|${content.hashCode()}"
        }
        if (!markSeen(dedupeKey)) {
            L.i("duplicate msg ignored ($dedupeKey)")
            return
        }

        val nowSec = System.currentTimeMillis() / 1000
        if (createTime > 0 && nowSec - createTime > STALE_MSG_SECONDS) {
            L.i("skip stale msg from $talker (${nowSec - createTime}s old)")
            return
        }

        if (!inWhitelist(cfg.whitelist, talker)) return
        if (isGroup && cfg.groupOnlyWhenMentioned && !mentionsMe(content, cfg)) {
            L.i("group msg skipped (not mentioned): ${content.take(40)}")
            return
        }
        if (looksLikeSystemOrMedia(content)) return
        if (!passRateLimit(cfg, talker)) return

        L.i("trigger <- $talker: ${content.take(60)}")
        dispatch(cfg, talker, content, isGroup)
    }

    // ---------------------------------------------------------------- 发送流程

    private fun dispatch(cfg: Config, talker: String, content: String, isGroup: Boolean) {
        pool.execute {
            try {
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
                    return@execute
                }

                val reply = sanitize(text, cfg.maxReplyChars)
                if (reply.isEmpty()) {
                    L.w("sanitized reply empty for $talker")
                    return@execute
                }

                val ok = MessageSender.sendText(talker, reply)
                recordReply(talker)
                debugToast(cfg, if (ok) "已回复: ${reply.take(30)}" else "发送失败")
                L.i("reply -> $talker: ${reply.take(60)} (ok=$ok)")
            } catch (t: Throwable) {
                L.e("dispatch failed for $talker", t)
            }
        }
    }

    private fun sanitize(text: String, maxChars: Int): String {
        var s = text.trim()
        // 去掉模型爱加的包裹引号
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

    // ---------------------------------------------------------------- 反射读字段

    private fun fieldOf(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + "#" + name
        fieldCache[key]?.let { return it }
        if (fieldCache.containsKey(key)) return null

        var c: Class<*>? = clazz
        var found: Field? = null
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                found = f
                break
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                L.e("field lookup failed: $key", t)
                break
            }
        }
        fieldCache[key] = found
        return found
    }

    private fun anyField(msg: Any, name: String): Any? = try {
        fieldOf(msg.javaClass, name)?.get(msg)
    } catch (t: Throwable) {
        L.e("read field $name failed", t)
        null
    }

    private fun intField(msg: Any, name: String): Int? = (anyField(msg, name) as? Number)?.toInt()

    private fun longField(msg: Any, name: String): Long? = (anyField(msg, name) as? Number)?.toLong()

    private fun strField(msg: Any, name: String): String? = anyField(msg, name) as? String
}
