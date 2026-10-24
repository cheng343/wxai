package dev.cheng.wxai

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

/**
 * 微信进程侧的会话枚举器。
 *
 * 8.0.76 (3141) 逆向验证结构：
 * - MMKernel (hm0.j1) 静态字段 m 持有 hm0.j1 单例，其实例字段 d 持有 CoreStorage (hm0.b0)
 *   （直接反射读字段可避免调 hm0.j1.u() 在未登录/早期阶段触发 Lp06/a; 断言异常）
 * - CoreStorage (hm0.b0) 的字段 `public s85.k0 f` 以及 MsgInfoStorage (com.tencent.mm.storage.f9)
 *   的字段 `public final s85.k0 r`，其声明类型为接口 `s85.k0`，运行时类型均为 `ka5.b0` (SqliteDBWrapper)
 * - `ka5.b0.P()`（内部调用 `this.b.e()`）返回 `com.tencent.wcdb.database.SQLiteDatabase`
 */
object ConversationBridge {

    private const val TAG = "WXAI/Conv"
    private const val SQLITE_INTERFACE = "s85.k0"

    /** 等待微信内核初始化的轮询：60 次 × 3s = 3 分钟 */
    private const val WAIT_TRIES = 60
    private const val WAIT_INTERVAL_MS = 3_000L

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

        thread(name = "wxai-conv-init", isDaemon = true) {
            awaitKernel(app, cl)
        }
    }

    /**
     * 当 Hooks 拦截到 f9.I9 插入消息时调用：f9.r 同样持有 ka5.b0 数据库包装器，可作为双保险捕获通道。
     */
    fun onStorageSeen(ctx: Context, storageObj: Any) {
        if (database != null) return
        val app = ctx.applicationContext ?: ctx
        thread(name = "wxai-conv-capture", isDaemon = true) {
            tryCapture(app, storageObj)
        }
    }

    // ---------------- 取 DB 句柄 ----------------

    private fun awaitKernel(ctx: Context, cl: ClassLoader) {
        for (i in 1..WAIT_TRIES) {
            if (database != null) {
                pushSnapshot(ctx)
                return
            }
            if (tryCaptureFromKernel(ctx, cl)) {
                return
            }
            SystemClock.sleep(WAIT_INTERVAL_MS)
        }
        L.w("$TAG kernel not ready after ${WAIT_TRIES * WAIT_INTERVAL_MS / 1000}s")
    }

    private fun tryCaptureFromKernel(ctx: Context, cl: ClassLoader? = appClassLoader): Boolean {
        if (database != null) return true
        val loader = cl ?: return false
        val coreStorage = getCoreStorageQuietly(loader) ?: return false
        tryCapture(ctx, coreStorage)
        return database != null
    }

    /**
     * 静默通过静态字段读取 hm0.j1.m -> d (hm0.b0)，不调用会抛 AssertionFailedError 的 hm0.j1.u()。
     */
    private fun getCoreStorageQuietly(cl: ClassLoader): Any? {
        return try {
            val kernelCls = XposedHelpers.findClass(DexMap.MM_KERNEL, cl)
            // 1) 找 hm0.j1 自身类型的静态单例字段（8.0.76 为 m）
            val kernelInst = kernelCls.declaredFields.firstNotNullOfOrNull { f ->
                if (!Modifier.isStatic(f.modifiers) || f.type != kernelCls) return@firstNotNullOfOrNull null
                try {
                    f.isAccessible = true
                    f.get(null)
                } catch (_: Throwable) {
                    null
                }
            } ?: return null

            // 2) 在 hm0.j1 实例上找 hm0.b0 (CoreStorage) 字段（8.0.76 为 d）
            for (f in kernelCls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.name == "d" || f.type.name == "hm0.b0") {
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

    private fun tryCapture(ctx: Context, storageObj: Any) {
        if (database != null) return
        var capturedNow = false
        synchronized(this) {
            if (database == null) {
                val handle = findDatabaseHandle(storageObj)
                if (handle != null) {
                    database = handle
                    capturedNow = true
                    L.i("$TAG database ready: ${handle.javaClass.name}")
                }
            }
        }
        if (capturedNow) {
            pushSnapshot(ctx)
        }
    }

    /**
     * 从 CoreStorage (hm0.b0) 或 MsgInfoStorage (f9) 里提取 SQLiteDatabase：
     * 检查运行时类型为 ka5.b0 或声明类型为 s85.k0 的字段，再调用其返回 SQLiteDatabase 的无参方法 P()。
     */
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
                    L.i("$TAG direct SQLiteDatabase field ${c.name}#${f.name}")
                    return value
                }
                if (runtimeName == DexMap.SQLITE_WRAPPER ||
                    declaredName == DexMap.SQLITE_WRAPPER ||
                    declaredName == SQLITE_INTERFACE
                ) {
                    val db = callDatabaseAccessor(value)
                    if (db != null) {
                        L.i("$TAG db handle via ${c.name}#${f.name} (declared=$declaredName, runtime=$runtimeName)")
                        return db
                    }
                }
            }
            cls = c.superclass
        }
        return null
    }

    /** 在 ka5.b0 的类层级里找 无参 且 返回类型含 "SQLiteDatabase" 的方法并调用。 */
    private fun callDatabaseAccessor(wrapper: Any): Any? {
        // 先检查 ka5.b0.b (底层 ka5.f) 是否已初始化，避免在未打开时调用 P() 触发 NPE
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
                    if (r != null) {
                        L.i("$TAG accessor ${c.name}#${m.name}(): ${m.returnType.name}")
                        return r
                    }
                } catch (_: Throwable) {
                }
            }
            cls = c.superclass
        }
        return null
    }

    // ---------------- 查询会话 ----------------

    fun snapshot(): ConvSnapshot {
        val db = database
            ?: return ConvSnapshot(
                error = "微信数据库还没准备好。请保持微信在前台几秒后重试（若一直失败请重启微信）。",
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
            // 按位置取列：SQL 是我们自己写的，顺序固定，比按列名匹配更抗混淆
            // 0=username 1=unReadCount 2=nickname 3=conRemark 4=type
            if (cursor.columnCount < 3) {
                return ConvSnapshot(
                    error = "会话查询返回的列数异常（${cursor.columnCount}），可能是一版微信改了表结构",
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
                // 优先 (String, String[])，其次 (String, Object[])
                if (pt[1].componentType != String::class.java && pt[1].componentType != Any::class.java) continue
                try {
                    m.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
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
                            tryCaptureFromKernel(ctx)
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


