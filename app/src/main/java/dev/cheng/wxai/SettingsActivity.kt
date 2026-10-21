package dev.cheng.wxai

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 模块 App 的设置界面。
 *
 * 继承 android.app.Activity 而不是 AppCompatActivity：本项目只有平台依赖
 * （build.gradle.kts 里没有 androidx/appcompat），换成 AppCompatActivity 编译不过。
 * 因此布局也只用平台原生控件（EditText / CheckBox / Button / TextView）。
 *
 * 保存流程：控件 -> Config -> Prefs.save -> notifyChange(content://dev.cheng.wxai.config/config)
 * -> Toast。微信进程侧由 ConfigBridge（带 15 秒 TTL）自己经 ContentProvider 拉这份 JSON。
 *
 * 注意：界面代码里绝不调用 L（Log.kt 走 XposedBridge，在模块 App 进程里会
 * NoClassDefFoundError），日志一律用 android.util.Log 或显示到 tv_status。
 */
class SettingsActivity : Activity() {

    private lateinit var cbEnabled: CheckBox
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etSystemPrompt: EditText
    private lateinit var etWhitelist: EditText
    private lateinit var etNickname: EditText
    private lateinit var cbGroupOnlyMention: CheckBox
    private lateinit var etCooldown: EditText
    private lateinit var etMaxPerHour: EditText
    private lateinit var etDelayMin: EditText
    private lateinit var etDelayMax: EditText
    private lateinit var etMaxChars: EditText
    private lateinit var etTemperature: EditText
    private lateinit var cbDebug: CheckBox
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var tvStatus: TextView

    /** 测试连接是阻塞网络请求，用这个标记防止重复点、重复起线程。 */
    @Volatile
    private var testInProgress: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        fillViews(Prefs.load(this))
        btnSave.setOnClickListener { onSaveClicked() }
        btnTest.setOnClickListener { onTestClicked() }
    }

    // ------------------------------------------------------------------ 绑定 / 回填

    private fun bindViews() {
        cbEnabled = findViewById(R.id.cb_enabled)
        etBaseUrl = findViewById(R.id.et_base_url)
        etApiKey = findViewById(R.id.et_api_key)
        etModel = findViewById(R.id.et_model)
        etSystemPrompt = findViewById(R.id.et_system_prompt)
        etWhitelist = findViewById(R.id.et_whitelist)
        etNickname = findViewById(R.id.et_nickname)
        cbGroupOnlyMention = findViewById(R.id.cb_group_only_mention)
        etCooldown = findViewById(R.id.et_cooldown)
        etMaxPerHour = findViewById(R.id.et_max_per_hour)
        etDelayMin = findViewById(R.id.et_delay_min)
        etDelayMax = findViewById(R.id.et_delay_max)
        etMaxChars = findViewById(R.id.et_max_chars)
        etTemperature = findViewById(R.id.et_temperature)
        cbDebug = findViewById(R.id.cb_debug)
        btnSave = findViewById(R.id.btn_save)
        btnTest = findViewById(R.id.btn_test)
        tvStatus = findViewById(R.id.tv_status)
    }

    private fun fillViews(cfg: Config) {
        cbEnabled.isChecked = cfg.enabled
        etBaseUrl.setText(cfg.baseUrl)
        etApiKey.setText(cfg.apiKey)
        etModel.setText(cfg.model)
        etSystemPrompt.setText(cfg.systemPrompt)
        etWhitelist.setText(cfg.whitelist.joinToString("\n"))
        etNickname.setText(cfg.myNickname)
        cbGroupOnlyMention.isChecked = cfg.groupOnlyWhenMentioned
        etCooldown.setText(cfg.cooldownSeconds.toString())
        etMaxPerHour.setText(cfg.maxPerHour.toString())
        etDelayMin.setText(cfg.replyDelayMinMs.toString())
        etDelayMax.setText(cfg.replyDelayMaxMs.toString())
        etMaxChars.setText(cfg.maxReplyChars.toString())
        etTemperature.setText(cfg.temperature.toString())
        cbDebug.isChecked = cfg.debug
        tvStatus.text = getString(R.string.status_idle)
    }

    // ------------------------------------------------------------------ 保存

    private fun onSaveClicked() {
        try {
            val cfg = readConfig()
            Prefs.save(this, cfg)
            try {
                // Uri 实际值 = content://dev.cheng.wxai.config/config
                contentResolver.notifyChange(ConfigProvider.configUri(), null)
            } catch (t: Throwable) {
                // Provider 没装载/无权通知都不影响已落盘的配置，只记日志。
                Log.w(TAG, "notifyChange failed", t)
            }
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            tvStatus.text = getString(R.string.status_idle)
        } catch (t: Throwable) {
            Log.e(TAG, "save config failed", t)
            Toast.makeText(
                this,
                "保存失败：" + (t.message ?: t.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * 从控件读出 Config。所有数值都用 toIntOrNull()/toDoubleOrNull() 兜底，
     * 空串、乱输、超大数都不会让界面崩。
     */
    private fun readConfig(): Config {
        // 以已保存的配置为底，避免以后 Config 加字段时这里漏填。
        val current = Prefs.load(this)

        val whitelist = textOf(etWhitelist)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 两个延迟允许填反：交换而不是把 min > max 传给引擎。
        val delayA = intOf(etDelayMin, Config.DEFAULT.replyDelayMinMs).coerceAtLeast(0)
        val delayB = intOf(etDelayMax, Config.DEFAULT.replyDelayMaxMs).coerceAtLeast(0)

        // "NaN" 也能被 toDoubleOrNull() 解析成 NaN，而 JSONObject.put("temperature", NaN)
        // 会抛 JSONException，所以这里必须挡掉非有限值。
        val temp = doubleOf(etTemperature, Config.DEFAULT.temperature)
        val safeTemp = if (temp.isFinite()) temp.coerceIn(0.0, 2.0) else Config.DEFAULT.temperature

        return current.copy(
            enabled = cbEnabled.isChecked,
            baseUrl = textOf(etBaseUrl),
            apiKey = textOf(etApiKey),
            model = textOf(etModel),
            systemPrompt = textOf(etSystemPrompt),
            whitelist = whitelist,
            myNickname = textOf(etNickname),
            groupOnlyWhenMentioned = cbGroupOnlyMention.isChecked,
            cooldownSeconds = intOf(etCooldown, Config.DEFAULT.cooldownSeconds).coerceAtLeast(0),
            maxPerHour = intOf(etMaxPerHour, Config.DEFAULT.maxPerHour).coerceAtLeast(0),
            replyDelayMinMs = minOf(delayA, delayB),
            replyDelayMaxMs = maxOf(delayA, delayB),
            maxReplyChars = intOf(etMaxChars, Config.DEFAULT.maxReplyChars).coerceIn(1, 10_000),
            temperature = safeTemp,
            debug = cbDebug.isChecked,
        )
    }

    // ------------------------------------------------------------------ 测试连接

    private fun onTestClicked() {
        if (testInProgress) return
        testInProgress = true
        btnTest.isEnabled = false
        tvStatus.text = getString(R.string.status_testing)

        // 在 UI 线程把控件读成不可变 Config，后台线程只碰它，避免跨线程读 View。
        val cfg = readConfig()
        val prompt = "你好，这是一条连通性测试，请回复收到"

        val worker = Thread(Runnable {
            val statusText = try {
                val result = AiClient.complete(cfg, prompt)
                val text = result.text
                if (!text.isNullOrBlank()) {
                    getString(R.string.status_test_ok, text.take(100))
                } else {
                    getString(R.string.status_test_fail, result.error ?: "未知错误")
                }
            } catch (t: Throwable) {
                // AiClient / Config.fromJson 内部会调 L（XposedBridge）——模块 App 进程里
                // 那是 NoClassDefFoundError。这里必须兜住，否则点一下按钮就崩。
                Log.e(TAG, "test connection failed", t)
                val detail = if (t is NoClassDefFoundError) {
                    // 真实错误（HTTP 状态码/超时/解析失败）被 AiClient 内部的 L.e 吃掉了，
                    // 只能提示用户去看 Logcat，不要假装是网络错误。
                    getString(R.string.status_test_xposed_issue)
                } else {
                    t.javaClass.simpleName + ": " + (t.message ?: "")
                }
                getString(R.string.status_test_fail, detail)
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    tvStatus.text = statusText
                    btnTest.isEnabled = true
                }
                testInProgress = false
            }
        }, "wxai-test-conn")
        worker.start()
    }

    // ------------------------------------------------------------------ 小工具

    private fun textOf(et: EditText): String = et.text?.toString()?.trim().orEmpty()

    private fun intOf(et: EditText, fallback: Int): Int = textOf(et).toIntOrNull() ?: fallback

    private fun doubleOf(et: EditText, fallback: Double): Double =
        textOf(et).toDoubleOrNull() ?: fallback

    private companion object {
        const val TAG = "WxAiSettings"
    }
}
