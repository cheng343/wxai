package dev.cheng.wxai

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 走微信原生发送链路发文本：
 *   NetSceneSendMsg(talker, content, type=1, flag=0, null)
 *     -> NetSceneQueue.e() 取队列
 *     -> queue.g(scene) 投递
 *
 * 8.0.76 对应：y11.r0 / e01.d9.e() / com.tencent.mm.modelbase.r1.g(m1)Z
 * 这条路径和手打发送完全一致（对方会收到正常的服务器消息，id 也由服务端分配）。
 */
object MessageSender {

    @Volatile
    private var ready = false

    @Volatile
    private var sceneCtor: Constructor<*>? = null

    @Volatile
    private var queueGetter: Method? = null

    @Volatile
    private var queuePost: Method? = null

    @Volatile
    private var sceneCls: Class<*>? = null

    val isReady: Boolean get() = ready

    fun init(cl: ClassLoader) {
        try {
            resolve(cl)
            ready = true
            L.i("sender ready: ${sceneCls?.name}")
        } catch (t: Throwable) {
            ready = false
            L.e("sender init failed", t)
        }
    }

    @Synchronized
    private fun resolve(cl: ClassLoader) {
        val sCls = XposedHelpers.findClass(DexMap.SEND_SCENE, cl)
        sceneCls = sCls

        sceneCtor = sCls.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size >= 5 &&
                p[0] == String::class.java &&
                p[1] == String::class.java &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType &&
                p[4] == Any::class.java
        } ?: throw IllegalStateException("NetSceneSendMsg ctor not found in ${sCls.name}")
        sceneCtor?.isAccessible = true

        val getterCls = XposedHelpers.findClass(DexMap.QUEUE_GETTER, cl)
        queueGetter = getterCls.declaredMethods.firstOrNull { m ->
            m.name == DexMap.QUEUE_GETTER_METHOD && m.parameterTypes.isEmpty() && Modifier.isStatic(m.modifiers)
        } ?: throw IllegalStateException("queue getter not found in ${getterCls.name}")
        queueGetter?.isAccessible = true

        val queueCls = XposedHelpers.findClass(DexMap.QUEUE, cl)
        queuePost = queueCls.declaredMethods.firstOrNull { m ->
            m.name == DexMap.QUEUE_POST_METHOD &&
                m.parameterTypes.size == 1 &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterTypes[0].isAssignableFrom(sCls)
        } ?: throw IllegalStateException("queue.post not found in ${queueCls.name}")
        queuePost?.isAccessible = true
    }

    /** 阻塞式发送，必须从后台线程调用。成功返回 true（仅代表成功入队）。 */
    fun sendText(talker: String, text: String): Boolean {
        if (!ready) {
            L.w("sendText called before init")
            return false
        }
        return try {
            val scene = sceneCtor!!.newInstance(talker, text, DexMap.TYPE_TEXT, 0, null)
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

