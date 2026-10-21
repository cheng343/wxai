# ── wxai 模块类全部保留 ──────────────────────────────────────
# LSPosed 注入后，XposedHelpers 通过反射加载本模块的类；
# R8 optimize 模式会做 class-merge / method-inline / field-strip，
# 极易把 hook 链上的 object 单例、data class、常量类删掉或合并。
# 模块自身代码本来就很小，全部 keep 最安全。
-keep class dev.cheng.wxai.** { *; }

# Compose 的 Activity 里做 setContent，保留 ExtensionActivity 相关
-keep class androidx.activity.ComponentActivity { *; }

# Xposed API 是 compileOnly，运行时由框架提供
-dontwarn de.robv.android.xposed.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.slf4j.**

# 保留行号便于排查（体积代价很小）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
