package dev.cheng.wxai

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed 入口（assets/xposed_init 指向这里）。
 *
 * 启动时序完全对齐 WeKit (UnifiedEntryPoint + StartupAgent)：
 * 1. 仅在微信主进程 (com.tencent.mm) 挂载，跳过 :push / :tools / :appbrand 等子进程；
 * 2. 先挂 com.tencent.mm.app.Application.attachBaseContext(Context)，拿到 Tinker 完成后的真实 ClassLoader；
 * 3. 在 attachBaseContext 结束时立即挂 com.tencent.mm.app.h3.a 屏蔽堆栈检测并清理 app_qqprotect；
 * 4. 在 Instrumentation.callApplicationOnCreate 结束后执行轻量级注册（启动期零跨进程 IPC、零数据库查询、零轮询线程）。
 */
class MainHook : IXposedHookLoadPackage {

    private val attachHooked = AtomicBoolean(false)
    private val booted = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        if (lpparam.processName != TARGET_PACKAGE) {
            return
        }

        L.i("module loading in ${lpparam.processName}")
        val initCl = lpparam.classLoader

        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.app.Application",
                initCl,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!attachHooked.compareAndSet(false, true)) return
                        val app = param.thisObject as? Application ?: return
                        val baseCtx = param.args.firstOrNull() as? Context ?: app
                        val currentCl = baseCtx.classLoader ?: app.classLoader ?: initCl

                        onAttachBaseContext(app, currentCl)
                    }
                }
            )
        } catch (t: Throwable) {
            L.e("hook com.tencent.mm.app.Application.attachBaseContext failed", t)
        }
    }

    private fun onAttachBaseContext(app: Application, cl: ClassLoader) {
        // 清理 app_qqprotect（对齐 WeKit StartupAgent）
        runCatching {
            File(app.dataDir, "app_qqprotect").deleteRecursively()
        }

        // 立即拦截微信 Xposed 堆栈检测（h3.a），确保 Application.onCreate 期间已在保护之下
        installAntiDetect(cl)

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                cl,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!booted.compareAndSet(false, true)) return
                        val application = param.args.firstOrNull() as? Application ?: app
                        val realCl = application.baseContext?.classLoader
                            ?: application.classLoader
                            ?: cl
                        try {
                            boot(application, realCl)
                        } catch (t: Throwable) {
                            L.e("boot failed", t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            L.e("hook Instrumentation.callApplicationOnCreate failed", t)
        }
    }

    private fun installAntiDetect(cl: ClassLoader) {
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

        // 启动期只读微信本地镜像 SharedPreferences + 注册广播，绝不发起跨进程 ContentProvider 请求
        ConfigBridge.init(ctx)

        try {
            Hooks.install(ctx, cl)
        } catch (t: Throwable) {
            L.e("Hooks.install failed", t)
        }

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
