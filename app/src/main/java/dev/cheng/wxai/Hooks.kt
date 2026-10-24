package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 微信进程内收消息 hook 安装点。
 *
 * 完全对齐 WeKit (WeDatabaseListenerApi + EventTriggerBus)：
 * 不再去 hook 800 行带复杂同步锁的混淆方法 com.tencent.mm.storage.f9.I9（会导致 ART 反优化/锁竞争卡死微信），
 * 而是直接 hook WCDB 的标准短方法：
 *   com.tencent.wcdb.database.SQLiteDatabase.insertWithOnConflict(String, String, ContentValues, int)
 * 当 table == "message" 时直接从 ContentValues 读取消息字段，零混淆依赖、零反射开销。
 */
object Hooks {

    fun install(ctx: Context, cl: ClassLoader) {
        MessageSender.init(cl)
        ReplyEngine.start(ctx)

        try {
            XposedHelpers.findAndHookMethod(
                DexMap.WCDB_DATABASE,
                cl,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table == "message" || table == "rconversation") {
                            param.thisObject?.let { ConversationBridge.onSqliteDatabaseSeen(it) }
                        }
                        if (table != "message") return

                        val rowId = param.result as? Long ?: -1L
                        if (rowId < 0L) return

                        val values = param.args.getOrNull(2) as? ContentValues ?: return
                        try {
                            ReplyEngine.onMessageInsertedValues(values)
                        } catch (t: Throwable) {
                            L.e("onMessageInsertedValues failed", t)
                        }
                    }
                }
            )
            L.i("hooked ${DexMap.WCDB_DATABASE}.insertWithOnConflict")
        } catch (t: Throwable) {
            L.e("hook SQLiteDatabase.insertWithOnConflict failed", t)
        }
    }
}


