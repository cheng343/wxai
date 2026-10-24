package dev.cheng.wxai

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

/**
 * 微信进程侧的会话枚举器。
 *
 * 完全对齐 WeKit (WeDatabaseApi) 的被动式数据库句柄获取：
 * 1. 启动期绝不启动轮询线程、绝不执行 rawQuery SQL、绝不主动调用跨进程 ContentProvider；
 * 2. 被动通过两条轻量通道记录 SQLiteDatabase 引用：
 *    a) SQLiteDatabase.insertWithOnConflict("message" | "rconversation", ...) 的 thisObject 就是 EnMicroMsg.db；
 *    b) hm0.j1.u() 的返回值 CoreStorage (hm0.b0) -> ka5.b0 -> P()；
 * 3. 只有当用户在 WXAI 模块界面点击「从微信选择会话 / 刷新」发出广播时，才在后台线程执行一次查询并回推。
 */
object ConversationBridge {

    private const val TAG = "WXAI/Conv"
    private const val SQLITE_INTERFACE = "s85.k0"

    @Volatile
    private var database: Any? = null

    @Volatile
    private var appClassLoader: ClassLoader? = null

    fun install(ctx: Context, cl: ClassLoader) {
        val app = ctx.applicationContext ?: ctx
        appClassLoader = cl

        try {
            registerRefreshReceiver(app)
        } catch (t: Throwable) {
            L.e("$TAG register receiver failed", t)
        }

        // 对齐 WeKit WeDatabaseApi.onEnable：被动挂 hm0.j1.u()，只在微信自己调用时顺手记下 DB 引用，不做任何查询
        try {
            XposedHelpers.findAndHookMethod(
                DexMap.MM_KERNEL,
                cl,
                DexMap.MM_KERNEL_GET_STORAGE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (database != null) return
                        val storageObj = param.result ?: return
                        tryCaptureQuietly(storageObj)
                    }
                }
            )
        } catch (t: Throwable) {
            L.w("$TAG hook ${DexMap.MM_KERNEL}.${DexMap.MM_KERNEL_GET_STORAGE} skipped: ${t.message}")
        }
    }

    /**
     * 当 SQLiteDatabase.insertWithOnConflict 操作 message / rconversation 表时，
     * thisObject 本身就是 EnMicroMsg.db 的 SQLiteDatabase 实例，直接记录即可。
     */
    fun onSqliteDatabaseSeen(dbObj: Any) {
        if (database == null) {
            database = dbObj
            L.i("$TAG database captured from insertWithOnConflict: ${dbObj.javaClass.name}")
        }
    }

    // ---------------- 取 DB 句柄 ----------------

    private fun tryCaptureFromKernel(cl: ClassLoader? = appClassLoader): Boolean {
        if (database != null) return true
        val loader = cl ?: return false
        val coreStorage = getCoreStorageQuietly(loader) ?: return false
        tryCaptureQuietly(coreStorage)
        return database != null
    }

    private fun getCoreStorageQuietly(cl: ClassLoader): Any? {
        return try {
            val kernelCls = XposedHelpers.findClass(DexMap.MM_KERNEL, cl)
            val kernelInst = kernelCls.declaredFields.firstNotNullOfOrNull { f ->
                if (!Modifier.isStatic(f.modifiers) || f.type != kernelCls) return@firstNotNullOfOrNull null
                try {
                    f.isAccessible = true
                    f.get(null)
                } catch (_: Throwable) {
                    null
                }
            } ?: return null

            for (f in kernelCls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.name == "d" || f.type.name == DexMap.CORE_STORAGE) {
                    f.isAccessible = true
                    val v = f.get(kernelInst)
                    if (v != null) return v
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryCaptureQuietly(storageObj: Any) {
        if (database != null) return
        synchronized(this) {
            if (database == null) {
                val handle = findDatabaseHandle(storageObj)
                if (handle != null) {
                    database = handle
                    L.i("$TAG database ready: ${handle.javaClass.name}")
                }
            }
        }
    }

    private fun findDatabaseHandle(storageObj: Any): Any? {
        if (storageObj.javaClass.name == DexMap.SQLITE_WRAPPER) {
            callDatabaseAccessor(storageObj)?.let { return it }
        }

        var cls: Class<*>? = storageObj.javaClass
        while (true) {
            val c = cls ?: break
            if (c == Any::class.java) break
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val value = try {
                    f.isAccessible = true
                    f.get(storageObj)
                } catch (_: Throwable) {
                    null
                } ?: continue

                val runtimeName = value.javaClass.name
                val declaredName = f.type.name
                if (runtimeName.contains("SQLiteDatabase")) {
                    return value
                }
                if (runtimeName == DexMap.SQLITE_WRAPPER ||
                    declaredName == DexMap.SQLITE_WRAPPER ||
                    declaredName == SQLITE_INTERFACE
                ) {
                    val db = callDatabaseAccessor(value)
                    if (db != null) {
                        return db
                    }
                }
            }
            cls = c.superclass
        }
        return null
    }

    private fun callDatabaseAccessor(wrapper: Any): Any? {
        try {
            val bField = wrapper.javaClass.getDeclaredField("b")
            bField.isAccessible = true
            if (bField.get(wrapper) == null) return null
        } catch (_: Throwable) {
        }

        var cls: Class<*>? = wrapper.javaClass
        while (true) {
            val c = cls ?: break
            if (c == Any::class.java) break
            for (m in c.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 0) continue
                if (!m.returnType.name.contains("SQLiteDatabase")) continue
                try {
                    m.isAccessible = true
                    val r = m.invoke(wrapper)
                    if (r != null) return r
                } catch (_: Throwable) {
                }
            }
            cls = c.superclass
        }
        return null
    }

    // ---------------- 查询会话（仅在收到刷新广播时执行） ----------------

    fun snapshot(): ConvSnapshot {
        val db = database
            ?: return ConvSnapshot(
                error = "微信数据库还没准备好。请先进入微信主界面后再点刷新。",
                timestamp = System.currentTimeMillis(),
            )

        val cursor = try {
            rawQuery(db, DexMap.SQL_CONVERSATIONS)
        } catch (t: Throwable) {
            L.e("$TAG rawQuery failed", t)
            return ConvSnapshot(
                error = "查询会话表失败：" + (t.message ?: t.javaClass.simpleName),
                timestamp = System.currentTimeMillis(),
            )
        } ?: return ConvSnapshot(error = "查询会话表返回了空游标", timestamp = System.currentTimeMillis())

        val items = ArrayList<ConvEntry>(64)
        try {
            if (cursor.columnCount < 3) {
                return ConvSnapshot(
                    error = "会话查询返回的列数异常（${cursor.columnCount}）",
                    timestamp = System.currentTimeMillis(),
                )
            }
            while (cursor.moveToNext()) {
                val username = if (cursor.isNull(0)) null else cursor.getString(0)
                if (username.isNullOrBlank()) continue
                val unread = if (cursor.isNull(1)) 0 else cursor.getInt(1)
                val nickname = if (cursor.isNull(2)) null else cursor.getString(2)
                val remark = if (cursor.columnCount > 3 && !cursor.isNull(3)) cursor.getString(3) else null
                val isGroup = username.endsWith("@chatroom")
                val name = remark?.takeIf { it.isNotBlank() }
                    ?: nickname?.takeIf { it.isNotBlank() }
                    ?: username
                items.add(
                    ConvEntry(
                        username = username,
                        name = name,
                        isGroup = isGroup,
                        unread = if (unread < 0) 0 else unread,
                        lastTime = 0L,
                    )
                )
            }
        } catch (t: Throwable) {
            L.e("$TAG read cursor failed", t)
        } finally {
            try {
                cursor.close()
            } catch (_: Throwable) {
            }
        }

        L.i("$TAG enumerated ${items.size} conversations")
        return ConvSnapshot(items = items, error = null, timestamp = System.currentTimeMillis())
    }

    private fun rawQuery(db: Any, sql: String): Cursor? {
        var cls: Class<*>? = db.javaClass
        while (true) {
            val c = cls ?: break
            if (c == Any::class.java) break
            for (m in c.declaredMethods) {
                if (m.name != "rawQuery") continue
                if (m.parameterCount != 2) continue
                val pt = m.parameterTypes
                if (pt[0] != String::class.java) continue
                if (!pt[1].isArray) continue
                if (pt[1].componentType != String::class.java && pt[1].componentType != Any::class.java) continue
                try {
                    m.isAccessible = true
                    val r = m.invoke(db, sql, null)
                    if (r is Cursor) return r
                } catch (t: Throwable) {
                    L.w("$TAG rawQuery(${pt[1].componentType?.simpleName}) threw: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            cls = c.superclass
        }
        throw NoSuchMethodException("rawQuery(String, String[]) not found on ${db.javaClass.name}")
    }

    // ---------------- 推给模块 App ----------------

    private fun pushSnapshot(ctx: Context) {
        val snap = snapshot()
        val values = ContentValues().apply {
            put(ConfigProvider.COLUMN_JSON, snap.toJson())
        }
        val uri = Uri.parse("content://${ConfigProvider.AUTHORITY}/${ConfigProvider.PATH_CONVERSATIONS}")
        try {
            val rows = ctx.contentResolver.update(uri, values, null, null)
            L.i("$TAG pushed ${snap.items.size} conversations (error=${snap.error}), rows=$rows")
        } catch (t: Throwable) {
            L.e("$TAG push to ${ConfigProvider.AUTHORITY} failed", t)
        }
    }

    // ---------------- 刷新入口 ----------------

    private fun registerRefreshReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                L.i("$TAG refresh requested")
                thread(name = "wxai-conv-query", isDaemon = true) {
                    try {
                        if (database == null) {
                            tryCaptureFromKernel()
                        }
                        pushSnapshot(ctx)
                    } catch (t: Throwable) {
                        L.e("$TAG refresh failed", t)
                    }
                }
            }
        }
        val filter = IntentFilter(ConversationProtocol.ACTION_LIST_CONVERSATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        L.i("$TAG refresh receiver registered for ${ConversationProtocol.ACTION_LIST_CONVERSATIONS}")
    }
}



