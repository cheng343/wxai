package dev.cheng.wxai

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 走微信原生发送链路发文本（对齐 WeKit WeMessageApi.sendText）：
 *   NetSceneSendMsg(talker, content, type=1, flag=0, null)
 *     -> NetSceneQueue.e() 取队列
 *     -> queue.g(scene) 投递
 *
 * 启动期只保存 ClassLoader，不做任何类加载或反射扫描；
 * 仅在首次需要发送回复时在主线程按需解析一次，避免拖慢或干扰微信启动。
 */
object MessageSender {

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var resolved = false

    @Volatile
    private var sceneCtor: Constructor<*>? = null

    @Volatile
    private var ctorArgCount: Int = 5

    @Volatile
    private var queueGetter: Method? = null

    @Volatile
    private var queuePost: Method? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun init(cl: ClassLoader) {
        appClassLoader = cl
    }

    @Synchronized
    private fun ensureResolved(): Boolean {
        if (resolved) return true
        val cl = appClassLoader ?: return false
        return try {
            val sCls = XposedHelpers.findClass(DexMap.SEND_SCENE, cl)
            val ctor5 = sCls.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 5 &&
                    p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType
            }
            val ctor = ctor5 ?: sCls.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 6 &&
                    p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType
            } ?: throw IllegalStateException("NetSceneSendMsg ctor not found in ${sCls.name}")
            ctor.isAccessible = true
            sceneCtor = ctor
            ctorArgCount = ctor.parameterCount

            val getterCls = XposedHelpers.findClass(DexMap.QUEUE_GETTER, cl)
            val getter = getterCls.declaredMethods.firstOrNull { m ->
                m.name == DexMap.QUEUE_GETTER_METHOD && m.parameterTypes.isEmpty() && Modifier.isStatic(m.modifiers)
            } ?: throw IllegalStateException("queue getter not found in ${getterCls.name}")
            getter.isAccessible = true
            queueGetter = getter

            val queueCls = XposedHelpers.findClass(DexMap.QUEUE, cl)
            val post = queueCls.declaredMethods.firstOrNull { m ->
                m.name == DexMap.QUEUE_POST_METHOD &&
                    m.parameterTypes.size == 1 &&
                    m.returnType == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[0].isAssignableFrom(sCls)
            } ?: throw IllegalStateException("queue.post not found in ${queueCls.name}")
            post.isAccessible = true
            queuePost = post

            resolved = true
            L.i("sender resolved lazily: ${sCls.name} (args=$ctorArgCount)")
            true
        } catch (t: Throwable) {
            L.e("sender lazy resolve failed", t)
            false
        }
    }

    /** 将构建 NetSceneSendMsg 与入队投递到主线程执行，等待最多 5 秒返回结果。 */
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
        return try {
            val ctor = sceneCtor!!
            val scene = if (ctorArgCount == 6) {
                ctor.newInstance(talker, text, DexMap.TYPE_TEXT, 0, null, 0L)
            } else {
                ctor.newInstance(talker, text, DexMap.TYPE_TEXT, 0, null)
            }
            val queue = queueGetter!!.invoke(null)
                ?: throw IllegalStateException("queue is null")
            val ok = queuePost!!.invoke(queue, scene) as? Boolean ?: false
            if (ok) L.i("queued -> $talker: ${text.take(40)}") else L.w("queue rejected message to $talker")
            ok
        } catch (t: Throwable) {
            L.e("sendText failed ($talker)", t)
            false
        }
    }
}


