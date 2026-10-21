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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

/**
 * 微信进程侧的会话枚举器。
 *
 * 为什么必须跑在微信进程里：会话表 EnMicroMsg.db 是 wcdb 加密库，
 * 明文句柄只有微信自己手里有。WeKit 的做法是拦 MMKernel.getStorage() 拿到 CoreStorage，
 * 再从中取出 SqliteDBWrapper 上返回 SQLiteDatabase 的方法 —— 这里照抄同一套反射路径
 * （8.0.76 的混淆名来自 WeKit 的 Dex-Test 报告，见 DexMap）。
 *
 * 拿到句柄后执行一条 JOIN 查询，把结果 JSON 通过 ConfigProvider 推给模块 App，
 * App 侧只负责展示和勾选，从不接触微信数据。
 */
object ConversationBridge {

    private const val TAG = "WXAI/Conv"

    /** 等待微信内核初始化的轮询：40 次 × 3s = 2 分钟 */
    private const val WAIT_TRIES = 40
    private const val WAIT_INTERVAL_MS = 3_000L

    @Volatile
    private var database: Any? = null

    @Volatile
    private var storage: Any? = null

    fun install(ctx: Context, cl: ClassLoader) {
        // 注意：这里【不】hook MMKernel.u()。
        // u() 是微信启动期间被高频调用的内核 getter，挂全局 after-hook 属于高风险改动；
        // 反正拿句柄只需要一次，用后台轮询即可（最多晚 3 秒）。

        try {
            registerRefreshReceiver(ctx)
        } catch (t: Throwable) {
            L.e("$TAG register receiver failed", t)
        }

        // 启动时 MMKernel 往往还没初始化完，后台轮询到拿到句柄为止
        thread(name = "wxai-conv-init", isDaemon = true) {
            awaitKernel(ctx, cl)
        }
    }

    // ---------------- 取 DB 句柄 ----------------

    /** 主动轮询 mmkernel.u()，直到返回非 null（微信启动早期会抛"未初始化"）。 */
    private fun awaitKernel(ctx: Context, cl: ClassLoader) {
        for (i in 1..WAIT_TRIES) {
            if (database != null) {
                // 句柄已就绪：补一次推送，让 App 打开时就有数据
                pushSnapshot(ctx)
                return
            }
            val obj = try {
                val kernel = XposedHelpers.findClass(DexMap.MM_KERNEL, cl)
                XposedHelpers.callStaticMethod(kernel, DexMap.MM_KERNEL_GET_STORAGE)
            } catch (t: Throwable) {
                null
            }
            if (obj != null) {
                tryCapture(ctx, obj)
                if (database != null) return
            }
            SystemClock.sleep(WAIT_INTERVAL_MS)
        }
        L.w("$TAG kernel not ready after ${WAIT_TRIES * WAIT_INTERVAL_MS / 1000}s, conversation list unavailable")
    }

    private fun tryCapture(ctx: Context, storageObj: Any) {
        if (database != null) return
        synchronized(this) {
            if (database != null) return
            storage = storageObj
            val handle = findDatabaseHandle(storageObj)
            if (handle == null) {
                L.w("$TAG no SQLiteDatabase handle found in ${storageObj.javaClass.name}")
                return
            }
            database = handle
            L.i("$TAG database ready: ${handle.javaClass.name}")
        }
        // 首次拿到句柄，主动推一次（用户可能压根没点过刷新）
        pushSnapshot(ctx)
    }

    /**
     * 从 CoreStorage 里挖 wcdb 句柄。
     *
     * 先按 WeKit 的路径：找类型为 SqliteDBWrapper 的字段，再调它上面"无参 + 返回 SQLiteDatabase"的方法。
     * 找不到就直接扫所有字段（不依赖混淆名），最后再试试 storage 自己。
     */
    private fun findDatabaseHandle(storageObj: Any): Any? {
        // 1) 优先：声明类型名等于 ka5.b0 的字段
        run {
            var cls: Class<*>? = storageObj.javaClass
            while (true) {
                val c = cls ?: break
                if (c == Any::class.java) break
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val typeName = f.type.name
                    if (typeName != DexMap.SQLITE_WRAPPER) continue
                    val value = try {
                        f.isAccessible = true
                        f.get(storageObj)
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    callDatabaseAccessor(value)?.let {
                        L.i("$TAG db handle via field ${c.name}#${f.name}")
                        return it
                    }
                }
                cls = c.superclass
            }
        }

        // 2) 兜底：扫所有字段，谁身上有无参返回 SQLiteDatabase 的方法就用谁
        run {
            var cls: Class<*>? = storageObj.javaClass
            while (true) {
                val c = cls ?: break
                if (c == Any::class.java) break
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val value = try {
                        f.isAccessible = true
                        f.get(storageObj)
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    callDatabaseAccessor(value)?.let {
                        L.i("$TAG db handle via scanned field ${c.name}#${f.name} (${value.javaClass.name})")
                        return it
                    }
                }
                cls = c.superclass
            }
        }

        // 3) storage 自己就带访问器
        return callDatabaseAccessor(storageObj)
    }

    /** 在 owner 的类层级里找 无参 且 返回类型含 "SQLiteDatabase" 的方法并调用。 */
    private fun callDatabaseAccessor(owner: Any): Any? {
        var cls: Class<*>? = owner.javaClass
        while (true) {
            val c = cls ?: break
            if (c == Any::class.java) break
            for (m in c.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 0) continue
                if (!m.returnType.name.contains("SQLiteDatabase")) continue
                try {
                    m.isAccessible = true
                    val r = m.invoke(owner)
                    if (r != null) {
                        L.i("$TAG accessor ${c.name}#${m.name}(): ${m.returnType.name}")
                        return r
                    }
                } catch (t: Throwable) {
                    L.w("$TAG accessor ${c.name}#${m.name} threw: ${t.javaClass.simpleName}: ${t.message}")
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


