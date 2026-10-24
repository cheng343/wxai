package dev.cheng.wxai

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log

/**
 * 微信进程跨进程读配置的唯一入口。
 *
 * 契约（ConfigBridge.kt 按这个实现）：
 *   GET content://dev.cheng.wxai.config/config
 *   -> Cursor 一行一列，列名 "json"，值为 Prefs.load(ctx).toJson().toString()
 *   调用方 / 重载方法都会忽略 selection / projection / sortOrder。
 *
 * 安全：Manifest 里这个 Provider 必须是 exported=true（微信进程才能访问），
 * 所以这里自己校验调用方 uid：只放行
 *   1) 本 App 自身（uid == Process.myUid()），
 *   2) uid 对应包名包含 "com.tencent.mm"（微信）。
 * 不用自定义权限 —— 微信不会申请我们声明的权限，那样只会把功能锁死。
 * 其余调用方一律返回 null（等价于"没有数据"）。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        /** 必须与 AndroidManifest.xml 里 android:authorities 完全一致。 */
        const val AUTHORITY = "dev.cheng.wxai.config"

        /** 路径段：content://dev.cheng.wxai.config/config */
        const val PATH = "config"

        /**
         * 会话列表路由：content://dev.cheng.wxai.config/conversations
         *
         * GET  → 返回上一次微信进程推上来的会话快照（App 侧读）
         * PUT  → 微信进程把新快照写进来（update 调用），只接受微信 uid
         *
         * 为什么反着来：会话数据本来就在微信进程里，App 不能直接读微信库，
         * 所以由微信进程 push、App pull 缓存。Provider 只当信箱，不做查询。
         */
        const val PATH_CONVERSATIONS = "conversations"

        const val PATH_LIFE_SNAPSHOT = "life_snapshot"

        /** 唯一一列的列名，ConfigBridge 按这个名字取值。 */
        const val COLUMN_JSON = "json"

        private const val TAG = "WxAiConfigProvider"
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        /** 与 ConfigBridge 里 Uri.parse("content://dev.cheng.wxai.config/config") 等价。 */
        fun configUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")

        /** 会话快照信箱的 Uri（微信进程 update，App query）。 */
        fun conversationsUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH_CONVERSATIONS")

        /** 数字生命档案信箱的 Uri（微信进程 update，App query）。 */
        fun lifeSnapshotUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH_LIFE_SNAPSHOT")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isTrustedCaller()) {
            Log.w(TAG, "query rejected, callingUid=" + Binder.getCallingUid())
            return null
        }
        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "query without context")
            return null
        }

        val json = try {
            when (uri.lastPathSegment) {
                PATH_CONVERSATIONS -> Prefs.loadConversations(ctx).toJson()
                PATH_LIFE_SNAPSHOT -> Prefs.loadLifeSnapshotRaw(ctx).orEmpty()
                else -> Prefs.load(ctx).toJson().toString()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "build json failed for $uri", t)
            return null
        }

        return MatrixCursor(arrayOf(COLUMN_JSON)).apply { addRow(arrayOf<Any>(json)) }
    }

    /** 只读 Provider：写操作一律拒绝，但不能抛异常把调用方搞崩。 */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        Log.w(TAG, "insert ignored")
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * 唯一的写路径：微信进程推会话快照或数字生命快照。
     *
     * 只接受 /conversations 与 /life_snapshot，且调用方必须是微信或本 App；
     * 其他 App 即使猜到 authority 也写不进来（isTrustedCaller 挡）。
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val seg = uri.lastPathSegment
        if (seg != PATH_CONVERSATIONS && seg != PATH_LIFE_SNAPSHOT) {
            Log.w(TAG, "update ignored for $uri")
            return 0
        }
        if (!isTrustedCaller()) {
            Log.w(TAG, "update rejected, callingUid=" + Binder.getCallingUid())
            return 0
        }
        val ctx = context ?: return 0
        val raw = values?.getAsString(COLUMN_JSON) ?: return 0
        return try {
            if (seg == PATH_LIFE_SNAPSHOT) {
                Prefs.saveLifeSnapshotRaw(ctx, raw)
            } else {
                Prefs.saveConversations(ctx, raw)
            }
            1
        } catch (t: Throwable) {
            Log.e(TAG, "save snapshot failed ($seg)", t)
            0
        }
    }

    override fun getType(uri: Uri): String? = null

    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true

        val ctx = context ?: return false
        val packages = try {
            ctx.packageManager.getPackagesForUid(uid)
        } catch (t: Throwable) {
            Log.w(TAG, "getPackagesForUid(" + uid + ") failed", t)
            null
        }
        if (packages == null || packages.isEmpty()) return false

        for (pkg in packages) {
            if (pkg == WECHAT_PACKAGE) return true
        }
        return false
    }
}
