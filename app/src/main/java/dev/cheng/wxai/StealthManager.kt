package dev.cheng.wxai

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信内纯框架级自隐藏与环境伪装引擎（0 接触微信内部类，彻底杜绝 Native SO 完整性检测）：
 *
 * 核心原理解析：
 * 腾讯微信的 Native 安全引擎（Turing / SecShell / Matrix SO）会对 com.tencent.mm.* 下的所有类方法
 * 做运行期 ArtMethod 指针与内存 Hash 校验。一旦尝试直接 Hook 微信自身的内部类（如 com.tencent.mm.app.h3.a 等“蜜罐”方法），
 * Native 层的完整性校验会瞬间捕捉到 ArtMethod 被篡改，从而直接触发微信实时的“安全风险提醒”与功能受限！
 *
 * 真正的安全隐身哲学：
 * 1. 严格 0 Hook 微信自身任何内部类；
 * 2. 仅在 Android Framework 系统层（ApplicationPackageManager / Settings）进行无痕拦截，
 *    Native SO 无法校验系统 Framework 代码；
 * 3. 在 onPackageReady 最早时机（第 0 毫秒）完成挂载，先于微信任何扫描逻辑生效：
 *    - getPackageInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - getApplicationInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - queryIntentActivities -> 从列表中移除 dev.cheng.wxai
 *    - getInstalledPackages -> 从列表中移除 dev.cheng.wxai
 *    - getInstalledApplications -> 从列表中移除 dev.cheng.wxai
 *    - Settings.Global.getInt("adb_enabled") -> 0
 *    - Settings.Secure.getInt("development_settings_enabled") -> 0
 */
object StealthManager {

    private const val TAG = "WXAI/Stealth"
    const val MODULE_PKG = "dev.cheng.wxai"

    private val initialized = AtomicBoolean(false)

    fun install(cl: ClassLoader) {
        if (!initialized.compareAndSet(false, true)) return
        hookPackageManager(cl)
        hookEnvironment(cl)
        L.i("$TAG Framework-level zero-touch stealth initialized successfully at millisecond 0")
    }

    /**
     * 1. 拦截 ApplicationPackageManager，使 dev.cheng.wxai 在微信视角下彻底未安装
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
     * 2. 环境伪装（伪装未开启 ADB 与开发者选项）
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
}
