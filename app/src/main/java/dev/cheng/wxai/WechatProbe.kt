package dev.cheng.wxai

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 微信进程内部纯执行探针（WechatProbe）：
 * 1. 微信端绝对零落盘（启动时物理清理所有残留历史文件，不再建立任何数据库或配置）；
 * 2. 微信端 0 自定义 Hook（收消息挂接 WeKit WeDatabaseListenerApi，发消息走 WeKit WeMessageApi 官方队列）；
 * 3. 微信端 0 AI 网络连接（收到消息通过标准 Binder 上报给模块本体 BrainService，不发往任何外网）；
 * 4. 充当模块本体的执行触手，受控执行发消息、查记录等 12 项指令。
 */
object WechatProbe {

    private const val TAG = "WechatProbe"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var brainProxy: BrainProtocol.BrainServiceProxy? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingOutgoingMessages = ConcurrentLinkedQueue<String>()

    /**
     * 暴露给模块本体的 IWechatProbe Binder 实体
     */
    private val probeBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                BrainProtocol.CODE_PROBE_SEND_TEXT -> {
                    val talker = data.readString().orEmpty()
                    val text = data.readString().orEmpty()
                    val isMultiBubble = data.readInt() != 0
                    val quoteSvrId = data.readLong()
                    val ok = MessageSender.sendHumanLikeText(talker, text, isMultiBubble, quoteSvrId)
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }

                BrainProtocol.CODE_PROBE_SEND_PAT -> {
                    val talker = data.readString().orEmpty()
                    val patTarget = data.readString().orEmpty()
                    val ok = MessageSender.sendPat(talker, patTarget)
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }

                BrainProtocol.CODE_PROBE_SEND_CARD -> {
                    val toUser = data.readString().orEmpty()
                    val cardWxId = data.readString().orEmpty()
                    val ok = MessageSender.sendShareCard(toUser, cardWxId)
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }

                BrainProtocol.CODE_PROBE_POST_MOMENT -> {
                    val content = data.readString().orEmpty()
                    val ok = MessageSender.postMomentText(content)
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }

                BrainProtocol.CODE_PROBE_REVOKE_MSG -> {
                    val msgId = data.readLong()
                    val ok = MessageSender.revokeMessage(msgId)
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }

                BrainProtocol.CODE_PROBE_QUERY_MESSAGES -> {
                    val talker = data.readString().orEmpty()
                    val limit = data.readInt()
                    val records = ConversationBridge.queryWechatMessages(talker, limit)
                    val arr = JSONArray()
                    for (r in records) {
                        arr.put(
                            JSONObject().apply {
                                put("msgId", r.msgId)
                                put("svrId", r.msgSvrId)
                                put("senderWxid", r.senderWxid)
                                put("senderName", r.senderName)
                                put("content", r.content)
                                put("createTimeMs", r.createTimeMs)
                                put("isSelf", r.isSelf)
                                put("type", r.type)
                            }
                        )
                    }
                    reply?.writeString(arr.toString())
                    return true
                }

                BrainProtocol.CODE_PROBE_QUERY_CONTACTS -> {
                    val keyword = data.readString().orEmpty()
                    val contacts = ConversationBridge.findContacts(keyword, 25)
                    val arr = JSONArray()
                    for (c in contacts) {
                        arr.put(
                            JSONObject().apply {
                                put("username", c.username)
                                put("name", c.name)
                                put("isGroup", c.isGroup)
                                put("unread", c.unread)
                                put("lastTime", c.lastTime)
                            }
                        )
                    }
                    reply?.writeString(arr.toString())
                    return true
                }

                BrainProtocol.CODE_PROBE_QUERY_GROUP_MEMBERS -> {
                    val groupTarget = data.readString().orEmpty()
                    val members = ConversationBridge.getGroupMembers(groupTarget, 60)
                    val arr = JSONArray()
                    for (m in members) {
                        arr.put(
                            JSONObject().apply {
                                put("username", m.username)
                                put("name", m.name)
                            }
                        )
                    }
                    reply?.writeString(arr.toString())
                    return true
                }

                BrainProtocol.CODE_PROBE_SEARCH_GLOBAL -> {
                    val keyword = data.readString().orEmpty()
                    val limit = data.readInt()
                    val hits = ConversationBridge.searchGlobalMessages(keyword, limit)
                    val arr = JSONArray()
                    for (h in hits) {
                        arr.put(
                            JSONObject().apply {
                                put("talker", h.talker)
                                put("talkerDisplayName", h.talkerName)
                                put("senderName", h.senderName)
                                put("content", h.content)
                                put("createTimeMs", h.createTimeMs)
                            }
                        )
                    }
                    reply?.writeString(arr.toString())
                    return true
                }

                BrainProtocol.CODE_PROBE_GET_SELF_INFO -> {
                    val info = JSONObject().apply {
                        put("selfWxid", ConversationBridge.getSelfWxid())
                        put("selfNickname", ConversationBridge.getSelfNickname())
                    }
                    reply?.writeString(info.toString())
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val proxy = BrainProtocol.BrainServiceProxy(service)
            brainProxy = proxy
            L.i("$TAG connected to WxAiBrainService, registering probe...")
            val ok = proxy.registerProbe(probeBinder)
            L.i("$TAG probe registered result: $ok")
            // 冲刷连接前暂存的消息
            while (pendingOutgoingMessages.isNotEmpty()) {
                val item = pendingOutgoingMessages.poll() ?: break
                proxy.onIncomingMessage(item)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            L.w("$TAG disconnected from WxAiBrainService")
            brainProxy = null
            // 延迟 3 秒尝试自动重连
            mainHandler.postDelayed({ connectToBrainService() }, 3000L)
        }
    }

    /**
     * 探针初始化入口
     */
    fun init(ctx: Context, cl: ClassLoader) {
        appContext = ctx.applicationContext ?: ctx
        // 1. 彻底清除微信私有目录下所有历史残留文件（保证 100% 绝对零落盘）
        purgeLegacyFiles(ctx)

        // 2. 初始化桥接工具（用于执行操控命令）
        MessageSender.init(cl)
        ConversationBridge.install(ctx, cl)

        // 3. 建立与模块本体 BrainService 的跨进程连接
        connectToBrainService()
    }

    fun setWeKitClassLoader(cl: ClassLoader) {
        MessageSender.setWeKitClassLoader(cl)
        ConversationBridge.setWeKitClassLoader(cl)
        ImageBridge.setWeKitClassLoader(cl)
    }

    /**
     * 收到微信消息时分发（由 WeDatabaseListenerApi 回调或 Hooks SQLite 回调入口驱动）
     */
    fun onMessageInsertedValues(values: ContentValues) {
        val talker = values.getAsString("talker")?.trim().orEmpty()
        if (talker.isEmpty()) return

        val type = values.getAsInteger("type") ?: return
        val isSys = (type == 10000)
        if (type != DexMap.TYPE_TEXT && type != 822083633 && type != 3 && type != 47 && !isSys) return

        val rawContent = values.getAsString("content").orEmpty()
        val imgPath = values.getAsString("imgPath")
        if (rawContent.isEmpty() && imgPath.isNullOrEmpty()) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val svrId = values.getAsLong("msgSvrId") ?: 0L
        val createTime = values.getAsLong("createTime") ?: System.currentTimeMillis()
        val isSend = values.getAsInteger("isSend") ?: 0

        val json = JSONObject().apply {
            put("talker", talker)
            put("content", rawContent)
            put("type", type)
            put("imgPath", imgPath.orEmpty())
            put("msgId", msgId)
            put("msgSvrId", svrId)
            put("createTime", createTime)
            put("isSend", isSend)
        }.toString()

        val proxy = brainProxy
        if (proxy != null) {
            proxy.onIncomingMessage(json)
        } else {
            // 如果与模块本体尚未连通，缓存最近 20 条，并立刻发起重连
            if (pendingOutgoingMessages.size < 20) {
                pendingOutgoingMessages.offer(json)
            }
            connectToBrainService()
        }
    }

    private fun connectToBrainService() {
        val ctx = appContext ?: return
        try {
            val intent = Intent().apply {
                component = ComponentName("dev.cheng.wxai", "dev.cheng.wxai.WxAiBrainService")
            }
            val bound = ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            L.i("$TAG bind WxAiBrainService requested, result=$bound")
        } catch (t: Throwable) {
            L.w("$TAG bind WxAiBrainService failed: ${t.message}")
        }
    }

    /**
     * 强力自扫并销毁微信内部历史版本留下的所有痕迹文件，达到物理级绝对纯净
     */
    private fun purgeLegacyFiles(ctx: Context) {
        try {
            val app = ctx.applicationContext ?: ctx
            val filesDir = app.filesDir
            if (filesDir != null && filesDir.exists()) {
                File(filesDir, ".avatar_cache_meta.db").delete()
                File(filesDir, ".avatar_cache_meta.db-wal").delete()
                File(filesDir, ".avatar_cache_meta.db-shm").delete()
                File(filesDir, ".avatar_cache_meta.db-journal").delete()
            }
            val spDir = File(app.applicationInfo.dataDir, "shared_prefs")
            if (spDir.exists()) {
                File(spDir, ".mm_turns_cache.xml").delete()
                File(spDir, ".mm_sync_prefs.xml").delete()
                File(spDir, "wxai_mirror_config.xml").delete()
                File(spDir, "wxai_session_turns.xml").delete()
            }
            val dbDir = File(app.applicationInfo.dataDir, "databases")
            if (dbDir.exists()) {
                File(dbDir, "wxai_memory.db").delete()
                File(dbDir, "wxai_memory.db-wal").delete()
                File(dbDir, "wxai_memory.db-shm").delete()
                File(dbDir, "wxai_memory.db-journal").delete()
            }
            L.i("$TAG legacy files in WeChat data directory purged cleanly")
        } catch (_: Throwable) {
        }
    }
}
