package dev.cheng.wxai

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 模块主引导器（同时兼容 Modern LibXposed 102 的 LxpEntry 与旧版 IXposedHookLoadPackage）。
 *
 * 核心设计（彻底消除与 WeKit 共存时的启动死锁）：
 * 1. 微信启动期（0~5 秒）安装 **0 个 Hook**，完全不碰 Application.attachBaseContext、
 *    Instrumentation.callApplicationOnCreate、h3.a 等高频启动方法；
 * 2. 后台守护线程等待 5 秒（让微信 Tinker 与 WeKit 100% 完成初始化），再通过
 *    ActivityThread.currentApplication() 无 Hook 拿到 Application 与真实 ClassLoader；
 * 3. 若检测到进程内已有 WeKit，直接复用 WeKit 的 WeDatabaseListenerApi / WeDatabaseApi，
 *    全生命周期保持 **0 个 Xposed Hook**！
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        if (lpparam.processName != TARGET_PACKAGE) return

        L.i("legacy handleLoadPackage in ${lpparam.processName}")
        startBootstrap(lpparam.classLoader)
    }

    companion object {
        const val TARGET_PACKAGE = "com.tencent.mm"

        private val started = AtomicBoolean(false)

        fun startBootstrap(initialCl: ClassLoader) {
            if (!started.compareAndSet(false, true)) return

            thread(name = "wxai-boot", isDaemon = true) {
                try {
                    // 让微信与 WeKit 先零干扰完成全部启动流程
                    Thread.sleep(5000L)

                    var app: Application? = null
                    for (i in 0 until 60) {
                        app = currentApplicationQuietly()
                        if (app != null && app.baseContext != null) break
                        Thread.sleep(500L)
                    }

                    val application = app
                    if (application == null) {
                        L.e("wxai-boot timed out waiting for Application")
                        return@thread
                    }

                    val realCl = application.baseContext?.classLoader
                        ?: application.classLoader
                        ?: initialCl

                    runCatching {
                        File(application.dataDir, "app_qqprotect").deleteRecursively()
                    }

                    boot(application, realCl)
                } catch (t: Throwable) {
                    L.e("wxai-boot failed", t)
                }
            }
        }

        private fun currentApplicationQuietly(): Application? {
            return try {
                val atCls = Class.forName("android.app.ActivityThread")
                val m = atCls.getDeclaredMethod("currentApplication")
                m.isAccessible = true
                m.invoke(null) as? Application
            } catch (_: Throwable) {
                null
            }
        }

        private fun boot(ctx: Context, cl: ClassLoader) {
            val info = packageInfo(ctx)
            val versionName = info?.versionName ?: "?"
            val versionCode = longVersionCode(info)
            L.i("wechat versionName=$versionName versionCode=$versionCode")

            if (versionName != DexMap.SUPPORTED_VERSION_NAME || !DexMap.SUPPORTED_VERSION_CODES.contains(versionCode)) {
                L.w(
                    "unsupported wechat build: $versionName/$versionCode (target ${DexMap.SUPPORTED_VERSION_NAME} " +
                        DexMap.SUPPORTED_VERSION_CODES.joinToString("/") + "), hooks will try anyway"
                )
            }

            ConfigBridge.init(ctx)

            try {
                ConversationBridge.install(ctx, cl)
            } catch (t: Throwable) {
                L.e("ConversationBridge.install failed", t)
            }

            try {
                Hooks.install(ctx, cl)
            } catch (t: Throwable) {
                L.e("Hooks.install failed", t)
            }
        }

        private fun packageInfo(ctx: Context): PackageInfo? = try {
            ctx.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
        } catch (t: Throwable) {
            L.e("getPackageInfo failed", t)
            null
        }

        private fun longVersionCode(info: PackageInfo?): Long {
            if (info == null) return -1
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }
    }
}
