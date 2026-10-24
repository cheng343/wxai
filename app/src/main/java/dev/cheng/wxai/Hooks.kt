package dev.cheng.wxai

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 微信进程内所有 hook 的安装点。 */
object Hooks {

    fun install(ctx: Context, cl: ClassLoader) {
        MessageSender.init(cl)
        ReplyEngine.start(ctx)

        // 8.0.76 中 f9.H9(e9) 只是 this.I9(e9, false) 的单行包装；
        // 优先只挂底层 I9(e9, boolean)，避免每次插入触发两次 hook。
        var installed = hookInsert(ctx, cl, DexMap.MSG_API_INSERT_NAME, singleArg = false)
        if (installed == 0) {
            installed += hookInsert(ctx, cl, DexMap.MSG_INSERT_NAME, singleArg = true)
        }

        if (installed == 0) {
            L.e("no receive hook installed — 收消息不会触发，请检查微信版本/映射表")
        } else {
            L.i("receive hooks installed: $installed")
        }
    }

    private fun hookInsert(ctx: Context, cl: ClassLoader, name: String, singleArg: Boolean): Int {
        return try {
            val storage = XposedHelpers.findClass(DexMap.MSG_INFO_STORAGE, cl)
            val msgInfoClass = XposedHelpers.findClass(DexMap.MSG_INFO, cl)

            val target = storage.declaredMethods.firstOrNull { m ->
                m.name == name &&
                    !m.isSynthetic &&
                    m.returnType == Long::class.javaPrimitiveType &&
                    if (singleArg) {
                        m.parameterTypes.size == 1 && m.parameterTypes[0] == msgInfoClass
                    } else {
                        m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == msgInfoClass &&
                            m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                    }
            }

            if (target == null) {
                L.w("method ${DexMap.MSG_INFO_STORAGE}.$name not found (singleArg=$singleArg)")
                return 0
            }

            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 顺带把 f9 实例交给 ConversationBridge（f9.r 同样持有 s85.k0 / ka5.b0 数据库包装器）
                    param.thisObject?.let { ConversationBridge.onStorageSeen(ctx, it) }

                    val rowId = param.result as? Long ?: -1L
                    if (rowId < 0L) return
                    val msg = param.args.getOrNull(0) ?: return
                    try {
                        ReplyEngine.onMessageInserted(msg)
                    } catch (t: Throwable) {
                        L.e("onMessageInserted failed", t)
                    }
                }
            })
            L.i("hooked ${DexMap.MSG_INFO_STORAGE}.$name (${target.parameterTypes.joinToString { it.simpleName }})")
            1
        } catch (t: Throwable) {
            L.e("hook $name failed", t)
            0
        }
    }
}

