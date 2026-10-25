package dev.cheng.wxai

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject

/**
 * wxai 纯净分离架构跨进程 Binder 通信契约：
 *
 * 进程边界：
 * 1. 微信进程 (com.tencent.mm) ── 运行 WechatProbe (纯执行探针，0 文件落盘，0 AI 网络连接)
 * 2. 模块本体 (dev.cheng.wxai)  ── 运行 WxAiBrainService (核心大脑，掌管所有 SQLite 记忆与 AI 推理)
 *
 * 两个 Binder 接口：
 * - IBrainService：由 WxAiBrainService 实现，供微信端上报收到消息
 * - IWechatProbe：由微信端 WechatProbe 实现，供模块端下发发消息、查记录等操控指令
 */
object BrainProtocol {

    const val SERVICE_ACTION = "dev.cheng.wxai.action.BIND_BRAIN"

    // IBrainService transaction codes
    const val CODE_REGISTER_PROBE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CODE_ON_INCOMING_MESSAGE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val CODE_PING = IBinder.FIRST_CALL_TRANSACTION + 3

    // IWechatProbe transaction codes
    const val CODE_PROBE_SEND_TEXT = IBinder.FIRST_CALL_TRANSACTION + 10
    const val CODE_PROBE_SEND_PAT = IBinder.FIRST_CALL_TRANSACTION + 11
    const val CODE_PROBE_SEND_CARD = IBinder.FIRST_CALL_TRANSACTION + 12
    const val CODE_PROBE_POST_MOMENT = IBinder.FIRST_CALL_TRANSACTION + 13
    const val CODE_PROBE_REVOKE_MSG = IBinder.FIRST_CALL_TRANSACTION + 14
    const val CODE_PROBE_QUERY_MESSAGES = IBinder.FIRST_CALL_TRANSACTION + 15
    const val CODE_PROBE_QUERY_CONTACTS = IBinder.FIRST_CALL_TRANSACTION + 16
    const val CODE_PROBE_QUERY_GROUP_MEMBERS = IBinder.FIRST_CALL_TRANSACTION + 17
    const val CODE_PROBE_SEARCH_GLOBAL = IBinder.FIRST_CALL_TRANSACTION + 18
    const val CODE_PROBE_GET_SELF_INFO = IBinder.FIRST_CALL_TRANSACTION + 19
    const val CODE_PROBE_QUERY_SQL = IBinder.FIRST_CALL_TRANSACTION + 20

    /**
     * 微信端持有：向模块本体 Brain 调用的 Proxy
     */
    class BrainServiceProxy(private val remote: IBinder) {
        fun registerProbe(probeBinder: IBinder): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeStrongBinder(probeBinder)
                remote.transact(CODE_REGISTER_PROBE, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun onIncomingMessage(jsonStr: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(jsonStr)
                remote.transact(CODE_ON_INCOMING_MESSAGE, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun ping(): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                remote.transact(CODE_PING, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    /**
     * 模块本体持有：向微信探针下达操作指令的 Proxy
     */
    class WechatProbeProxy(private val remote: IBinder) {
        fun sendText(talker: String, text: String, isMultiBubble: Boolean, quoteSvrId: Long): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(talker)
                data.writeString(text)
                data.writeInt(if (isMultiBubble) 1 else 0)
                data.writeLong(quoteSvrId)
                remote.transact(CODE_PROBE_SEND_TEXT, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun sendPat(talker: String, patTarget: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(talker)
                data.writeString(patTarget)
                remote.transact(CODE_PROBE_SEND_PAT, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun sendCard(toUser: String, cardWxId: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(toUser)
                data.writeString(cardWxId)
                remote.transact(CODE_PROBE_SEND_CARD, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun postMoment(content: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(content)
                remote.transact(CODE_PROBE_POST_MOMENT, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun revokeMsg(msgId: Long): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeLong(msgId)
                remote.transact(CODE_PROBE_REVOKE_MSG, data, reply, 0)
                reply.readInt() != 0
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun queryMessages(talker: String, limit: Int): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(talker)
                data.writeInt(limit)
                remote.transact(CODE_PROBE_QUERY_MESSAGES, data, reply, 0)
                reply.readString().orEmpty()
            } catch (_: Throwable) {
                "[]"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun queryContacts(keyword: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(keyword)
                remote.transact(CODE_PROBE_QUERY_CONTACTS, data, reply, 0)
                reply.readString().orEmpty()
            } catch (_: Throwable) {
                "[]"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun queryGroupMembers(groupTarget: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(groupTarget)
                remote.transact(CODE_PROBE_QUERY_GROUP_MEMBERS, data, reply, 0)
                reply.readString().orEmpty()
            } catch (_: Throwable) {
                "[]"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun searchGlobalMessages(keyword: String, limit: Int): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeString(keyword)
                data.writeInt(limit)
                remote.transact(CODE_PROBE_SEARCH_GLOBAL, data, reply, 0)
                reply.readString().orEmpty()
            } catch (_: Throwable) {
                "[]"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun getSelfInfo(): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                remote.transact(CODE_PROBE_GET_SELF_INFO, data, reply, 0)
                reply.readString().orEmpty()
            } catch (_: Throwable) {
                "{}"
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
