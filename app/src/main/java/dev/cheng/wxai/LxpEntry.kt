package dev.cheng.wxai

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * Modern LibXposed API 102 入口（META-INF/xposed/java_init.list 指向这里）。
 *
 * 与 WeKit 完全使用同一套 LibXposed 102 接口，使 LSPosed 无需为本模块加载
 * Legacy de.robv.android.xposed.XposedBridge，彻底消除新旧两套 Hook Bridge 混用导致的启动卡死。
 */
class LxpEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        currentProcessName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != MainHook.TARGET_PACKAGE) return
        if (!param.isFirstPackage) return
        val proc = currentProcessName
        if (proc != null && proc != MainHook.TARGET_PACKAGE) return

        L.i("LxpEntry onPackageReady in ${proc ?: param.packageName}")
        MainHook.startBootstrap(param.classLoader)
    }

    companion object {
        @Volatile
        var module: XposedModule? = null

        @Volatile
        var currentProcessName: String? = null

        fun hookMethodAfter(
            method: Method,
            after: (thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit,
        ) {
            val m = module ?: throw IllegalStateException("LxpEntry.module is null")
            m.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val thisObj = chain.thisObject
                    val res = chain.proceed(args)
                    try {
                        after(thisObj, args, res)
                    } catch (t: Throwable) {
                        L.e("hook after callback error", t)
                    }
                    res
                }
        }
    }
}
