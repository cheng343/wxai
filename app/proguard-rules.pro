# LSPosed 用类名反射加载入口，R8 不能改名/删除
-keep class dev.cheng.wxai.MainHook { *; }
# 系统按 manifest 实例化
-keep class dev.cheng.wxai.ConfigProvider { *; }
-keep class dev.cheng.wxai.SettingsActivity { *; }
# Compose 的 Activity 里做 setContent，保留 ExtensionActivity 相关
-keep class androidx.activity.ComponentActivity { *; }

# Xposed API 是 compileOnly，运行时由框架提供
-dontwarn de.robv.android.xposed.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.slf4j.**

# 保留行号便于排查（体积代价很小）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
