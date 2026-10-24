package dev.cheng.wxai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.cheng.wxai.ui.SettingsScreen
import dev.cheng.wxai.ui.WxAiTheme

/**
 * 模块设置界面。
 *
 * 纯 Compose 实现：Activity 只负责挂主题 + [SettingsScreen]，不持有任何界面状态，
 * 配置的读写全部通过 [Prefs]（微信进程经 ContentProvider 读取同一份 JSON）。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WxAiTheme {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.broadcastCurrent(this)
    }
}
