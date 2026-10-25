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

        fun hookMethodBefore(
            method: Method,
            before: (thisObj: Any?, args: Array<Any?>) -> Unit,
        ) {
            val m = module ?: throw IllegalStateException("LxpEntry.module is null")
            m.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val thisObj = chain.thisObject
                    before(thisObj, args)
                    chain.proceed(args)
                }
        }

        fun hookMethodFilter(
            method: Method,
            filter: (thisObj: Any?, args: Array<Any?>, result: Any?) -> Any?,
        ) {
            val m = module ?: throw IllegalStateException("LxpEntry.module is null")
            m.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val thisObj = chain.thisObject
                    val res = chain.proceed(args)
                    try {
                        filter(thisObj, args, res)
                    } catch (t: Throwable) {
                        L.e("hook filter callback error", t)
                        res
                    }
                }
        }

        fun hookMethodIntercept(
            method: Method,
            interceptor: (chain: XposedInterface.Chain) -> Any?,
        ) {
            val m = module ?: throw IllegalStateException("LxpEntry.module is null")
            m.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    try {
                        interceptor(chain)
                    } catch (t: Throwable) {
                        L.e("hook intercept callback error", t)
                        chain.proceed(chain.args.toTypedArray())
                    }
                }
        }

        fun hookMethodReplace(
            method: Method,
            replacement: (thisObj: Any?, args: Array<Any?>) -> Any?,
        ) {
            val m = module ?: throw IllegalStateException("LxpEntry.module is null")
            m.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val thisObj = chain.thisObject
                    try {
                        replacement(thisObj, args)
                    } catch (t: Throwable) {
                        L.e("hook replace callback error", t)
                        chain.proceed(args)
                    }
                }
        }
    }
}
