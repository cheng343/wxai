package dev.cheng.wxai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject

/**
 * 模块本体核心大脑服务（运行在 dev.cheng.wxai 进程中）：
 * 1. 宿主是独立的 App，拥有安全的私有沙盒目录，微信无法越权访问；
 * 2. 承载 SessionManager 的 SQLite 数据库（wxai_memory.db）；
 * 3. 承载 ReplyEngine 与 AiClient（大模型 HTTPS 请求全从本进程发出，微信 0 AI 外网流量）；
 * 4. 通过 Binder 接收来自微信探针的消息事件，并通过 WechatProbeProxy 下发操控指令。
 */
class WxAiBrainService : Service() {

    companion object {
        private const val TAG = "WxAiBrainService"
        private const val CHANNEL_ID = "wxai_brain_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var probeProxy: BrainProtocol.WechatProbeProxy? = null

        fun getProbe(): BrainProtocol.WechatProbeProxy? = probeProxy

        fun isProbeConnected(): Boolean = probeProxy != null
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                BrainProtocol.CODE_REGISTER_PROBE -> {
                    val remoteProbe = data.readStrongBinder()
                    if (remoteProbe != null) {
                        probeProxy = BrainProtocol.WechatProbeProxy(remoteProbe)
                        Log.i(TAG, "WeChat probe connected and registered successfully")
                        remoteProbe.linkToDeath({
                            Log.w(TAG, "WeChat probe disconnected (process died or unhooked)")
                            probeProxy = null
                        }, 0)
                        reply?.writeInt(1)
                    } else {
                        reply?.writeInt(0)
                    }
                    return true
                }

                BrainProtocol.CODE_ON_INCOMING_MESSAGE -> {
                    val jsonStr = data.readString().orEmpty()
                    if (jsonStr.isNotEmpty()) {
                        handleIncomingJson(jsonStr)
                        reply?.writeInt(1)
                    } else {
                        reply?.writeInt(0)
                    }
                    return true
                }

                BrainProtocol.CODE_PING -> {
                    reply?.writeInt(1)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WxAiBrainService onCreate")
        startForegroundSafely()
        // 预热模块本地的数据库与配置引擎
        try {
            ConfigBridge.ensureLoadedOnWorker(applicationContext)
            SessionManager.initInModuleProcess(applicationContext)
            ReplyEngine.startInModuleProcess(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "init failed in module process", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind from callingUid=${Binder.getCallingUid()}")
        return binder
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "wxai 核心大脑服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持 wxai 本地记忆与 AI 推理服务在后台稳定运行"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("wxai 运行中")
                .setContentText("独立大脑运行中，微信端 0 落盘 0 流量")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .build()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.w(TAG, "startForeground skipped: ${t.message}")
            }
        }
    }

    private fun handleIncomingJson(jsonStr: String) {
        try {
            val o = JSONObject(jsonStr)
            val talker = o.optString("talker", "")
            val rawContent = o.optString("content", "")
            val type = o.optInt("type", 1)
            val imgPath = o.optString("imgPath", "").takeIf { it.isNotEmpty() }
            val msgId = o.optLong("msgId", 0L)
            val svrId = o.optLong("msgSvrId", 0L)
            val createTime = o.optLong("createTime", System.currentTimeMillis())
            val isSend = o.optInt("isSend", 0)

            ReplyEngine.dispatchIncomingFromProbe(
                ctx = applicationContext,
                talker = talker,
                type = type,
                rawContent = rawContent,
                imgPath = imgPath,
                msgId = msgId,
                svrId = svrId,
                createTime = createTime,
                isSend = isSend,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "handleIncomingJson error", t)
        }
    }
}
