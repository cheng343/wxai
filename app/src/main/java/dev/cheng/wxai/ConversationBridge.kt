package dev.cheng.wxai

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

/**
 * 微信进程侧的会话枚举器（零 Hook 实现）。
 *
 * 1. 全程安装 **0 个 Xposed Hook**，绝不挂载 hm0.j1.u()，也不触碰 Legacy XposedHelpers；
 * 2. 通过三条无 Hook 通道获取 SQLiteDatabase 实例：
 *    a) 若开启了 WeKit，直接读取 dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi.INSTANCE.db；
 *    b) 当收到消息 / 会话写入时，从 insertWithOnConflict 的 thisObject 记录；
 *    c) 用户点击「刷新」时，若尚未拿到 DB，直接通过标准反射读取 hm0.j1 单例字段链；
 * 3. 只有当用户在 WXAI 模块界面点击「从微信选择会话 / 刷新」发出广播时，才在后台线程执行一次查询并回推。
 */
object ConversationBridge {

    private const val TAG = "WXAI/Conv"
    private const val SQLITE_INTERFACE = "s85.k0"

    @Volatile
    private var database: Any? = null

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var wekitClassLoader: ClassLoader? = null

    /** 微信 wxid -> 展示名（备注优先，其次昵称）缓存 */
    private val displayNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class WechatMessageRecord(
        val msgId: Long = 0L,
        val msgSvrId: Long = 0L,
        val senderWxid: String = "",
        val senderName: String,
        val isSelf: Boolean,
        val content: String,
        val type: Int,
        val createTimeMs: Long,
    )

    data class GlobalMessageHit(
        val talker: String,
        val talkerName: String,
        val senderName: String,
        val content: String,
        val createTimeMs: Long,
    )

    fun install(ctx: Context, cl: ClassLoader) {
        val app = ctx.applicationContext ?: ctx
        appClassLoader = cl

        try {
            registerRefreshReceiver(app)
        } catch (t: Throwable) {
            L.e("$TAG register receiver failed", t)
        }
    }

    fun setWeKitClassLoader(cl: ClassLoader) {
        wekitClassLoader = cl
        tryCaptureFromWeKit()
    }

    fun tryCaptureFromWeKit(): Boolean {
        if (database != null) return true
        val wkCl = wekitClassLoader ?: return false
        return try {
            val apiCls = Class.forName(
                "dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi",
                true,
                wkCl
            )
            val inst = apiCls.getDeclaredField("INSTANCE").apply {
                isAccessible = true
            }.get(null) ?: return false

            val dbField = apiCls.getDeclaredField("db").apply {
                isAccessible = true
            }
            val dbObj = dbField.get(inst)
            if (dbObj != null) {
                database = dbObj
                L.i("$TAG database captured from WeKit WeDatabaseApi: ${dbObj.javaClass.name}")
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun onSqliteDatabaseSeen(dbObj: Any) {
        if (database == null) {
            database = dbObj
            L.i("$TAG database captured from insertWithOnConflict: ${dbObj.javaClass.name}")
        }
    }

    // ---------------- 取 DB 句柄 ----------------

    private fun tryCaptureFromKernel(cl: ClassLoader? = appClassLoader): Boolean {
        if (database != null) return true
        if (tryCaptureFromWeKit()) return true
        val loader = cl ?: return false
        val coreStorage = getCoreStorageQuietly(loader) ?: return false
        tryCaptureQuietly(coreStorage)
        return database != null
    }

    private fun getCoreStorageQuietly(cl: ClassLoader): Any? {
        return try {
            val kernelCls = Class.forName(DexMap.MM_KERNEL, false, cl)
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
                displayNameCache[username] = name
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

    // ---------------- Agent 跨对话查询与联系人解析 ----------------

    private fun ensureDb(): Any? {
        database?.let { return it }
        tryCaptureFromKernel()
        return database
    }

    private fun sqlEscape(s: String): String = s.replace("'", "''")

    /**
     * 根据 wxid / chatroomId 获取展示名称（备注名优先，其次昵称，查不到返回原 id）。
     */
    fun getDisplayName(talker: String): String {
        val clean = talker.trim()
        if (clean.isEmpty()) return ""
        displayNameCache[clean]?.let { return it }

        val db = ensureDb() ?: return clean
        val esc = sqlEscape(clean)
        val sql = "SELECT conRemark, nickname FROM rcontact WHERE username = '$esc' LIMIT 1"
        try {
            rawQuery(db, sql)?.use { cur ->
                if (cur.moveToFirst()) {
                    val remark = if (!cur.isNull(0)) cur.getString(0) else null
                    val nick = if (cur.columnCount > 1 && !cur.isNull(1)) cur.getString(1) else null
                    val name = remark?.takeIf { it.isNotBlank() }
                        ?: nick?.takeIf { it.isNotBlank() }
                        ?: clean
                    displayNameCache[clean] = name
                    return name
                }
            }
        } catch (_: Throwable) {
        }
        return clean
    }

    /**
     * 按关键词（备注名、昵称、微信号 alias 或 username）搜索联系人或群聊。
     */
    fun findContacts(query: String, limit: Int = 15): List<ConvEntry> {
        val db = ensureDb() ?: return emptyList()
        val q = query.trim()
        val safeLimit = limit.coerceIn(1, 50)
        val sql = if (q.isEmpty()) {
            """
            SELECT c.username, c.unReadCount, r.nickname, r.conRemark
            FROM rconversation c
            LEFT JOIN rcontact r ON r.username = c.username
            ORDER BY c.conversationTime DESC
            LIMIT $safeLimit
            """.trimIndent()
        } else {
            val esc = sqlEscape(q)
            """
            SELECT r.username, 0, r.nickname, r.conRemark
            FROM rcontact r
            WHERE (r.conRemark LIKE '%$esc%' OR r.nickname LIKE '%$esc%' OR r.alias = '$esc' OR r.username = '$esc')
              AND r.username NOT LIKE 'gh_%'
              AND r.username NOT LIKE 'fake_%'
            LIMIT $safeLimit
            """.trimIndent()
        }

        val list = ArrayList<ConvEntry>()
        try {
            rawQuery(db, sql)?.use { cur ->
                while (cur.moveToNext()) {
                    val username = if (cur.isNull(0)) null else cur.getString(0)
                    if (username.isNullOrBlank()) continue
                    val unread = if (cur.columnCount > 1 && !cur.isNull(1)) cur.getInt(1) else 0
                    val nick = if (cur.columnCount > 2 && !cur.isNull(2)) cur.getString(2) else null
                    val remark = if (cur.columnCount > 3 && !cur.isNull(3)) cur.getString(3) else null
                    val name = remark?.takeIf { it.isNotBlank() }
                        ?: nick?.takeIf { it.isNotBlank() }
                        ?: username
                    displayNameCache[username] = name
                    list.add(
                        ConvEntry(
                            username = username,
                            name = name,
                            isGroup = username.endsWith("@chatroom"),
                            unread = unread.coerceAtLeast(0),
                            lastTime = 0L,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("$TAG findContacts($q) failed", t)
        }
        return list
    }

    /**
     * 将用户/AI 给出的目标标识（可能是 wxid_xxx、xxx@chatroom、备注名或昵称）解析为准确的微信 talker ID。
     */
    fun resolveTargetTalker(target: String): ConvEntry? {
        val clean = target.trim()
        if (clean.isEmpty()) return null

        // 1. 如果本身看起来就是 wxid / chatroom / filehelper，先直接查库验证
        if (clean.startsWith("wxid_") || clean.endsWith("@chatroom") || clean == "filehelper") {
            val display = getDisplayName(clean)
            return ConvEntry(
                username = clean,
                name = display,
                isGroup = clean.endsWith("@chatroom"),
                unread = 0,
                lastTime = 0L,
            )
        }

        // 2. 搜索匹配的联系人，优先精确匹配（备注完全一致 > 昵称完全一致 > 包含匹配）
        val candidates = findContacts(clean, limit = 20)
        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.username.equals(clean, ignoreCase = true) }?.let { return it }
        candidates.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        return candidates.first()
    }

    /**
     * 从微信底层 message 表读取指定会话（talker）最近的聊天记录（按时间正序返回）。
     */
    fun queryWechatMessages(talker: String, limit: Int = 20): List<WechatMessageRecord> {
        val db = ensureDb() ?: return emptyList()
        val cleanTalker = talker.trim()
        if (cleanTalker.isEmpty()) return emptyList()

        val safeLimit = limit.coerceIn(1, 50)
        val esc = sqlEscape(cleanTalker)
        val isGroup = cleanTalker.endsWith("@chatroom")
        val sql = """
            SELECT msgId, msgSvrId, isSend, type, content, createTime
            FROM message
            WHERE talker = '$esc'
            ORDER BY createTime DESC
            LIMIT $safeLimit
        """.trimIndent()

        val rawReversed = ArrayList<WechatMessageRecord>()
        try {
            rawQuery(db, sql)?.use { cur ->
                while (cur.moveToNext()) {
                    val msgId = if (cur.isNull(0)) 0L else cur.getLong(0)
                    val msgSvrId = if (cur.isNull(1)) 0L else cur.getLong(1)
                    val isSend = if (cur.isNull(2)) 0 else cur.getInt(2)
                    val type = if (cur.isNull(3)) 0 else cur.getInt(3)
                    val rawContent = if (cur.isNull(4)) "" else cur.getString(4).orEmpty()
                    val createTime = if (cur.isNull(5)) 0L else cur.getLong(5)
                    val timeMs = if (createTime in 1L..9_999_999_999L) createTime * 1000L else createTime

                    val isSelf = isSend != 0
                    var senderWxid = if (isSelf) "self" else cleanTalker
                    var sender = if (isSelf) "我" else getDisplayName(cleanTalker)
                    var body = rawContent

                    if (isGroup && !isSelf) {
                        val idx = rawContent.indexOf(":\n")
                        if (idx in 1..80) {
                            senderWxid = rawContent.substring(0, idx).trim()
                            sender = getDisplayName(senderWxid)
                            body = rawContent.substring(idx + 2).trim()
                        }
                    }

                    val readableBody = when (type) {
                        DexMap.TYPE_TEXT -> body.trim()
                        3 -> "[图片]"
                        34 -> "[语音]"
                        43 -> "[视频]"
                        47 -> "[表情包]"
                        10000 -> "[系统提示: ${body.take(40)}]"
                        else -> if (body.startsWith("<")) "[富媒体/卡片消息]" else body.take(120)
                    }

                    if (readableBody.isNotEmpty()) {
                        rawReversed.add(
                            WechatMessageRecord(
                                msgId = msgId,
                                msgSvrId = msgSvrId,
                                senderWxid = senderWxid,
                                senderName = sender,
                                isSelf = isSelf,
                                content = readableBody,
                                type = type,
                                createTimeMs = timeMs,
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            L.e("$TAG queryWechatMessages($cleanTalker) failed", t)
        }
        return rawReversed.asReversed()
    }

    /**
     * 获取指定群聊（@chatroom）的所有成员列表（含 wxid、昵称/备注）。
     */
    fun getGroupMembers(groupTalker: String, limit: Int = 60): List<ConvEntry> {
        val db = ensureDb() ?: return emptyList()
        val clean = groupTalker.trim()
        if (!clean.endsWith("@chatroom")) return emptyList()

        val esc = sqlEscape(clean)
        var memberListStr = ""
        try {
            rawQuery(db, "SELECT memberlist FROM chatroom WHERE chatroomname = '$esc' LIMIT 1")?.use { cur ->
                if (cur.moveToFirst() && !cur.isNull(0)) {
                    memberListStr = cur.getString(0).orEmpty()
                }
            }
        } catch (t: Throwable) {
            L.w("$TAG getGroupMembers($clean) failed: ${t.message}")
        }
        if (memberListStr.isBlank()) return emptyList()

        val wxids = memberListStr.split(';', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(limit.coerceIn(1, 150))

        return wxids.map { wxid ->
            ConvEntry(
                username = wxid,
                name = getDisplayName(wxid),
                isGroup = false,
                unread = 0,
                lastTime = 0L,
            )
        }
    }

    /**
     * 在微信全量历史消息表 (message) 中按关键词检索消息记录（跨所有私聊与群聊）。
     */
    fun searchGlobalMessages(keyword: String, limit: Int = 20): List<GlobalMessageHit> {
        val db = ensureDb() ?: return emptyList()
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()

        val safeLimit = limit.coerceIn(1, 40)
        val esc = sqlEscape(q)
        val sql = """
            SELECT talker, isSend, content, createTime
            FROM message
            WHERE type = ${DexMap.TYPE_TEXT}
              AND content LIKE '%$esc%'
            ORDER BY createTime DESC
            LIMIT $safeLimit
        """.trimIndent()

        val hits = ArrayList<GlobalMessageHit>()
        try {
            rawQuery(db, sql)?.use { cur ->
                while (cur.moveToNext()) {
                    val talker = if (cur.isNull(0)) "" else cur.getString(0).orEmpty()
                    if (talker.isEmpty()) continue
                    val isSend = if (cur.isNull(1)) 0 else cur.getInt(1)
                    val rawContent = if (cur.isNull(2)) "" else cur.getString(2).orEmpty()
                    val createTime = if (cur.isNull(3)) 0L else cur.getLong(3)
                    val timeMs = if (createTime in 1L..9_999_999_999L) createTime * 1000L else createTime

                    val talkerName = getDisplayName(talker)
                    val isGroup = talker.endsWith("@chatroom")
                    val isSelf = isSend != 0
                    var sender = if (isSelf) "我" else talkerName
                    var body = rawContent

                    if (isGroup && !isSelf) {
                        val idx = rawContent.indexOf(":\n")
                        if (idx in 1..80) {
                            val senderWxid = rawContent.substring(0, idx).trim()
                            sender = getDisplayName(senderWxid)
                            body = rawContent.substring(idx + 2).trim()
                        }
                    }

                    hits.add(
                        GlobalMessageHit(
                            talker = talker,
                            talkerName = talkerName,
                            senderName = sender,
                            content = body.trim().take(160),
                            createTimeMs = timeMs,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("$TAG searchGlobalMessages($q) failed", t)
        }
        return hits
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
        pushLifeSnapshot(ctx)
    }

    fun pushLifeSnapshot(ctx: Context) {
        try {
            val lifeJson = SessionManager.exportLifeSnapshotJson(ctx)
            val values = ContentValues().apply {
                put(ConfigProvider.COLUMN_JSON, lifeJson)
            }
            val uri = Uri.parse("content://${ConfigProvider.AUTHORITY}/${ConfigProvider.PATH_LIFE_SNAPSHOT}")
            ctx.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            L.w("$TAG pushLifeSnapshot skipped: ${t.message}")
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
