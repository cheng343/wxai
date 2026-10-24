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
 * 微信进程与模块 App 跨进程同步配置、会话列表与生命状态快照的信箱。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dev.cheng.wxai.config"
        const val PATH = "config"
        const val PATH_CONVERSATIONS = "conversations"
        const val PATH_LIFE_SNAPSHOT = "life_snapshot"
        const val COLUMN_JSON = "json"

        private const val TAG = "WxAiConfigProvider"
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        fun configUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")
        fun conversationsUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH_CONVERSATIONS")
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
        val ctx = context ?: return null

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

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val seg = uri.lastPathSegment
        if (seg != PATH_CONVERSATIONS && seg != PATH_LIFE_SNAPSHOT && seg != PATH) {
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
            when (seg) {
                PATH_LIFE_SNAPSHOT -> Prefs.saveLifeSnapshotRaw(ctx, raw)
                PATH_CONVERSATIONS -> Prefs.saveConversations(ctx, raw)
                PATH -> Prefs.save(ctx, Config.fromJson(raw))
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
            null
        } ?: return false

        for (pkg in packages) {
            if (pkg == WECHAT_PACKAGE) return true
        }
        return false
    }
}
