package dev.cheng.wxai

import android.content.Intent
import android.os.Build
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
        startBrainServiceSafely()
        setContent {
            WxAiTheme {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBrainServiceSafely()
        Prefs.broadcastCurrent(this)
    }

    private fun startBrainServiceSafely() {
        try {
            val intent = Intent(this, WxAiBrainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {
        }
    }
}
