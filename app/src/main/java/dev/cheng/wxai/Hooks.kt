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

        var installed = 0

        installed += hookInsert(cl, DexMap.MSG_INSERT_NAME, singleArg = true)
        // API 插入路径（部分同步/系统消息走这里），dedupe 由引擎按 msgId 负责
        installed += hookInsert(cl, DexMap.MSG_API_INSERT_NAME, singleArg = false)

        if (installed == 0) {
            L.e("no receive hook installed — 收消息不会触发，请检查微信版本/映射表")
        } else {
            L.i("receive hooks installed: $installed")
        }
    }

    private fun hookInsert(cl: ClassLoader, name: String, singleArg: Boolean): Int {
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

