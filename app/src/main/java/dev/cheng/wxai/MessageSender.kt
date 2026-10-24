package dev.cheng.wxai

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 走微信发送链路发文本（优先使用 WeKit 的 WeMessageApi.sendText，回退微信原生 NetSceneSendMsg）。
 */
object MessageSender {

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var wekitClassLoader: ClassLoader? = null

    @Volatile
    private var weMessageApiSendText: Method? = null

    @Volatile
    private var weMessageApiInstance: Any? = null

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
        resolveWeKitSendText(cl)
    }

    private fun resolveWeKitSendText(cl: ClassLoader) {
        try {
            val apiCls = Class.forName("dev.ujhhgtg.wekit.features.api.core.WeMessageApi", false, cl)
            val inst = apiCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val method = apiCls.getDeclaredMethod("sendText", String::class.java, String::class.java).apply {
                isAccessible = true
            }
            weMessageApiInstance = inst
            weMessageApiSendText = method
            L.i("WeKit WeMessageApi.sendText resolved successfully")
        } catch (t: Throwable) {
            L.w("resolveWeKitSendText skipped or failed: ${t.message}")
        }
    }

    @Synchronized
    private fun ensureResolved(): Boolean {
        if (resolved) return true
        val cl = appClassLoader ?: return false

        // 如果 WeKit ClassLoader 存在但尚未解析 WeMessageApi，先尝试一次
        val wkCl = wekitClassLoader
        if (wkCl != null && weMessageApiSendText == null) {
            resolveWeKitSendText(wkCl)
        }
        if (weMessageApiSendText != null && weMessageApiInstance != null) {
            resolved = true
            return true
        }

        return try {
            val sCls = Class.forName(DexMap.SEND_SCENE, false, cl)

            // 优先匹配适合我们调用入参的构造函数
            val ctors = sCls.declaredConstructors
            var selected: Constructor<*>? = null

            // 1. 尝试 5 参数: (String, String, int, int, Object)
            selected = ctors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 5 &&
                    p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    !p[4].isPrimitive
            }

            // 2. 尝试 6 参数: (String, String, int, int, Object, String)
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

            // 3. 兜底匹配任意一个前 4 个参数符合 (String, String, int, int) 的构造器
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
            L.i("sender resolved native fallback: ${sCls.name} (params=${ctor.parameterTypes.joinToString { it.simpleName }})")
            true
        } catch (t: Throwable) {
            L.e("sender lazy resolve failed", t)
            false
        }
    }

    /** 将发送逻辑投递到主线程执行，等待最多 5 秒返回结果。 */
    fun sendText(talker: String, text: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return doSendOnMain(talker, text)
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(doSendOnMain(talker, text))
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(5, TimeUnit.SECONDS) && result.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun doSendOnMain(talker: String, text: String): Boolean {
        if (!ensureResolved()) {
            L.w("sendText skipped: sender not resolved")
            return false
        }

        // 优先通过 WeKit 的 WeMessageApi.sendText 发送
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

        // 兜底：微信原生 NetSceneQueue 投递
        return try {
            val ctor = sceneCtor!!
            val paramTypes = ctor.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)

            for (i in paramTypes.indices) {
                args[i] = when (i) {
                    0 -> talker
                    1 -> text
                    2 -> DexMap.TYPE_TEXT
                    3 -> 0
                    4 -> {
                        val pt = paramTypes[4]
                        when {
                            pt == Long::class.javaPrimitiveType -> 0L
                            pt == Int::class.javaPrimitiveType -> 0
                            pt == String::class.java -> ""
                            else -> null
                        }
                    }
                    5 -> {
                        val pt = paramTypes[5]
                        when {
                            pt == Long::class.javaPrimitiveType -> 0L
                            pt == Int::class.javaPrimitiveType -> 0
                            pt == String::class.java -> ""
                            else -> null
                        }
                    }
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
