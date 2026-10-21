package dev.cheng.wxai

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口（assets/xposed_init 指向这里）。
 *
 * 只在微信主进程初始化；hook 点全部在 Application.attach 之后安装，
 * 那时 ClassLoader 已经能解析 com.tencent.mm.* 。
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        // 只挂主进程：push / tools 等进程没有消息库写入权限，挂上只会重复回复
        if (!lpparam.processName.equals(TARGET_PACKAGE, ignoreCase = true)) {
            L.i("skip secondary process: ${lpparam.processName}")
            return
        }

        L.i("module loading in ${lpparam.processName}")

        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.firstOrNull() as? Context ?: return
                        // 关键：初始化不在微信主线程上做。
                        // Application.attach 期间主线程任何阻塞（跨进程查配置、装 hook）都可能拖死启动，
                        // 所以全部丢到后台线程，失败也只写日志。
                        val cl = lpparam.classLoader
                        Thread({
                            try {
                                boot(ctx, cl)
                            } catch (t: Throwable) {
                                L.e("boot failed", t)
                            }
                        }, "wxai-boot").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            L.e("hook Application.attach failed", t)
        }
    }

    private fun boot(ctx: Context, cl: ClassLoader) {
        // 关键防护 1：第一时间拦截微信自己的 Xposed 堆栈检测（h3.a），防止微信自杀/闪退
        try {
            XposedHelpers.findAndHookMethod(
                DexMap.ANTI_DETECT_CLASS,
                cl,
                DexMap.ANTI_DETECT_METHOD,
                Array<StackTraceElement>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
            L.i("anti-detect: hooked ${DexMap.ANTI_DETECT_CLASS}.${DexMap.ANTI_DETECT_METHOD}")
        } catch (t: Throwable) {
            L.w("anti-detect hook skip or failed: ${t.message}")
        }

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

        val cfg = ConfigBridge.get(ctx, force = true)
        L.verbose = cfg.debug
        L.i("config: enabled=${cfg.enabled} whitelist=${cfg.whitelist.size} model=${cfg.model} baseUrl=${cfg.baseUrl}")
        if (cfg.enabled && cfg.apiKey.isBlank()) {
            L.w("enabled=true but apiKey is empty — replies will fail")
        }

        try {
            Hooks.install(ctx, cl)
        } catch (t: Throwable) {
            L.e("Hooks.install failed", t)
        }

        // 会话枚举（白名单选择器用）：独立于回复链路，失败不影响自动回复
        try {
            ConversationBridge.install(ctx, cl)
        } catch (t: Throwable) {
            L.e("ConversationBridge.install failed", t)
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

    companion object {
        const val TARGET_PACKAGE = "com.tencent.mm"
    }
}
