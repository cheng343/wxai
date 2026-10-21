package dev.cheng.wxai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话列表的跨进程数据契约。
 *
 * 生产端：微信进程里的 ConversationBridge（读 rconversation + rcontact）
 * 消费端：模块 App 里的 ConversationClient / 设置界面的会话选择器
 *
 * 传递路径：
 *   微信进程 → ContentProvider.update(content://dev.cheng.wxai.config/conversations)
 *            → App 进程 SharedPreferences("wxai_config")."conversations_json"
 *   用户在界面上点刷新 → App sendBroadcast(ACTION_LIST_CONVERSATIONS, pkg=com.tencent.mm)
 *            → 微信进程动态注册的 receiver → 重新查询 → 再写回 Provider
 *
 * 这个文件不允许引用 de.robv.* / Xposed 类，两个进程都会加载它。
 */
data class ConvEntry(
    /** 会话 id：好友是 wxid_xxx，群是 xxxxx@chatroom */
    val username: String,
    /** 展示名：备注 > 昵称 > id */
    val name: String,
    val isGroup: Boolean,
    /** 未读条数（含免打扰未读） */
    val unread: Int,
    /** 最后一条消息时间（毫秒），0 表示未知 */
    val lastTime: Long,
)

data class ConvSnapshot(
    val items: List<ConvEntry> = emptyList(),
    /** 非 null 表示这次读取失败的原因，界面要显示给用户 */
    val error: String? = null,
    /** 生产时间戳（毫秒）。0 表示从未成功获取过 */
    val timestamp: Long = 0L,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("ts", timestamp)
        root.put("error", error ?: JSONObject.NULL)
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("u", it.username)
                    put("n", it.name)
                    put("g", it.isGroup)
                    put("c", it.unread)
                    put("t", it.lastTime)
                }
            )
        }
        root.put("items", arr)
        return root.toString()
    }

    companion object {
        val EMPTY = ConvSnapshot()

        fun fromJson(raw: String?): ConvSnapshot {
            if (raw.isNullOrBlank()) return EMPTY
            return try {
                val root = JSONObject(raw)
                val arr = root.optJSONArray("items") ?: JSONArray()
                val out = ArrayList<ConvEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val username = o.optString("u", "")
                    if (username.isEmpty()) continue
                    out.add(
                        ConvEntry(
                            username = username,
                            name = o.optString("n", username).ifBlank { username },
                            isGroup = o.optBoolean("g", username.endsWith("@chatroom")),
                            unread = o.optInt("c", 0),
                            lastTime = o.optLong("t", 0L),
                        )
                    )
                }
                ConvSnapshot(
                    items = out,
                    error = if (root.isNull("error")) null else root.optString("error", ""),
                    timestamp = root.optLong("ts", 0L),
                )
            } catch (t: Throwable) {
                EMPTY
            }
        }
    }
}

object ConversationProtocol {
    /** App → 微信进程：请重新枚举会话列表 */
    const val ACTION_LIST_CONVERSATIONS = "dev.cheng.wxai.ACTION_LIST_CONVERSATIONS"

    /** 目标宿主包名（发送广播时用 setPackage 收敛，避免被别的 App 收到） */
    const val WECHAT_PACKAGE = "com.tencent.mm"
}
