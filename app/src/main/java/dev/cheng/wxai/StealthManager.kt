package dev.cheng.wxai

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import java.lang.reflect.Method

/**
 * 微信内全套防检测与自隐藏引擎（100% 完整移植并增强 WeKit 系统与隐私四大核心反检测能力）：
 *
 * 1. 隐藏模块应用（移植自 WeKit: HideModuleFromAppList）：
 *    - getPackageInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - getApplicationInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - queryIntentActivities -> 从列表中移除 dev.cheng.wxai
 *    - getInstalledPackages -> 从列表中移除 dev.cheng.wxai（wxai 独家强化）
 *    - getInstalledApplications -> 从列表中移除 dev.cheng.wxai（wxai 独家强化）
 *    微信内部 Matrix / Turing SDK 扫描已安装应用时，彻底查不到本模块任何踪迹。
 *
 * 2. 禁止微信检测 Xposed（移植自 WeKit: PreventXposedDetection）：
 *    - 拦截微信内置堆栈检测方法 com.tencent.mm.app.h3.a([StackTraceElement) -> 强制返回 false
 *    阻止微信在崩溃或日志扫描中检测到 XposedBridge / LibXposed 堆栈帧而上报风险风控。
 *
 * 3. 环境伪装（移植自 WeKit: SpoofEnvironment）：
 *    - 伪装 Settings.Global.getInt("adb_enabled") -> 0
 *    - 伪装 Settings.Secure.getInt("development_settings_enabled") -> 0
 *    - 拦截微信钱包与安全工具类的 VPN 检测 pg0.n3.kj() -> 强制返回 false
 *
 * 4. 禁用微信后台进程状态检测器（移植自 WeKit: NerfBackgroundProcessChecker）：
 *    - 拦截 zq3.b.run()（AbstractProcessChecker）使其中止自杀式清理与异常抛出，防止后台被杀。
 */
object StealthManager {

    private const val TAG = "WXAI/Stealth"
    const val MODULE_PKG = "dev.cheng.wxai"

    fun install(cl: ClassLoader) {
        hookPackageManager(cl)
        hookEnvironment(cl)
        hookVpnDetection(cl)
        hookXposedDetection(cl)
        hookBackgroundProcessChecker(cl)
        L.i("$TAG Complete WeKit anti-detection suite initialized successfully")
    }

    /**
     * 1. 隐藏模块安装状态（WeKit: HideModuleFromAppList）
     */
    private fun hookPackageManager(cl: ClassLoader) {
        try {
            val pmCls = Class.forName("android.app.ApplicationPackageManager", false, cl)
            for (m in pmCls.declaredMethods) {
                when (m.name) {
                    "getPackageInfo" -> {
                        if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                            runCatching {
                                LxpEntry.hookMethodBefore(m) { _, args ->
                                    val pkg = args.getOrNull(0) as? String
                                    if (pkg == MODULE_PKG) {
                                        throw PackageManager.NameNotFoundException(MODULE_PKG)
                                    }
                                }
                            }
                        }
                    }

                    "getApplicationInfo" -> {
                        if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                            runCatching {
                                LxpEntry.hookMethodBefore(m) { _, args ->
                                    val pkg = args.getOrNull(0) as? String
                                    if (pkg == MODULE_PKG) {
                                        throw PackageManager.NameNotFoundException(MODULE_PKG)
                                    }
                                }
                            }
                        }
                    }

                    "queryIntentActivities" -> {
                        runCatching {
                            LxpEntry.hookMethodFilter(m) { _, _, res ->
                                val list = res as? MutableList<*> ?: return@hookMethodFilter res
                                list.removeAll { item ->
                                    val r = item as? ResolveInfo ?: return@removeAll false
                                    r.activityInfo?.packageName == MODULE_PKG
                                }
                                list
                            }
                        }
                    }

                    "getInstalledPackages" -> {
                        runCatching {
                            LxpEntry.hookMethodFilter(m) { _, _, res ->
                                val list = res as? MutableList<*> ?: return@hookMethodFilter res
                                list.removeAll { item ->
                                    val p = item as? PackageInfo ?: return@removeAll false
                                    p.packageName == MODULE_PKG
                                }
                                list
                            }
                        }
                    }

                    "getInstalledApplications" -> {
                        runCatching {
                            LxpEntry.hookMethodFilter(m) { _, _, res ->
                                val list = res as? MutableList<*> ?: return@hookMethodFilter res
                                list.removeAll { item ->
                                    val a = item as? ApplicationInfo ?: return@removeAll false
                                    a.packageName == MODULE_PKG
                                }
                                list
                            }
                        }
                    }
                }
            }
            L.i("$TAG ApplicationPackageManager stealth hooks installed")
        } catch (t: Throwable) {
            L.w("$TAG hookPackageManager failed: ${t.message}")
        }
    }

    /**
     * 2. 环境伪装（WeKit: SpoofEnvironment - ADB & 开发者选项）
     */
    private fun hookEnvironment(cl: ClassLoader) {
        // 伪装 Settings.Global.getInt
        runCatching {
            for (m in Settings.Global::class.java.declaredMethods) {
                if (m.name == "getInt" && m.parameterTypes.size == 3 && m.parameterTypes[1] == String::class.java) {
                    LxpEntry.hookMethodIntercept(m) { chain ->
                        val args = chain.args.toTypedArray()
                        val name = args.getOrNull(1) as? String
                        if (name == "adb_enabled") {
                            0
                        } else {
                            chain.proceed(args)
                        }
                    }
                }
            }
        }

        // 伪装 Settings.Secure.getInt
        runCatching {
            for (m in Settings.Secure::class.java.declaredMethods) {
                if (m.name == "getInt" && m.parameterTypes.size == 3 && m.parameterTypes[1] == String::class.java) {
                    LxpEntry.hookMethodIntercept(m) { chain ->
                        val args = chain.args.toTypedArray()
                        val name = args.getOrNull(1) as? String
                        if (name == "development_settings_enabled") {
                            0
                        } else {
                            chain.proceed(args)
                        }
                    }
                }
            }
        }
    }

    /**
     * 3. VPN 检测伪装（WeKit: SpoofEnvironment::methodIsVpnEnabled，8.0.76 对应 pg0.n3.kj()Z）
     */
    private fun hookVpnDetection(cl: ClassLoader) {
        runCatching {
            val n3Cls = Class.forName(DexMap.VPN_DETECT_CLASS, false, cl)
            for (m in n3Cls.declaredMethods) {
                if (m.name == DexMap.VPN_DETECT_METHOD && m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                    LxpEntry.hookMethodReplace(m) { _, _ -> false }
                    L.i("$TAG SpoofEnvironment methodIsVpnEnabled installed on ${DexMap.VPN_DETECT_CLASS}.${DexMap.VPN_DETECT_METHOD}")
                }
            }
        }.onFailure {
            L.w("$TAG hookVpnDetection failed: ${it.message}")
        }
    }

    /**
     * 4. 禁止微信检测 Xposed 调用栈（WeKit: PreventXposedDetection，8.0.76 对应 com.tencent.mm.app.h3.a([Ljava/lang/StackTraceElement;)Z）
     */
    private fun hookXposedDetection(cl: ClassLoader) {
        var hooked = false
        // 优先直取 8.0.76 确切类与方法
        runCatching {
            val h3Cls = Class.forName(DexMap.XPOSED_DETECT_CLASS, false, cl)
            for (m in h3Cls.declaredMethods) {
                if (isStackTraceChecker(m)) {
                    LxpEntry.hookMethodReplace(m) { _, _ -> false }
                    L.i("$TAG PreventXposedDetection installed on ${DexMap.XPOSED_DETECT_CLASS}.${m.name}")
                    hooked = true
                }
            }
        }

        // 动态兜底匹配：搜索 com.tencent.mm.app 下符合签名的检测方法
        if (!hooked) {
            runCatching {
                for (suffix in listOf("h3", "g3", "i3", "j3", "k3")) {
                    val targetClass = "com.tencent.mm.app.$suffix"
                    val cls = runCatching { Class.forName(targetClass, false, cl) }.getOrNull() ?: continue
                    for (m in cls.declaredMethods) {
                        if (isStackTraceChecker(m)) {
                            LxpEntry.hookMethodReplace(m) { _, _ -> false }
                            L.i("$TAG PreventXposedDetection dynamic fallback installed on $targetClass.${m.name}")
                            hooked = true
                        }
                    }
                    if (hooked) break
                }
            }
        }
    }

    private fun isStackTraceChecker(m: Method): Boolean {
        return m.returnType == Boolean::class.javaPrimitiveType &&
            m.parameterTypes.size == 1 &&
            m.parameterTypes[0].isArray &&
            m.parameterTypes[0].componentType == StackTraceElement::class.java
    }

    /**
     * 5. 禁用微信后台进程状态检测器（WeKit: NerfBackgroundProcessChecker，8.0.76 对应 zq3.b.run()V）
     */
    private fun hookBackgroundProcessChecker(cl: ClassLoader) {
        runCatching {
            val bCls = Class.forName(DexMap.BG_PROCESS_CHECKER_CLASS, false, cl)
            val runMethod = bCls.getDeclaredMethod("run")
            LxpEntry.hookMethodReplace(runMethod) { _, _ -> null }
            L.i("$TAG NerfBackgroundProcessChecker installed on ${DexMap.BG_PROCESS_CHECKER_CLASS}.run")
        }.onFailure {
            L.w("$TAG hookBackgroundProcessChecker failed: ${it.message}")
        }
    }
}
