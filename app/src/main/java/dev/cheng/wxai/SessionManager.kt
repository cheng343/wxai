package dev.cheng.wxai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 数字生命记忆与人格状态引擎（SQLite v2）：
 *
 * 五大持久化表：
 * 1. session_messages   —— 按会话隔离的多轮工作记忆（支持滑窗截断与超时分割）
 * 2. self_state         —— 她的实时心境、精力值(0~100)、当前生活状态与内心独白
 * 3. contact_dossiers   —— 社交关系图谱（对每个联系人的称呼定位、好感度 0~100、性格与喜好画像）
 * 4. life_diary         —— 她的第一人称人生日记与岁月编年史
 * 5. long_term_memories —— 跨会话永久事实记忆碎片
 */
object SessionManager {

    private const val STEALTH_DB_NAME = ".avatar_cache_meta.db"
    private const val STEALTH_SP_TURNS = ".mm_turns_cache"
    private const val DB_VERSION = 4
    private const val TABLE_SUMMARY = "session_summaries"

    private const val TABLE_MESSAGES = "session_messages"
    private const val TABLE_MEMORIES = "long_term_memories"
    private const val TABLE_SELF_STATE = "self_state"
    private const val TABLE_DOSSIERS = "contact_dossiers"
    private const val TABLE_DIARY = "life_diary"

    @Volatile
    private var dbHelper: Helper? = null

    private fun getDbPath(ctx: Context): String =
        java.io.File((ctx.applicationContext ?: ctx).filesDir, STEALTH_DB_NAME).absolutePath

    /** 记录每个会话距离上次后台反思/写日记新增的对话轮数（使用持久化存储，进程重启不丢失） */
    fun getUnreflectedTurns(ctx: Context, talker: String): Int {
        val sp = ctx.getSharedPreferences(STEALTH_SP_TURNS, Context.MODE_PRIVATE)
        return sp.getInt("t_$talker", 0)
    }

    fun incrementUnreflectedTurns(ctx: Context, talker: String): Int {
        val sp = ctx.getSharedPreferences(STEALTH_SP_TURNS, Context.MODE_PRIVATE)
        val next = sp.getInt("t_$talker", 0) + 1
        sp.edit().putInt("t_$talker", next).apply()
        return next
    }

    fun resetReflectionCounter(ctx: Context, talker: String) {
        val sp = ctx.getSharedPreferences(STEALTH_SP_TURNS, Context.MODE_PRIVATE)
        sp.edit().putInt("t_$talker", 0).apply()
    }

    /**
     * 分块滚动窗口起点锚定（talker -> 起始 message id）：
     * 避免传统逐条滑窗每轮丢弃第1条消息导致整个 messages 数组前移、从而引发 DeepSeek Prefix KV 缓存 100% 失效。
     * 只有当当前锚点之后的消息数超过 (targetCount + stepSize) 时，才一次性向前推进一个 stepSize 块。
     */
    private val windowAnchorId = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------- 数据模型

    data class SelfState(
        val mood: String = "元气满满",
        val energy: Int = 85,
        val activity: String = defaultActivityForHour(),
        val monologue: String = "今天会遇到什么有趣的人和故事呢？",
        val updatedAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("mood", mood)
            put("energy", energy)
            put("activity", activity)
            put("monologue", monologue)
            put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject?): SelfState {
                if (o == null) return SelfState()
                return SelfState(
                    mood = o.optString("mood", "元气满满"),
                    energy = o.optInt("energy", 85).coerceIn(0, 100),
                    activity = o.optString("activity", defaultActivityForHour()),
                    monologue = o.optString("monologue", ""),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
            }
        }
    }

    data class ContactDossier(
        val talker: String,
        val displayName: String,
        val relationship: String = "普通朋友",
        val affection: Int = 55,
        val profileSummary: String = "",
        val updatedAt: Long = 0L,
    ) {
        fun affectionLabel(): String = when {
            affection >= 90 -> "挚爱/死忠 ($affection/100)"
            affection >= 75 -> "亲密信赖 ($affection/100)"
            affection >= 60 -> "聊得来 ($affection/100)"
            affection >= 40 -> "熟悉点头 ($affection/100)"
            affection >= 20 -> "冷淡疏远 ($affection/100)"
            else -> "厌恶警惕 ($affection/100)"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("talker", talker)
            put("displayName", displayName)
            put("relationship", relationship)
            put("affection", affection)
            put("profileSummary", profileSummary)
            put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): ContactDossier = ContactDossier(
                talker = o.optString("talker", ""),
                displayName = o.optString("displayName", ""),
                relationship = o.optString("relationship", "普通朋友"),
                affection = o.optInt("affection", 55).coerceIn(0, 100),
                profileSummary = o.optString("profileSummary", ""),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }

    data class DiaryItem(
        val id: Long,
        val mood: String,
        val relatedPerson: String,
        val entry: String,
        val timestamp: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("mood", mood)
            put("relatedPerson", relatedPerson)
            put("entry", entry)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(o: JSONObject): DiaryItem = DiaryItem(
                id = o.optLong("id", 0L),
                mood = o.optString("mood", ""),
                relatedPerson = o.optString("relatedPerson", ""),
                entry = o.optString("entry", ""),
                timestamp = o.optLong("timestamp", 0L),
            )
        }
    }

    data class MemoryItem(
        val id: Long,
        val scope: String,
        val fact: String,
        val timestamp: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("scope", scope)
            put("fact", fact)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(o: JSONObject): MemoryItem = MemoryItem(
                id = o.optLong("id", 0L),
                scope = o.optString("scope", "global"),
                fact = o.optString("fact", ""),
                timestamp = o.optLong("timestamp", 0L),
            )
        }
    }

    data class LifeSnapshot(
        val selfState: SelfState = SelfState(),
        val dossiers: List<ContactDossier> = emptyList(),
        val diaries: List<DiaryItem> = emptyList(),
        val memories: List<MemoryItem> = emptyList(),
        val totalMessagesCount: Int = 0,
        val timestamp: Long = 0L,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("selfState", selfState.toJson())
            put("dossiers", JSONArray().apply { dossiers.forEach { put(it.toJson()) } })
            put("diaries", JSONArray().apply { diaries.forEach { put(it.toJson()) } })
            put("memories", JSONArray().apply { memories.forEach { put(it.toJson()) } })
            put("totalMessagesCount", totalMessagesCount)
            put("timestamp", timestamp)
        }.toString()

        companion object {
            val EMPTY = LifeSnapshot()

            fun fromJson(raw: String?): LifeSnapshot {
                if (raw.isNullOrBlank()) return EMPTY
                return try {
                    val o = JSONObject(raw)
                    val dossiers = ArrayList<ContactDossier>()
                    o.optJSONArray("dossiers")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { dossiers.add(ContactDossier.fromJson(it)) }
                        }
                    }
                    val diaries = ArrayList<DiaryItem>()
                    o.optJSONArray("diaries")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { diaries.add(DiaryItem.fromJson(it)) }
                        }
                    }
                    val memories = ArrayList<MemoryItem>()
                    o.optJSONArray("memories")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { memories.add(MemoryItem.fromJson(it)) }
                        }
                    }
                    LifeSnapshot(
                        selfState = SelfState.fromJson(o.optJSONObject("selfState")),
                        dossiers = dossiers,
                        diaries = diaries,
                        memories = memories,
                        totalMessagesCount = o.optInt("totalMessagesCount", 0),
                        timestamp = o.optLong("timestamp", 0L),
                    )
                } catch (_: Throwable) {
                    EMPTY
                }
            }
        }
    }

    // ---------------------------------------------------------------- SQLite OpenHelper

    private class Helper(ctx: Context) : SQLiteOpenHelper(
        ctx.applicationContext ?: ctx,
        getDbPath(ctx),
        null,
        DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            createAllTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            createAllTables(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            createAllTables(db)
        }

        private fun createAllTables(db: SQLiteDatabase) {
            try {
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
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_talker_time ON $TABLE_MESSAGES(talker, timestamp)")

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
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_mem_scope ON $TABLE_MEMORIES(scope, timestamp)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_SELF_STATE (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        mood TEXT NOT NULL,
                        energy INTEGER NOT NULL,
                        activity TEXT NOT NULL,
                        monologue TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_DOSSIERS (
                        talker TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        relationship TEXT NOT NULL,
                        affection INTEGER NOT NULL,
                        profile_summary TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_DIARY (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        mood TEXT NOT NULL,
                        related_person TEXT NOT NULL,
                        entry TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_diary_time ON $TABLE_DIARY(timestamp DESC)")

                // 滚动摘要：滑出上下文窗口的旧对话被压缩为一段摘要，保证记忆可以无限累计而不丢失
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_SUMMARY (
                        talker TEXT PRIMARY KEY,
                        summary TEXT NOT NULL,
                        upto_id INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            } catch (t: Throwable) {
                L.e("createAllTables failed", t)
            }
        }
    }

    @Volatile
    private var storageMigrated = false

    private fun ensureStorageMigrated(ctx: Context) {
        if (storageMigrated) return
        storageMigrated = true
        val app = ctx.applicationContext ?: ctx
        try {
            val oldDb = app.getDatabasePath("wxai_memory.db")
            val newDb = java.io.File(getDbPath(app))
            newDb.parentFile?.mkdirs()
            if (oldDb.exists() && !newDb.exists()) {
                oldDb.copyTo(newDb, overwrite = true)
            }
            if (oldDb.exists()) {
                oldDb.delete()
                app.getDatabasePath("wxai_memory.db-wal").delete()
                app.getDatabasePath("wxai_memory.db-shm").delete()
                app.getDatabasePath("wxai_memory.db-journal").delete()
            }
            // 清理旧版暴露的 shared_prefs
            val spDir = java.io.File(app.applicationInfo.dataDir, "shared_prefs")
            if (spDir.exists()) {
                java.io.File(spDir, "wxai_session_turns.xml").delete()
                java.io.File(spDir, "wxai_mirror_config.xml").delete()
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        ensureStorageMigrated(ctx)
        var h = dbHelper
        if (h == null) {
            h = Helper(ctx)
            dbHelper = h
        }
        return h.writableDatabase
    }

    private fun defaultActivityForHour(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "深夜蜷在被窝里熬夜玩手机"
            in 6..8 -> "刚睡醒迷迷糊糊伸懒腰、准备吃早饭"
            in 9..11 -> "上午精神饱满地忙活手头的事"
            in 12..13 -> "刚吃完午饭，正在午休放空刷手机"
            in 14..17 -> "下午一边喝奶茶一边摸鱼聊天"
            in 18..20 -> "傍晚忙完放松下来，吃晚饭听歌"
            else -> "洗完澡舒舒服服窝在沙发上看手机"
        }
    }

    // ---------------------------------------------------------------- 自我心境与作息 (SelfState)

    fun getSelfState(ctx: Context): SelfState {
        return try {
            val database = db(ctx)
            database.rawQuery(
                "SELECT mood, energy, activity, monologue, updated_at FROM $TABLE_SELF_STATE WHERE id = 1",
                null
            ).use { cur ->
                if (cur.moveToFirst()) {
                    val mood = cur.getString(0).orEmpty().ifEmpty { "愉快放松" }
                    val energy = cur.getInt(1).coerceIn(0, 100)
                    val savedActivity = cur.getString(2).orEmpty()
                    val monologue = cur.getString(3).orEmpty()
                    val updatedAt = cur.getLong(4)
                    // 如果距离上次状态更新已超过 2 小时，自动切回符合当前时段的自然生活作息
                    val now = System.currentTimeMillis()
                    val activity = if (now - updatedAt > 2 * 3_600_000L || savedActivity.isBlank()) {
                        defaultActivityForHour()
                    } else {
                        savedActivity
                    }
                    SelfState(mood, energy, activity, monologue, updatedAt)
                } else {
                    SelfState()
                }
            }
        } catch (t: Throwable) {
            L.e("getSelfState failed", t)
            SelfState()
        }
    }

    fun updateSelfState(
        ctx: Context,
        mood: String?,
        energyDelta: Int = 0,
        activity: String?,
        monologue: String?,
    ): SelfState {
        val current = getSelfState(ctx)
        val newMood = mood?.trim()?.takeIf { it.isNotEmpty() } ?: current.mood
        val newEnergy = (current.energy + energyDelta).coerceIn(10, 100)
        val newActivity = activity?.trim()?.takeIf { it.isNotEmpty() } ?: current.activity
        val newMonologue = monologue?.trim()?.takeIf { it.isNotEmpty() } ?: current.monologue
        val now = System.currentTimeMillis()

        try {
            val database = db(ctx)
            val cv = ContentValues().apply {
                put("id", 1)
                put("mood", newMood)
                put("energy", newEnergy)
                put("activity", newActivity)
                put("monologue", newMonologue)
                put("updated_at", now)
            }
            database.insertWithOnConflict(TABLE_SELF_STATE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            L.e("updateSelfState failed", t)
        }
        return SelfState(newMood, newEnergy, newActivity, newMonologue, now)
    }

    // ---------------------------------------------------------------- 社交人物档案与好感度 (ContactDossier)

    fun getDossier(ctx: Context, talker: String, fallbackName: String = talker): ContactDossier {
        val clean = talker.trim()
        if (clean.isEmpty()) return ContactDossier("", fallbackName)
        return try {
            val database = db(ctx)
            database.rawQuery(
                "SELECT display_name, relationship, affection, profile_summary, updated_at FROM $TABLE_DOSSIERS WHERE talker = ? LIMIT 1",
                arrayOf(clean)
            ).use { cur ->
                if (cur.moveToFirst()) {
                    ContactDossier(
                        talker = clean,
                        displayName = cur.getString(0).orEmpty().ifEmpty { fallbackName },
                        relationship = cur.getString(1).orEmpty().ifEmpty { "朋友" },
                        affection = cur.getInt(2).coerceIn(0, 100),
                        profileSummary = cur.getString(3).orEmpty(),
                        updatedAt = cur.getLong(4),
                    )
                } else {
                    ContactDossier(
                        talker = clean,
                        displayName = fallbackName,
                        relationship = if (clean.endsWith("@chatroom")) "微信群聊" else "认识的朋友",
                        affection = 55,
                        profileSummary = "",
                        updatedAt = 0L,
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("getDossier failed", t)
            ContactDossier(clean, fallbackName)
        }
    }

    fun updateDossier(
        ctx: Context,
        talker: String,
        displayName: String,
        relationship: String? = null,
        affectionDelta: Int = 0,
        profileNotes: String? = null,
    ): ContactDossier {
        val clean = talker.trim()
        val current = getDossier(ctx, clean, displayName)
        val newName = displayName.trim().ifEmpty { current.displayName }
        val newRel = relationship?.trim()?.takeIf { it.isNotEmpty() } ?: current.relationship
        val newAffection = (current.affection + affectionDelta).coerceIn(0, 100)
        val newSummary = when {
            profileNotes.isNullOrBlank() -> current.profileSummary
            current.profileSummary.isBlank() -> profileNotes.trim()
            current.profileSummary.contains(profileNotes.trim()) -> current.profileSummary
            else -> (current.profileSummary + "；" + profileNotes.trim()).takeLast(500)
        }
        val now = System.currentTimeMillis()

        try {
            val database = db(ctx)
            val cv = ContentValues().apply {
                put("talker", clean)
                put("display_name", newName)
                put("relationship", newRel)
                put("affection", newAffection)
                put("profile_summary", newSummary)
                put("updated_at", now)
            }
            database.insertWithOnConflict(TABLE_DOSSIERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            L.e("updateDossier failed", t)
        }
        return ContactDossier(clean, newName, newRel, newAffection, newSummary, now)
    }

    fun listAllDossiers(ctx: Context, limit: Int = 40): List<ContactDossier> {
        val list = ArrayList<ContactDossier>()
        try {
            db(ctx).rawQuery(
                "SELECT talker, display_name, relationship, affection, profile_summary, updated_at FROM $TABLE_DOSSIERS ORDER BY updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    list.add(
                        ContactDossier(
                            talker = cur.getString(0).orEmpty(),
                            displayName = cur.getString(1).orEmpty(),
                            relationship = cur.getString(2).orEmpty(),
                            affection = cur.getInt(3).coerceIn(0, 100),
                            profileSummary = cur.getString(4).orEmpty(),
                            updatedAt = cur.getLong(5),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("listAllDossiers failed", t)
        }
        return list
    }

    // ---------------------------------------------------------------- 第一人称人生日记 (LifeDiary)

    fun writeDiary(
        ctx: Context,
        mood: String,
        relatedPerson: String,
        entry: String,
    ): Boolean {
        val cleanEntry = entry.trim()
        if (cleanEntry.isEmpty()) return false
        return try {
            val database = db(ctx)
            val cv = ContentValues().apply {
                put("mood", mood.trim().ifEmpty { "平静" })
                put("related_person", relatedPerson.trim())
                put("entry", cleanEntry)
                put("timestamp", System.currentTimeMillis())
            }
            database.insert(TABLE_DIARY, null, cv) > 0
        } catch (t: Throwable) {
            L.e("writeDiary failed", t)
            false
        }
    }

    fun getRecentDiaries(ctx: Context, limit: Int = 15): List<DiaryItem> {
        val list = ArrayList<DiaryItem>()
        try {
            db(ctx).rawQuery(
                "SELECT id, mood, related_person, entry, timestamp FROM $TABLE_DIARY ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    list.add(
                        DiaryItem(
                            id = cur.getLong(0),
                            mood = cur.getString(1).orEmpty(),
                            relatedPerson = cur.getString(2).orEmpty(),
                            entry = cur.getString(3).orEmpty(),
                            timestamp = cur.getLong(4),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            L.e("getRecentDiaries failed", t)
        }
        return list
    }

    // ---------------------------------------------------------------- 多轮会话管理（Prefix KV Cache 锚定优化）

    private data class StoredRow(
        val id: Long,
        val msg: AiClient.ChatMessage,
        val ts: Long,
    )

    fun getHistory(
        ctx: Context,
        talker: String,
        maxTurns: Int,
        expireMinutes: Int,
        useAnchor: Boolean = false,
    ): List<AiClient.ChatMessage> {
        if (maxTurns <= 0) return emptyList()
        val targetCount = (maxTurns * 2).coerceIn(4, 1000)
        val stepSize = (targetCount / 5).coerceIn(10, 80)
        val queryLimit = targetCount + stepSize
        val rowsReversed = ArrayList<StoredRow>(queryLimit)

        try {
            val database = db(ctx)
            database.rawQuery(
                """
                SELECT id, role, content, timestamp
                FROM $TABLE_MESSAGES
                WHERE talker = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(talker, queryLimit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val role = cur.getString(1) ?: continue
                    val content = cur.getString(2) ?: continue
                    val ts = cur.getLong(3)
                    rowsReversed.add(StoredRow(id, AiClient.ChatMessage(role = role, content = content), ts))
                }
            }
        } catch (t: Throwable) {
            L.e("SessionManager.getHistory failed for $talker", t)
            return emptyList()
        }

        if (rowsReversed.isEmpty()) return emptyList()

        val expireMs = if (expireMinutes > 0) expireMinutes * 60_000L else 0L
        val validReversed = ArrayList<StoredRow>(rowsReversed.size)
        var prevTs = System.currentTimeMillis()

        for (row in rowsReversed) {
            if (expireMs > 0L && (prevTs - row.ts) > expireMs) {
                break
            }
            validReversed.add(row)
            prevTs = row.ts
        }

        val chronological = validReversed.asReversed().toMutableList()
        while (chronological.isNotEmpty() && chronological.first().msg.role != "user") {
            chronological.removeAt(0)
        }
        if (chronological.isEmpty()) return emptyList()

        // 分块锚定逻辑：保证在 [targetCount .. targetCount + stepSize] 区间内，首条消息 ID 恒定不变，实现 100% 追加式前缀缓存命中
        // 注意：只有主对话上下文（useAnchor=true）才允许读写锚点；反思/指令等小窗口查询绝不能篡改锚点，
        // 否则会把主上下文错误截断为 8 轮（旧版“上下文只剩十几条、记忆攒不起来”的根因）。
        if (!useAnchor) {
            val start = (chronological.size - targetCount).coerceAtLeast(0)
            var s = start
            while (s < chronological.size && chronological[s].msg.role != "user") s++
            return if (s < chronological.size) chronological.subList(s, chronological.size).map { it.msg } else emptyList()
        }

        val currentAnchor = windowAnchorId[talker]
        val anchorIdx = if (currentAnchor != null) {
            chronological.indexOfFirst { it.id >= currentAnchor }
        } else {
            -1
        }
        // 锚点已滑出查询窗口（窗口首条 id 大于锚点，且查询结果已经达到上限）=> 需要向前推进一个块
        val anchorOverflow = anchorIdx == 0 && currentAnchor != null &&
            chronological.first().id > currentAnchor && rowsReversed.size >= queryLimit

        val effectiveStartIdx = if (anchorIdx >= 0 && !anchorOverflow) {
            anchorIdx
        } else {
            // 首次初始化锚点，或当前窗口已超出高水位线 (targetCount + stepSize)：向前裁切一个块并固定新锚点
            var newStart = (chronological.size - targetCount).coerceAtLeast(0)
            while (newStart < chronological.size && chronological[newStart].msg.role != "user") {
                newStart++
            }
            if (newStart < chronological.size) {
                windowAnchorId[talker] = chronological[newStart].id
            }
            newStart
        }

        val anchoredSlice = if (effectiveStartIdx in 0 until chronological.size) {
            chronological.subList(effectiveStartIdx, chronological.size)
        } else {
            chronological
        }
        anchoredSlice.firstOrNull()?.let { contextStartId[talker] = it.id }
        return anchoredSlice.map { it.msg }
    }

    /** 主上下文窗口当前首条消息 id（早于它的消息已滑出窗口，需要压缩进滚动摘要） */
    private val contextStartId = ConcurrentHashMap<String, Long>()

    fun getContextStartId(talker: String): Long = contextStartId[talker] ?: 0L

    // ---------------------------------------------------------------- 滚动摘要（长期对话记忆无限累计）

    fun getSummary(ctx: Context, talker: String): Pair<String, Long> {
        return try {
            db(ctx).rawQuery(
                "SELECT summary, upto_id FROM $TABLE_SUMMARY WHERE talker = ? LIMIT 1",
                arrayOf(talker)
            ).use { cur ->
                if (cur.moveToFirst()) cur.getString(0).orEmpty() to cur.getLong(1) else "" to 0L
            }
        } catch (t: Throwable) {
            L.e("getSummary failed", t)
            "" to 0L
        }
    }

    fun saveSummary(ctx: Context, talker: String, summary: String, uptoId: Long) {
        try {
            val cv = ContentValues().apply {
                put("talker", talker)
                put("summary", summary.trim())
                put("upto_id", uptoId)
                put("updated_at", System.currentTimeMillis())
            }
            db(ctx).insertWithOnConflict(TABLE_SUMMARY, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            L.e("saveSummary failed", t)
        }
    }

    /** 读取 (afterId, beforeId) 区间内的消息，用于压缩成摘要 */
    fun getMessagesBetween(
        ctx: Context,
        talker: String,
        afterId: Long,
        beforeId: Long,
        limit: Int = 160,
    ): List<Pair<Long, AiClient.ChatMessage>> {
        val list = ArrayList<Pair<Long, AiClient.ChatMessage>>()
        try {
            db(ctx).rawQuery(
                "SELECT id, role, content FROM $TABLE_MESSAGES WHERE talker = ? AND id > ? AND id < ? ORDER BY id ASC LIMIT ?",
                arrayOf(talker, afterId.toString(), beforeId.toString(), limit.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    val role = cur.getString(1) ?: continue
                    val content = cur.getString(2) ?: continue
                    list.add(cur.getLong(0) to AiClient.ChatMessage(role = role, content = content))
                }
            }
        } catch (t: Throwable) {
            L.e("getMessagesBetween failed", t)
        }
        return list
    }

    fun appendMessage(
        ctx: Context,
        talker: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Int {
        if (talker.isBlank() || content.isBlank()) return 0
        try {
            val database = db(ctx)
            val cv = ContentValues().apply {
                put("talker", talker)
                put("role", role)
                put("content", content)
                put("timestamp", timestamp)
            }
            database.insert(TABLE_MESSAGES, null, cv)

            database.execSQL(
                """
                DELETE FROM $TABLE_MESSAGES
                WHERE talker = ? AND id NOT IN (
                    SELECT id FROM $TABLE_MESSAGES
                    WHERE talker = ?
                    ORDER BY id DESC
                    LIMIT 2000
                )
                """.trimIndent(),
                arrayOf(talker, talker)
            )
        } catch (t: Throwable) {
            L.e("SessionManager.appendMessage failed for $talker", t)
        }
        if (role == "assistant") {
            return incrementUnreflectedTurns(ctx, talker)
        }
        return getUnreflectedTurns(ctx, talker)
    }

    fun clearSession(ctx: Context, talker: String): Int {
        resetReflectionCounter(ctx, talker)
        windowAnchorId.remove(talker)
        contextStartId.remove(talker)
        return try {
            runCatching { db(ctx).delete(TABLE_SUMMARY, "talker = ?", arrayOf(talker)) }
            db(ctx).delete(TABLE_MESSAGES, "talker = ?", arrayOf(talker))
        } catch (t: Throwable) {
            L.e("SessionManager.clearSession failed for $talker", t)
            0
        }
    }

    // ---------------------------------------------------------------- 长期事实与跨会话记忆

    fun saveMemory(ctx: Context, scope: String, fact: String): Boolean {
        val cleanScope = scope.trim().ifEmpty { "global" }
        val cleanFact = fact.trim()
        if (cleanFact.isEmpty()) return false
        return try {
            val database = db(ctx)
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

    /**
     * 导出数字生命完整状态快照 JSON（供微信进程通过 ConfigProvider 推给模块 App 展示）。
     */
    fun exportLifeSnapshotJson(ctx: Context): String {
        val totalMsgs = try {
            db(ctx).rawQuery("SELECT COUNT(*) FROM $TABLE_MESSAGES", null).use { cur ->
                if (cur.moveToFirst()) cur.getInt(0) else 0
            }
        } catch (_: Throwable) {
            0
        }
        val snap = LifeSnapshot(
            selfState = getSelfState(ctx),
            dossiers = listAllDossiers(ctx, limit = 30),
            diaries = getRecentDiaries(ctx, limit = 20),
            memories = searchMemories(ctx, "all", limit = 40),
            totalMessagesCount = totalMsgs,
            timestamp = System.currentTimeMillis(),
        )
        return snap.toJson()
    }
}
