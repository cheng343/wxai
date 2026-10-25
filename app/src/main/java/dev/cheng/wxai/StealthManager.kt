package dev.cheng.wxai

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings

/**
 * 微信内防检测与自隐藏引擎（100% 移植并增强自 WeKit 的 HideModuleFromAppList / SpoofEnvironment）：
 *
 * 1. 深度 Hook ApplicationPackageManager：
 *    - getPackageInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - getApplicationInfo("dev.cheng.wxai", ...) -> 强制抛出 NameNotFoundException
 *    - queryIntentActivities -> 从列表中移除 dev.cheng.wxai
 *    - getInstalledPackages -> 从列表中移除 dev.cheng.wxai
 *    - getInstalledApplications -> 从列表中移除 dev.cheng.wxai
 *    让微信自身的安全 SDK / Matrix / Turing SDK 扫描已安装应用时，彻底查不到本模块的任何蛛丝马迹；
 *
 * 2. 环境伪装（SpoofEnvironment）：
 *    - 伪装 adb_enabled = 0
 *    - 伪装 development_settings_enabled = 0
 */
object StealthManager {

    private const val TAG = "WXAI/Stealth"
    const val MODULE_PKG = "dev.cheng.wxai"

    fun install(cl: ClassLoader) {
        hookPackageManager(cl)
        hookEnvironment(cl)
    }

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
            L.i("$TAG ApplicationPackageManager stealth hooks installed successfully")
        } catch (t: Throwable) {
            L.w("$TAG hookPackageManager failed: ${t.message}")
        }
    }

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
        L.i("$TAG Environment spoofing hooks installed")
    }
}
