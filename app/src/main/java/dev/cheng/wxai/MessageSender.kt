package dev.cheng.wxai

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 微信全能消息与动作发送器：
 * 1. 深度桥接 WeKit 的 WeMessageApi：
 *    - sendText(toUser, text)
 *    - sendPat(toUser, patTargetWxId) —— 拍一拍
 *    - sendQuoteText(talker, quotedMsgSvrId, content) —— 引用消息回复
 *    - revokeMsgByMsgId(msgId) —— 撤回消息
 *    - sendShareCard(toUser, cardWxId) —— 发送联系人名片
 *    - sendEmojiByMd5(toUser, md5) —— 发送表情包
 * 2. 深度桥接 WeKit 的 WeMomentsApi：
 *    - postText(content, null, null) —— 发布纯文字微信朋友圈
 * 3. 支持拟人化多气泡分段发送（sendHumanLikeText），以及微信原生 NetSceneSendMsg 兜底。
 */
object MessageSender {

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var wekitClassLoader: ClassLoader? = null

    @Volatile
    private var weMessageApiInstance: Any? = null

    @Volatile
    private var weMessageApiSendText: Method? = null

    @Volatile
    private var weMessageApiSendPat: Method? = null

    @Volatile
    private var weMessageApiSendQuoteSvrId: Method? = null

    @Volatile
    private var weMessageApiRevokeMsgId: Method? = null

    @Volatile
    private var weMessageApiSendShareCard: Method? = null

    @Volatile
    private var weMessageApiSendEmojiMd5: Method? = null

    @Volatile
    private var weMomentsApiInstance: Any? = null

    @Volatile
    private var weMomentsApiPostText: Method? = null

    @Volatile
    private var resolved = false

    @Volatile
    private var sceneCtor: Constructor<*>? = null

    @Volatile
    private var queueGetter: Method? = null

    @Volatile
    private var queuePost: Method? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun init(cl: ClassLoader) {
        appClassLoader = cl
    }

    fun setWeKitClassLoader(cl: ClassLoader) {
        wekitClassLoader = cl
        resolveWeKitApis(cl)
    }

    private fun resolveWeKitApis(cl: ClassLoader) {
        try {
            val apiCls = Class.forName("dev.ujhhgtg.wekit.features.api.core.WeMessageApi", false, cl)
            val inst = apiCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            weMessageApiInstance = inst

            runCatching {
                weMessageApiSendText = apiCls.getDeclaredMethod("sendText", String::class.java, String::class.java).apply {
                    isAccessible = true
                }
            }
            runCatching {
                weMessageApiSendPat = apiCls.getDeclaredMethod("sendPat", String::class.java, String::class.java).apply {
                    isAccessible = true
                }
            }
            runCatching {
                weMessageApiSendQuoteSvrId = apiCls.getDeclaredMethod(
                    "sendQuoteText",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java
                ).apply { isAccessible = true }
            }
            runCatching {
                weMessageApiRevokeMsgId = apiCls.getDeclaredMethod(
                    "revokeMsgByMsgId",
                    Long::class.javaPrimitiveType
                ).apply { isAccessible = true }
            }
            runCatching {
                weMessageApiSendShareCard = apiCls.getDeclaredMethod(
                    "sendShareCard",
                    String::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            }
            runCatching {
                weMessageApiSendEmojiMd5 = apiCls.getDeclaredMethod(
                    "sendEmojiByMd5",
                    String::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            }
            L.i("WeKit WeMessageApi methods resolved (sendText=${weMessageApiSendText != null}, pat=${weMessageApiSendPat != null}, quote=${weMessageApiSendQuoteSvrId != null})")
        } catch (t: Throwable) {
            L.w("resolveWeKitApis(WeMessageApi) failed: ${t.message}")
        }

        try {
            val momentsCls = Class.forName("dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi", false, cl)
            val momentsInst = momentsCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            weMomentsApiInstance = momentsInst
            runCatching {
                weMomentsApiPostText = momentsCls.getDeclaredMethod(
                    "postText",
                    String::class.java,
                    String::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            }
            L.i("WeKit WeMomentsApi resolved (postText=${weMomentsApiPostText != null})")
        } catch (t: Throwable) {
            L.w("resolveWeKitApis(WeMomentsApi) skipped: ${t.message}")
        }
    }

    @Synchronized
    private fun ensureResolved(): Boolean {
        if (resolved) return true
        val cl = appClassLoader ?: return false

        val wkCl = wekitClassLoader
        if (wkCl != null && weMessageApiSendText == null) {
            resolveWeKitApis(wkCl)
        }
        if (weMessageApiSendText != null && weMessageApiInstance != null) {
            resolved = true
            return true
        }

        return try {
            val sCls = Class.forName(DexMap.SEND_SCENE, false, cl)
            val ctors = sCls.declaredConstructors
            var selected: Constructor<*>? = null

            selected = ctors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 5 &&
                    p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    !p[4].isPrimitive
            }

            if (selected == null) {
                selected = ctors.firstOrNull { c ->
                    val p = c.parameterTypes
                    p.size == 6 &&
                        p[0] == String::class.java &&
                        p[1] == String::class.java &&
                        p[2] == Int::class.javaPrimitiveType &&
                        p[3] == Int::class.javaPrimitiveType &&
                        !p[4].isPrimitive &&
                        p[5] == String::class.java
                }
            }

            if (selected == null) {
                selected = ctors.firstOrNull { c ->
                    val p = c.parameterTypes
                    p.size >= 4 &&
                        p[0] == String::class.java &&
                        p[1] == String::class.java &&
                        p[2] == Int::class.javaPrimitiveType &&
                        p[3] == Int::class.javaPrimitiveType
                }
            }

            val ctor = selected ?: throw IllegalStateException("NetSceneSendMsg ctor not found in ${sCls.name}")
            ctor.isAccessible = true
            sceneCtor = ctor

            val getterCls = Class.forName(DexMap.QUEUE_GETTER, false, cl)
            val getter = getterCls.declaredMethods.firstOrNull { m ->
                m.name == DexMap.QUEUE_GETTER_METHOD && m.parameterTypes.isEmpty() && Modifier.isStatic(m.modifiers)
            } ?: throw IllegalStateException("queue getter not found in ${getterCls.name}")
            getter.isAccessible = true
            queueGetter = getter

            val queueCls = Class.forName(DexMap.QUEUE, false, cl)
            val post = queueCls.declaredMethods.firstOrNull { m ->
                m.name == DexMap.QUEUE_POST_METHOD &&
                    m.parameterTypes.size == 1 &&
                    m.returnType == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[0].isAssignableFrom(sCls)
            } ?: throw IllegalStateException("queue.post not found in ${queueCls.name}")
            post.isAccessible = true
            queuePost = post

            resolved = true
            L.i("sender resolved native fallback: ${sCls.name}")
            true
        } catch (t: Throwable) {
            L.e("sender lazy resolve failed", t)
            false
        }
    }

    /**
     * 拟人化多气泡发送：
     * 如果开启了 multiBubble 且模型回复中含有 "||" 或多行自然段落（最多拆 3 条），
     * 自动按真人打字速度逐条发送，让聊天极富生命感。
     */
    fun sendHumanLikeText(talker: String, rawReply: String, enableMultiBubble: Boolean, quoteSvrId: Long = 0L): Boolean {
        val segments = if (enableMultiBubble) {
            splitIntoBubbles(rawReply)
        } else {
            listOf(rawReply.replace("||", " ").trim())
        }

        if (segments.isEmpty()) return false
        var anySuccess = false
        for ((idx, seg) in segments.withIndex()) {
            if (seg.isBlank()) continue
            if (idx > 0) {
                val typingDelay = (seg.length * 60L).coerceIn(450L, 1600L) + Random.nextLong(100L, 350L)
                try {
                    Thread.sleep(typingDelay)
                } catch (_: InterruptedException) {
                }
            }
            ReplyEngine.markAiOutbound(talker, seg)
            val ok = if (idx == 0 && quoteSvrId > 0L) {
                val quoted = sendQuoteText(talker, quoteSvrId, seg)
                if (!quoted) sendText(talker, seg) else true
            } else {
                sendText(talker, seg)
            }
            if (ok) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    private fun splitIntoBubbles(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.contains("||")) {
            return trimmed.split("||")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(4)
        }
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size in 2..3 && trimmed.length <= 180) {
            return lines
        }
        val sentenceSplit = trimmed.split(Regex("(?<=[！？!?~～])\\s+"))
        if (sentenceSplit.size in 2..3 && trimmed.length <= 100) {
            return sentenceSplit.map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOf(trimmed)
    }

    /** 将发送逻辑投递到主线程执行，等待最多 5 秒返回结果。 */
    fun sendText(talker: String, text: String): Boolean = runOnMainBlocking {
        doSendOnMain(talker, text)
    }

    /** 拍一拍某人（在群聊 talker 中拍 patTargetWxId，或在私聊中拍对方） */
    fun sendPat(talker: String, patTargetWxId: String): Boolean = runOnMainBlocking {
        ensureResolved()
        val m = weMessageApiSendPat
        val inst = weMessageApiInstance
        if (m == null || inst == null) {
            L.w("sendPat unavailable: WeKit WeMessageApi not bound")
            return@runOnMainBlocking false
        }
        try {
            val ok = m.invoke(inst, talker, patTargetWxId) as? Boolean ?: false
            L.i("sendPat($talker, $patTargetWxId) -> $ok")
            ok
        } catch (t: Throwable) {
            L.e("sendPat failed", t)
            false
        }
    }

    /** 引用指定消息（msgSvrId）进行回复 */
    fun sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean = runOnMainBlocking {
        ensureResolved()
        val m = weMessageApiSendQuoteSvrId
        val inst = weMessageApiInstance
        if (m == null || inst == null || quotedMsgSvrId <= 0L) {
            return@runOnMainBlocking doSendOnMain(talker, content)
        }
        try {
            ReplyEngine.markAiOutbound(talker, content)
            val ok = m.invoke(inst, talker, quotedMsgSvrId, content) as? Boolean ?: false
            if (ok) {
                L.i("sendQuoteText($talker, svrId=$quotedMsgSvrId) -> true")
                true
            } else {
                doSendOnMain(talker, content)
            }
        } catch (t: Throwable) {
            L.e("sendQuoteText failed, falling back to sendText", t)
            doSendOnMain(talker, content)
        }
    }

    /** 撤回本机发送的消息（通过本地 msgId） */
    fun revokeMessage(msgId: Long): Boolean = runOnMainBlocking {
        ensureResolved()
        val m = weMessageApiRevokeMsgId
        val inst = weMessageApiInstance
        if (m == null || inst == null || msgId <= 0L) return@runOnMainBlocking false
        try {
            val ok = m.invoke(inst, msgId) as? Boolean ?: false
            L.i("revokeMessage(msgId=$msgId) -> $ok")
            ok
        } catch (t: Throwable) {
            L.e("revokeMessage failed", t)
            false
        }
    }

    /** 向指定会话发送联系人的微信名片 */
    fun sendShareCard(talker: String, cardWxId: String): Boolean = runOnMainBlocking {
        ensureResolved()
        val m = weMessageApiSendShareCard
        val inst = weMessageApiInstance
        if (m == null || inst == null) return@runOnMainBlocking false
        try {
            val ok = m.invoke(inst, talker, cardWxId) as? Boolean ?: false
            L.i("sendShareCard($talker, card=$cardWxId) -> $ok")
            ok
        } catch (t: Throwable) {
            L.e("sendShareCard failed", t)
            false
        }
    }

    /** 发布一条纯文字微信朋友圈 */
    fun postMomentText(content: String): Boolean = runOnMainBlocking {
        ensureResolved()
        val m = weMomentsApiPostText
        val inst = weMomentsApiInstance
        if (m == null || inst == null || content.isBlank()) {
            L.w("postMomentText unavailable")
            return@runOnMainBlocking false
        }
        try {
            val ok = m.invoke(inst, content.trim(), null, null) as? Boolean ?: false
            L.i("postMomentText('${content.take(30)}') -> $ok")
            ok
        } catch (t: Throwable) {
            L.e("postMomentText failed", t)
            false
        }
    }

    private fun runOnMainBlocking(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(6, TimeUnit.SECONDS) && result.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun doSendOnMain(talker: String, text: String): Boolean {
        if (!ensureResolved()) {
            L.w("sendText skipped: sender not resolved")
            return false
        }

        val wkMethod = weMessageApiSendText
        val wkInst = weMessageApiInstance
        if (wkMethod != null && wkInst != null) {
            try {
                val ok = wkMethod.invoke(wkInst, talker, text) as? Boolean ?: false
                if (ok) {
                    L.i("sent via WeKit WeMessageApi -> $talker: ${text.take(40)}")
                    return true
                }
                L.w("WeKit WeMessageApi returned false, trying native fallback")
            } catch (t: Throwable) {
                L.e("WeKit WeMessageApi.sendText threw, trying native fallback", t)
            }
        }

        return try {
            val ctor = sceneCtor ?: return false
            val paramTypes = ctor.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)

            for (i in paramTypes.indices) {
                args[i] = when (i) {
                    0 -> talker
                    1 -> text
                    2 -> DexMap.TYPE_TEXT
                    3 -> 0
                    else -> {
                        val pt = paramTypes[i]
                        when {
                            pt == Long::class.javaPrimitiveType -> 0L
                            pt == Int::class.javaPrimitiveType -> 0
                            pt == Boolean::class.javaPrimitiveType -> false
                            pt == String::class.java -> ""
                            else -> null
                        }
                    }
                }
            }

            val scene = ctor.newInstance(*args)
            val queue = queueGetter!!.invoke(null)
                ?: throw IllegalStateException("queue is null")
            val ok = queuePost!!.invoke(queue, scene) as? Boolean ?: false
            if (ok) L.i("queued -> $talker: ${text.take(40)}") else L.w("queue rejected message to $talker")
            ok
        } catch (t: Throwable) {
            L.e("sendText native fallback failed ($talker)", t)
            false
        }
    }
}
