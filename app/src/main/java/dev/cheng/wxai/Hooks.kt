package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import java.lang.reflect.Proxy

/**
 * 微信进程内收消息监听安装点（纯 LibXposed API 102）。
 *
 * 1. 优先挂接 WeKit 的 WeDatabaseListenerApi.INSTANCE.addListener，全生命周期 0 Hook；
 * 2. 独立运行时使用 LibXposed 102 挂载 SQLiteDatabase.insertWithOnConflict；
 * 3. 彻底不调用 de.robv.android.xposed.*。
 */
object Hooks {

    fun install(ctx: Context, cl: ClassLoader) {
        MessageSender.init(cl)
        ReplyEngine.start(ctx)
        installAntiDetectionHook(cl)

        // 等待最多 3 秒让 WeKit 完成 ActivityProxy 初始化
        var wekitCl: ClassLoader? = null
        for (i in 0 until 6) {
            wekitCl = findWeKitClassLoader()
            if (wekitCl != null) break
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                break
            }
        }

        if (wekitCl != null) {
            MessageSender.setWeKitClassLoader(wekitCl)
            ConversationBridge.setWeKitClassLoader(wekitCl)
            ImageBridge.setWeKitClassLoader(wekitCl)
            if (attachToWeKitListener(wekitCl)) {
                L.i("attached to WeKit WeDatabaseListenerApi (0 Xposed hooks installed)")
                return
            }
        }

        installStandaloneWcdbHook(cl)
    }

    private fun findWeKitClassLoader(): ClassLoader? {
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val currentAt = atCls.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null) ?: return null

            val candidates = ArrayList<ClassLoader>(4)

            runCatching {
                val inst = atCls.getDeclaredField("mInstrumentation").apply { isAccessible = true }.get(currentAt)
                inst?.javaClass?.classLoader?.let { candidates.add(it) }
            }

            runCatching {
                val mH = atCls.getDeclaredField("mH").apply { isAccessible = true }.get(currentAt) as? Handler
                if (mH != null) {
                    val cb = Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }.get(mH)
                    cb?.javaClass?.classLoader?.let { candidates.add(it) }
                }
            }

            for (loader in candidates) {
                try {
                    Class.forName("dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi", false, loader)
                    return loader
                } catch (_: Throwable) {
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun attachToWeKitListener(wekitCl: ClassLoader): Boolean {
        return try {
            val apiCls = Class.forName(
                "dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi",
                true,
                wekitCl
            )
            val listenerItf = Class.forName(
                "dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi\$IInsertListener",
                true,
                wekitCl
            )
            val apiInstance = apiCls.getDeclaredField("INSTANCE").apply {
                isAccessible = true
            }.get(null) ?: return false

            val proxy = Proxy.newProxyInstance(
                wekitCl,
                arrayOf(listenerItf)
            ) { proxyInstance, method, args ->
                when (method.name) {
                    "onInsert" -> {
                        val table = args?.getOrNull(0) as? String
                        if (table == "message" || table == "rconversation") {
                            ConversationBridge.tryCaptureFromWeKit()
                        }
                        if (table == "message") {
                            val values = args.getOrNull(1) as? ContentValues
                            if (values != null) {
                                try {
                                    ReplyEngine.onMessageInsertedValues(values)
                                } catch (t: Throwable) {
                                    L.e("onMessageInsertedValues (via WeKit) failed", t)
                                }
                            }
                        }
                        null
                    }
                    "equals" -> proxyInstance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "toString" -> "com.tencent.mm.modelbase.IListener"
                    else -> null
                }
            }

            val addMethod = apiCls.getDeclaredMethod("addListener", listenerItf).apply {
                isAccessible = true
            }
            addMethod.invoke(apiInstance, proxy)
            true
        } catch (t: Throwable) {
            L.e("attachToWeKitListener failed", t)
            false
        }
    }

    private fun installStandaloneWcdbHook(cl: ClassLoader) {
        try {
            val dbCls = Class.forName(DexMap.WCDB_DATABASE, false, cl)
            val method = dbCls.getDeclaredMethod(
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType
            )

            LxpEntry.hookMethodAfter(method) { thisObj, args, result ->
                handleInsertCallback(thisObj, args, result)
            }
            L.i("hooked ${DexMap.WCDB_DATABASE}.insertWithOnConflict via LibXposed 102")
        } catch (t: Throwable) {
            L.e("installStandaloneWcdbHook failed", t)
        }
    }

    private fun handleInsertCallback(thisObj: Any?, args: Array<Any?>, result: Any?) {
        val table = args.getOrNull(0) as? String ?: return
        if (table == "message" || table == "rconversation") {
            thisObj?.let { ConversationBridge.onSqliteDatabaseSeen(it) }
        }
        if (table != "message") return

        val rowId = result as? Long ?: -1L
        if (rowId < 0L) return

        val values = args.getOrNull(2) as? ContentValues ?: return
        try {
            ReplyEngine.onMessageInsertedValues(values)
        } catch (t: Throwable) {
            L.e("onMessageInsertedValues failed", t)
        }
    }

    private fun installAntiDetectionHook(cl: ClassLoader) {
        try {
            val cls = Class.forName(DexMap.ANTI_DETECT_CLASS, false, cl)
            val method = cls.declaredMethods.firstOrNull { it.name == DexMap.ANTI_DETECT_METHOD }
            if (method != null) {
                method.isAccessible = true
                LxpEntry.hookMethodReplace(method) { _, _ -> false }
                L.i("anti-detect hook installed on ${DexMap.ANTI_DETECT_CLASS}.${DexMap.ANTI_DETECT_METHOD}")
            }
        } catch (t: Throwable) {
            L.w("anti-detect hook skipped: ${t.message}")
        }
    }
}
