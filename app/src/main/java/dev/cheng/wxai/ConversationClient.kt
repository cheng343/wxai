package dev.cheng.wxai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * 模块 App 侧的会话列表客户端。
 *
 * 微信进程（ConversationBridge）负责真正查库并把快照 push 进 ConfigProvider；
 * 这里只做两件事：① 发广播请求刷新，② 轮询本地缓存等结果。
 * 整个过程不读任何微信数据文件，也不持有微信 uid 权限。
 */
object ConversationClient {

    private const val TAG = "WXAI/ConvClient"

    /** 读本地缓存的快照（不发广播，可能在 stale）。界面首次渲染用它。 */
    fun peek(ctx: Context): ConvSnapshot = Prefs.loadConversations(ctx)

    /** 请求微信进程重新枚举一次。返回是否成功把广播发出去。 */
    fun requestRefresh(ctx: Context): Boolean {
        return try {
            val intent = Intent(ConversationProtocol.ACTION_LIST_CONVERSATIONS)
                .setPackage(ConversationProtocol.WECHAT_PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            ctx.applicationContext.sendBroadcast(intent)
            L.i("$TAG refresh broadcast sent")
            true
        } catch (t: Throwable) {
            L.e("$TAG sendBroadcast failed", t)
            false
        }
    }

    /**
     * 发广播并阻塞等待新快照（必须在线程里调用）。
     *
     * 判定"拿到新结果"的依据是快照时间戳变新 —— 微信进程即使查询失败也会推一份
     * 带 error 的快照，所以失败原因能传回来给界面显示。
     */
    fun refreshBlocking(ctx: Context, timeoutMs: Long = 8_000L): ConvSnapshot {
        val before = peek(ctx)
        if (!requestRefresh(ctx)) {
            return before.copy(error = "无法向微信发送刷新请求（广播被系统拦截）")
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250)
            val now = peek(ctx)
            if (now.timestamp > before.timestamp) return now
        }
        val last = peek(ctx)
        if (last.timestamp > before.timestamp) return last
        return last.copy(
            error = "微信没有在 ${timeoutMs / 1000} 秒内响应。请确认：① 微信正在运行；" +
                "② LSPosed 里勾选了微信且模块已启用；③ 微信版本是 ${DexMap.SUPPORTED_VERSION_NAME}。"
        )
    }

    /** 读微信的 versionName，用于界面显示"已连接/版本不匹配"提示。 */
    fun wechatVersionName(ctx: Context): String? {
        return try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ConversationProtocol.WECHAT_PACKAGE, 0).versionName
        } catch (t: Throwable) {
            null
        }
    }

    /** 微信是否安装（<queries> 已在 Manifest 声明，所以能查到）。 */
    fun isWechatInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(ConversationProtocol.WECHAT_PACKAGE, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }
}
