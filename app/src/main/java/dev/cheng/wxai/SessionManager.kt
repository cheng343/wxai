package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地持久化会话管理器（SessionManager）与三层记忆存储（SQLite）：
 *
 * 1. 短期工作记忆（session_messages 表）：
 *    按会话 talker 隔离存储多轮对话（user / assistant），支持滑窗截断与超时自动分割；
 * 2. 长期事实与跨会话记忆（long_term_memories 表）：
 *    存储联系人画像、偏好、重要事件及全局记忆（scope = "global" 或具体 talker/人名），
 *    重启微信或手机永不丢失。
 */
object SessionManager {

    private const val DB_NAME = "wxai_memory.db"
    private const val DB_VERSION = 1

    private const val TABLE_MESSAGES = "session_messages"
    private const val TABLE_MEMORIES = "long_term_memories"

    @Volatile
    private var dbHelper: Helper? = null

    private class Helper(ctx: Context) : SQLiteOpenHelper(
        ctx.applicationContext ?: ctx,
        DB_NAME,
        null,
        DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    talker TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_msg_talker_time ON $TABLE_MESSAGES(talker, timestamp)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope TEXT NOT NULL,
                    fact TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_mem_scope ON $TABLE_MEMORIES(scope, timestamp)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        var h = dbHelper
        if (h == null) {
            h = Helper(ctx)
            dbHelper = h
        }
        return h.writableDatabase
    }

    // ---------------------------------------------------------------- 短期多轮会话管理

    /**
     * 获取指定会话的多轮上下文消息（按时间升序，直接可塞给 AiClient）。
     *
     * @param maxTurns 最大保留轮数（1 轮 = 1 user + 1 assistant，故最多取 maxTurns * 2 条）
     * @param expireMinutes 会话超时时间（分钟）。若距离上一条消息超过该时长，则只取未超时的消息段（0 表示永不过期）
     */
    fun getHistory(
        ctx: Context,
        talker: String,
        maxTurns: Int,
        expireMinutes: Int,
    ): List<AiClient.ChatMessage> {
        if (maxTurns <= 0) return emptyList()
        val limit = (maxTurns * 2).coerceIn(2, 100)
        val rawList = ArrayList<Pair<AiClient.ChatMessage, Long>>(limit)

        try {
            val database = db(ctx)
            database.rawQuery(
                """
                SELECT role, content, timestamp
                FROM $TABLE_MESSAGES
                WHERE talker = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(talker, limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val role = cur.getString(0) ?: continue
                    val content = cur.getString(1) ?: continue
                    val ts = cur.getLong(2)
                    rawList.add(AiClient.ChatMessage(role = role, content = content) to ts)
                }
            }
        } catch (t: Throwable) {
            L.e("SessionManager.getHistory failed for $talker", t)
            return emptyList()
        }

        if (rawList.isEmpty()) return emptyList()

        // rawList 当前是按时间倒序（最新在前），检查相邻消息是否超过会话过期阈值
        val expireMs = if (expireMinutes > 0) expireMinutes * 60_000L else 0L
        val validReversed = ArrayList<AiClient.ChatMessage>(rawList.size)
        var prevTs = System.currentTimeMillis()

        for ((msg, ts) in rawList) {
            if (expireMs > 0L && (prevTs - ts) > expireMs) {
                break
            }
            validReversed.add(msg)
            prevTs = ts
        }

        // 反转为时间正序，并确保第一条是 user（避免半截 assistant 开头导致部分模型报错）
        val chronological = validReversed.asReversed().toMutableList()
        while (chronological.isNotEmpty() && chronological.first().role != "user") {
            chronological.removeAt(0)
        }
        return chronological
    }

    /**
     * 追加一条会话记录，并自动清理该会话过老的历史记录（保留最近 120 条）。
     */
    fun appendMessage(
        ctx: Context,
        talker: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        if (talker.isBlank() || content.isBlank()) return
        try {
            val database = db(ctx)
            val cv = ContentValues().apply {
                put("talker", talker)
                put("role", role)
                put("content", content)
                put("timestamp", timestamp)
            }
            database.insert(TABLE_MESSAGES, null, cv)

            // 定期裁剪单会话超出 120 条的陈旧记录
            database.execSQL(
                """
                DELETE FROM $TABLE_MESSAGES
                WHERE talker = ? AND id NOT IN (
                    SELECT id FROM $TABLE_MESSAGES
                    WHERE talker = ?
                    ORDER BY id DESC
                    LIMIT 120
                )
                """.trimIndent(),
                arrayOf(talker, talker)
            )
        } catch (t: Throwable) {
            L.e("SessionManager.appendMessage failed for $talker", t)
        }
    }

    /**
     * 清空指定会话的短期上下文历史（例如收到 #重置会话 指令时调用）。
     */
    fun clearSession(ctx: Context, talker: String): Int {
        return try {
            db(ctx).delete(TABLE_MESSAGES, "talker = ?", arrayOf(talker))
        } catch (t: Throwable) {
            L.e("SessionManager.clearSession failed for $talker", t)
            0
        }
    }

    // ---------------------------------------------------------------- 长期事实与跨会话记忆

    data class MemoryItem(
        val id: Long,
        val scope: String,
        val fact: String,
        val timestamp: Long,
    )

    /**
     * 写入一条长期记忆。
     * @param scope 记忆归属对象：可以是 "global"（全局通用记忆），也可以是联系人名字/备注或 talker wxid
     * @param fact 记忆的具体事实描述
     */
    fun saveMemory(ctx: Context, scope: String, fact: String): Boolean {
        val cleanScope = scope.trim().ifEmpty { "global" }
        val cleanFact = fact.trim()
        if (cleanFact.isEmpty()) return false
        return try {
            val database = db(ctx)
            // 防止完全重复的记忆
            database.rawQuery(
                "SELECT id FROM $TABLE_MEMORIES WHERE scope = ? AND fact = ? LIMIT 1",
                arrayOf(cleanScope, cleanFact)
            ).use { cur ->
                if (cur.moveToFirst()) {
                    val existingId = cur.getLong(0)
                    val cv = ContentValues().apply {
                        put("timestamp", System.currentTimeMillis())
                    }
                    database.update(TABLE_MEMORIES, cv, "id = ?", arrayOf(existingId.toString()))
                    return true
                }
            }

            val cv = ContentValues().apply {
                put("scope", cleanScope)
                put("fact", cleanFact)
                put("timestamp", System.currentTimeMillis())
            }
            database.insert(TABLE_MEMORIES, null, cv) > 0
        } catch (t: Throwable) {
            L.e("SessionManager.saveMemory failed", t)
            false
        }
    }

    /**
     * 查询与当前对话者相关的长期记忆 + 全局记忆。
     */
    fun getRelevantMemories(
        ctx: Context,
        talker: String,
        displayName: String?,
        limit: Int = 25,
    ): List<MemoryItem> {
        val result = ArrayList<MemoryItem>()
        try {
            val database = db(ctx)
            val hasName = !displayName.isNullOrBlank() && displayName != talker
            val sql = if (hasName) {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope = 'global' OR scope = ? OR scope = ? OR fact LIKE ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope = 'global' OR scope = ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent()
            }
            val args = if (hasName) {
                arrayOf(talker, displayName!!, "%$displayName%", limit.toString())
            } else {
                arrayOf(talker, limit.toString())
            }
            database.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    result.add(
                        MemoryItem(
                            id = cur.getLong(0),
                            scope = cur.getString(1).orEmpty(),
                            fact = cur.getString(2).orEmpty(),
                            timestamp = cur.getLong(3),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("SessionManager.getRelevantMemories failed", t)
        }
        return result
    }

    /**
     * 按关键词搜索全局所有长期记忆（供 Agent 工具 recall_memories 调用）。
     */
    fun searchMemories(ctx: Context, query: String, limit: Int = 30): List<MemoryItem> {
        val result = ArrayList<MemoryItem>()
        try {
            val database = db(ctx)
            val q = query.trim()
            val (sql, args) = if (q.isEmpty() || q == "all" || q == "全部") {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent() to arrayOf(limit.toString())
            } else {
                """
                SELECT id, scope, fact, timestamp
                FROM $TABLE_MEMORIES
                WHERE scope LIKE ? OR fact LIKE ?
                ORDER BY timestamp DESC
                LIMIT ?
                """.trimIndent() to arrayOf("%$q%", "%$q%", limit.toString())
            }
            database.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    result.add(
                        MemoryItem(
                            id = cur.getLong(0),
                            scope = cur.getString(1).orEmpty(),
                            fact = cur.getString(2).orEmpty(),
                            timestamp = cur.getLong(3),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("SessionManager.searchMemories failed", t)
        }
        return result
    }

    /**
     * 跨会话搜索本地保存的近期 AI 聊天记录（供 Agent 工具跨会话检索）。
     */
    fun searchLocalChatHistory(ctx: Context, talker: String, limit: Int = 20): List<AiClient.ChatMessage> {
        val list = ArrayList<AiClient.ChatMessage>()
        try {
            val database = db(ctx)
            database.rawQuery(
                """
                SELECT role, content, timestamp
                FROM $TABLE_MESSAGES
                WHERE talker = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(talker, limit.coerceIn(1, 50).toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val role = cur.getString(0) ?: continue
                    val content = cur.getString(1) ?: continue
                    list.add(AiClient.ChatMessage(role = role, content = content))
                }
            }
        } catch (t: Throwable) {
            L.e("SessionManager.searchLocalChatHistory failed", t)
        }
        return list.asReversed()
    }
}
