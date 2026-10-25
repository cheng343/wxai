package dev.cheng.wxai

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 模块主引导器（纯 LibXposed API 102 架构，彻底剔除 Legacy Xposed）。
 *
 * 核心设计（与 WeKit LSPosed 版 100% 兼容）：
 * 1. 微信启动期（0~5 秒）安装 0 个 Hook，完全不干涉微信与 WeKit 初始化；
 * 2. 后台守护线程等待 5 秒，通过 ActivityThread.currentApplication() 无 Hook 获取 Application；
 * 3. 检测到 WeKit 时，直接挂接 WeDatabaseListenerApi 与 WeDatabaseApi，全程保持 0 Hook；
 * 4. 彻底剔除 de.robv.android.xposed.IXposedHookLoadPackage 与 assets/xposed_init，
 *    避免 LSPosed 加载 Legacy 兼容桥产生 JIT/Hook 冲突。
 */
object MainHook {

    const val TARGET_PACKAGE = "com.tencent.mm"

    private val started = AtomicBoolean(false)

    fun startBootstrap(initialCl: ClassLoader) {
        if (!started.compareAndSet(false, true)) return

        thread(name = "pool-init-worker", isDaemon = true) {
            try {
                // 让微信与 WeKit 零干扰完成全部启动流程
                Thread.sleep(5000L)

                var app: android.app.Application? = null
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

                boot(application, realCl)
            } catch (t: Throwable) {
                L.e("wxai-boot failed", t)
            }
        }
    }

    private fun currentApplicationQuietly(): android.app.Application? {
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val m = atCls.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? android.app.Application
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
