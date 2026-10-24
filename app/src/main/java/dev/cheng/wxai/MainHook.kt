package dev.cheng.wxai

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed 入口（assets/xposed_init 指向这里）。
 *
 * 只在微信主进程初始化；在 Application.onCreate 完成后延迟后台启动，
 * 避开 TinkerApplication 启动期 ClassLoader 锁竞争与早期 IPC 阻塞。
 */
class MainHook : IXposedHookLoadPackage {

    private val booted = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        // 只挂主进程：push / tools 等进程没有消息库写入权限，挂上只会重复回复
        if (!lpparam.processName.equals(TARGET_PACKAGE, ignoreCase = true)) {
            L.i("skip secondary process: ${lpparam.processName}")
            return
        }

        L.i("module loading in ${lpparam.processName}")
        val cl = lpparam.classLoader

        try {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.args.firstOrNull() as? Application ?: return
                        scheduleBoot(app, cl)
                    }
                }
            )
        } catch (t: Throwable) {
            L.e("hook Instrumentation.callApplicationOnCreate failed, fallback to Application.attach", t)
            try {
                XposedHelpers.findAndHookMethod(
                    Application::class.java,
                    "attach",
                    Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ctx = param.args.firstOrNull() as? Context ?: return
                            scheduleBoot(ctx, cl)
                        }
                    }
                )
            } catch (t2: Throwable) {
                L.e("hook Application.attach failed", t2)
            }
        }
    }

    private fun scheduleBoot(ctx: Context, cl: ClassLoader) {
        if (!booted.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext ?: ctx
        Thread({
            try {
                // 等待微信主界面与 Tinker/MMKernel 完成启动，避免启动期 ClassLoader 争用
                Thread.sleep(2500L)
                boot(appCtx, cl)
            } catch (t: Throwable) {
                L.e("boot failed", t)
            }
        }, "wxai-boot").apply {
            isDaemon = true
            start()
        }
    }

    private fun boot(ctx: Context, cl: ClassLoader) {
        // 关键防护 1：拦截微信自己的 Xposed 堆栈检测（h3.a），防止异常上报时触发自杀
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

        ConfigBridge.init(ctx)
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
