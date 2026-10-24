package dev.cheng.wxai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cheng.wxai.AiClient
import dev.cheng.wxai.Config
import dev.cheng.wxai.ConvSnapshot
import dev.cheng.wxai.ConversationClient
import dev.cheng.wxai.DexMap
import dev.cheng.wxai.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var cfg by remember { mutableStateOf(Prefs.load(context)) }
    var initialized by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(ConversationClient.peek(context)) }
    var refreshing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorSpec?>(null) }

    val wechatInstalled = remember { ConversationClient.isWechatInstalled(context) }
    val wechatVersion = remember { ConversationClient.wechatVersionName(context) }
    val moduleVersion = remember { moduleVersionName(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val update: ((Config) -> Config) -> Unit = { transform ->
        cfg = transform(cfg)
    }

    // 从微信侧读取会话列表（refreshBlocking 需要 IO 线程）。
    val refresh: (Boolean) -> Unit = { showToast ->
        if (!refreshing) {
            refreshing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        ConversationClient.refreshBlocking(context)
                    } catch (t: Throwable) {
                        ConvSnapshot(error = "读取会话列表失败：${t.javaClass.simpleName}：${t.message ?: "无详细信息"}")
                    }
                }
                snapshot = result
                refreshing = false
                if (showToast) {
                    snackbarHostState.showSnackbar(
                        if (result.error != null) "读取会话列表失败" else "已读取 ${result.items.size} 个会话"
                    )
                }
            }
        }
    }

    // 「测试连接」：用当前配置向 OpenAI 兼容接口发一条消息。
    val testConnection: () -> Unit = {
        if (!testing) {
            testing = true
            val request = cfg.copy(
                baseUrl = cfg.baseUrl.trim(),
                apiKey = cfg.apiKey.trim(),
                model = cfg.model.trim(),
            )
            scope.launch {
                val message = withContext(Dispatchers.IO) {
                    try {
                        val result = AiClient.complete(request, "你好")
                        val text = result.text
                        if (!text.isNullOrBlank()) {
                            "连接成功，模型回复：${text.take(40)}"
                        } else {
                            "连接失败：${result.error ?: "未知错误"}"
                        }
                    } catch (t: Throwable) {
                        "连接失败：${t.javaClass.simpleName}${if (t.message.isNullOrBlank()) "" else "：" + t.message}"
                    }
                }
                testing = false
                snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    // 配置即时生效：改动后 400ms 防抖保存，避免每次输入都写盘。
    LaunchedEffect(cfg) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        delay(400)
        try {
            Prefs.save(context, cfg)
            snackbarHostState.showSnackbar("已保存")
        } catch (t: Throwable) {
            snackbarHostState.showSnackbar("保存失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // 首次进入时如果本地快照为空或已过期（> 5 分钟），自动刷新一次。
    LaunchedEffect(Unit) {
        val current = snapshot
        val stale = current.items.isEmpty() ||
            current.timestamp == 0L ||
            (System.currentTimeMillis() - current.timestamp) > 5 * 60_000L
        if (stale) {
            refresh(false)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text("微信 AI 助手") },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusCard(
                installed = wechatInstalled,
                versionName = wechatVersion,
                enabled = cfg.enabled,
                onEnabledChange = { value -> update { it.copy(enabled = value) } },
            )
            AiServiceCard(
                cfg = cfg,
                testing = testing,
                update = update,
                edit = { spec -> editor = spec },
                onTest = testConnection,
            )
            ReplyPolicyCard(
                cfg = cfg,
                update = update,
                edit = { spec -> editor = spec },
            )
            MemoryAndAgentCard(
                cfg = cfg,
                update = update,
                edit = { spec -> editor = spec },
            )
            WhitelistCard(
                cfg = cfg,
                snapshot = snapshot,
                refreshing = refreshing,
                onManage = { picking = true },
                onRefresh = { refresh(true) },
            )
            DebugCard(
                debug = cfg.debug,
                onDebugChange = { value -> update { it.copy(debug = value) } },
                moduleVersion = moduleVersion,
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (picking) {
        ConversationPicker(
            initialSelected = cfg.whitelist.toSet(),
            snapshot = snapshot,
            refreshing = refreshing,
            onRefresh = { refresh(true) },
            onConfirm = { selected ->
                update { it.copy(whitelist = selected) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    editor?.let { spec ->
        when (spec) {
            is EditorSpec.Text -> EditTextDialog(
                title = spec.title,
                initial = spec.initial,
                multiline = spec.multiline,
                password = spec.password,
                keyboardType = spec.keyboardType,
                placeholder = spec.placeholder,
                onDismiss = { editor = null },
                onConfirm = { value ->
                    editor = null
                    spec.onApply(value)
                },
            )

            is EditorSpec.Number -> {
                val rangeCheck: (String) -> String? = { raw ->
                    val parsed = raw.trim().toIntOrNull()
                    when {
                        parsed == null -> "请输入整数"
                        parsed < spec.min -> "不能小于 ${spec.min}"
                        parsed > spec.max -> "不能大于 ${spec.max}"
                        else -> null
                    }
                }
                EditTextDialog(
                    title = spec.title,
                    initial = spec.initial.toString(),
                    keyboardType = KeyboardType.Number,
                    validate = rangeCheck,
                    onDismiss = { editor = null },
                    onConfirm = { raw ->
                        editor = null
                        raw.trim().toIntOrNull()?.let { parsed -> spec.onApply(parsed) }
                    },
                )
            }

            is EditorSpec.Delay -> DelayDialog(
                initialMin = spec.initialMin,
                initialMax = spec.initialMax,
                onDismiss = { editor = null },
                onConfirm = { min, max ->
                    editor = null
                    spec.onApply(min, max)
                },
            )
        }
    }
}

@Composable
private fun StatusCard(
    installed: Boolean,
    versionName: String?,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val supported = DexMap.SUPPORTED_VERSION_NAME
    val versionSummary = when {
        !installed -> "未检测到微信"
        versionName.isNullOrBlank() -> "无法读取微信版本名"
        versionName == supported -> "$versionName（已适配）"
        else -> "$versionName（未适配，本模块仅支持 $supported）"
    }
    val versionColor = when {
        !installed || versionName.isNullOrBlank() -> MaterialTheme.colorScheme.error
        versionName == supported -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    SettingsCard(title = "运行状态") {
        InfoRow(
            title = "微信",
            value = if (installed) "已安装" else "未安装",
            valueColor = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        SettingRow(
            title = "微信版本",
            subtitle = versionSummary,
            subtitleColor = versionColor,
        )
        SwitchRow(
            title = "启用自动回复",
            subtitle = "关闭后微信里不会自动回复任何消息",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun AiServiceCard(
    cfg: Config,
    testing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onTest: () -> Unit,
) {
    SettingsCard(title = "AI 服务") {
        ValueRow(
            title = "服务地址",
            value = cfg.baseUrl,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "服务地址（Base URL）",
                        initial = cfg.baseUrl,
                        keyboardType = KeyboardType.Uri,
                        onApply = { value -> update { it.copy(baseUrl = value.trim()) } },
                    )
                )
            },
        )
        ValueRow(
            title = "API Key",
            value = if (cfg.apiKey.isBlank()) "未设置" else "已设置（点此修改）",
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "API Key",
                        initial = cfg.apiKey,
                        password = true,
                        keyboardType = KeyboardType.Password,
                        placeholder = "sk-…",
                        onApply = { value -> update { it.copy(apiKey = value.trim()) } },
                    )
                )
            },
        )
        ValueRow(
            title = "模型",
            value = cfg.model,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "模型",
                        initial = cfg.model,
                        placeholder = "deepseek-chat",
                        onApply = { value -> update { it.copy(model = value.trim()) } },
                    )
                )
            },
        )
        TemperatureSlider(
            value = cfg.temperature,
            onChange = { value -> update { it.copy(temperature = value) } },
        )
        SettingRow(
            title = "测试连接",
            subtitle = "用当前配置向接口发一条「你好」",
            onClick = { if (!testing) onTest() },
            trailing = {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun TemperatureSlider(value: Double, onChange: (Double) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "随机性 temperature",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "%.1f".format(Locale.US, value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(0f, 2f),
            onValueChange = { raw ->
                onChange((raw.toDouble() * 10.0).roundToInt() / 10.0)
            },
            valueRange = 0f..2f,
            steps = 19,
        )
    }
}

@Composable
private fun ReplyPolicyCard(
    cfg: Config,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
) {
    val promptPreview = remember(cfg.systemPrompt) {
        val compact = cfg.systemPrompt
            .trim()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("\n")
        if (compact.length > 120) compact.take(120) + "…" else compact
    }
    SettingsCard(title = "回复策略") {
        ValueRow(
            title = "系统提示词",
            value = promptPreview,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "系统提示词",
                        initial = cfg.systemPrompt,
                        multiline = true,
                        onApply = { value -> update { it.copy(systemPrompt = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "我的昵称",
            value = cfg.myNickname,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "我的昵称",
                        initial = cfg.myNickname,
                        placeholder = "用于识别群聊里的 @我",
                        onApply = { value -> update { it.copy(myNickname = value.trim()) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "群里只有被 @ 才回复",
            subtitle = "群聊里仅当消息 @ 我的昵称 时才触发",
            checked = cfg.groupOnlyWhenMentioned,
            onCheckedChange = { value -> update { it.copy(groupOnlyWhenMentioned = value) } },
        )
        ValueRow(
            title = "冷却时间",
            value = "${cfg.cooldownSeconds} 秒",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "冷却时间（秒）",
                        initial = cfg.cooldownSeconds,
                        min = 0,
                        max = 3600,
                        onApply = { value -> update { it.copy(cooldownSeconds = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "每小时回复上限",
            value = "${cfg.maxPerHour} 条",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "每小时回复上限（条）",
                        initial = cfg.maxPerHour,
                        min = 0,
                        max = 1000,
                        onApply = { value -> update { it.copy(maxPerHour = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "单条回复最大字数",
            value = "${cfg.maxReplyChars} 字",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "单条回复最大字数",
                        initial = cfg.maxReplyChars,
                        min = 1,
                        max = 10000,
                        onApply = { value -> update { it.copy(maxReplyChars = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "随机延迟区间",
            value = "${cfg.replyDelayMinMs} ~ ${cfg.replyDelayMaxMs} 毫秒",
            onClick = {
                edit(
                    EditorSpec.Delay(
                        initialMin = cfg.replyDelayMinMs,
                        initialMax = cfg.replyDelayMaxMs,
                        onApply = { min, max ->
                            update { it.copy(replyDelayMinMs = min, replyDelayMaxMs = max) }
                        },
                    )
                )
            },
        )
    }
}

@Composable
private fun MemoryAndAgentCard(
    cfg: Config,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
) {
    SettingsCard(title = "记忆与 Agent 操控") {
        SwitchRow(
            title = "启用会话与长期记忆",
            subtitle = "本地 SQLite 持久化多轮对话与永久事实档案（发 #重置 可清空）",
            checked = cfg.enableMemory,
            onCheckedChange = { value -> update { it.copy(enableMemory = value) } },
        )
        ValueRow(
            title = "多轮上下文轮数",
            value = "${cfg.maxHistoryTurns} 轮（最多 ${cfg.maxHistoryTurns * 2} 条消息）",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "多轮上下文保留轮数",
                        initial = cfg.maxHistoryTurns,
                        min = 1,
                        max = 50,
                        onApply = { value -> update { it.copy(maxHistoryTurns = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "会话超时分割",
            value = if (cfg.sessionExpireMinutes <= 0) "永不过期" else "${cfg.sessionExpireMinutes} 分钟",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "会话超时分钟数（0 = 永不过期）",
                        initial = cfg.sessionExpireMinutes,
                        min = 0,
                        max = 10080,
                        onApply = { value -> update { it.copy(sessionExpireMinutes = value) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "启用跨对话 Agent 操控",
            subtitle = "允许 AI 跨对话查阅消息、搜索联系人、代发消息与存取长期记忆",
            checked = cfg.enableAgentTools,
            onCheckedChange = { value -> update { it.copy(enableAgentTools = value) } },
        )
        ValueRow(
            title = "主人标识（特权限定）",
            value = cfg.masterTalkers.ifBlank { "未限制（白名单内均可触发操控）" },
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "主人标识（wxid 或备注/昵称，逗号分隔）",
                        initial = cfg.masterTalkers,
                        placeholder = "留空则白名单内均可触发，或填你的昵称/wxid",
                        onApply = { value -> update { it.copy(masterTalkers = value.trim()) } },
                    )
                )
            },
        )
    }
}

@Composable
private fun WhitelistCard(
    cfg: Config,
    snapshot: ConvSnapshot,
    refreshing: Boolean,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
) {
    val names = remember(cfg.whitelist, snapshot) {
        val nameByUsername = snapshot.items.associate { entry -> entry.username to entry.name }
        cfg.whitelist.map { username ->
            nameByUsername[username]?.takeIf { it.isNotBlank() } ?: username
        }
    }
    val preview = when {
        names.isEmpty() -> "尚未选择，点这里从微信会话列表勾选"
        names.size <= 3 -> names.joinToString("、")
        else -> names.take(3).joinToString("、") + "…"
    }
    val statusLine = when {
        snapshot.error != null -> "上次读取失败，可点此重试"
        snapshot.timestamp == 0L -> "尚未读取过会话列表"
        else -> "已读取 ${snapshot.items.size} 个会话 · ${formatTime(snapshot.timestamp)}"
    }
    SettingsCard(title = "白名单会话") {
        ValueRow(
            title = "已选 ${cfg.whitelist.size} 个会话",
            value = preview,
            onClick = onManage,
        )
        ValueRow(
            title = "管理白名单",
            value = "从微信会话列表勾选",
            onClick = onManage,
        )
        SettingRow(
            title = "从微信读取会话列表",
            subtitle = statusLine,
            onClick = { if (!refreshing) onRefresh() },
            trailing = {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        val error = snapshot.error
        if (error != null) {
            SnapshotErrorNotice(message = error, refreshing = refreshing, onRetry = onRefresh)
        }
    }
}

@Composable
private fun SnapshotErrorNotice(message: String, refreshing: Boolean, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "读取会话列表失败",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = { if (!refreshing) onRetry() },
                enabled = !refreshing,
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun DebugCard(
    debug: Boolean,
    onDebugChange: (Boolean) -> Unit,
    moduleVersion: String,
) {
    SettingsCard(title = "调试与关于") {
        SwitchRow(
            title = "调试日志",
            subtitle = "让微信进程把命中/发送细节写入 Xposed 日志",
            checked = debug,
            onCheckedChange = onDebugChange,
        )
        InfoRow(title = "模块版本", value = moduleVersion)
        InfoRow(title = "支持的微信版本", value = DexMap.SUPPORTED_VERSION_NAME)
        InfoRow(title = "会话映射来源", value = "WeKit Dex-Test")
    }
}

private fun formatTime(timestamp: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
} catch (_: Throwable) {
    "?"
}

@Suppress("DEPRECATION")
private fun moduleVersionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1"
} catch (_: Throwable) {
    "1.1"
}

/**
 * 设置行的编辑目标。确认后通过 onApply 写回 Config。
 */
private sealed interface EditorSpec {
    data class Text(
        val title: String,
        val initial: String,
        val multiline: Boolean = false,
        val password: Boolean = false,
        val keyboardType: KeyboardType = KeyboardType.Text,
        val placeholder: String? = null,
        val onApply: (String) -> Unit,
    ) : EditorSpec

    data class Number(
        val title: String,
        val initial: Int,
        val min: Int,
        val max: Int,
        val onApply: (Int) -> Unit,
    ) : EditorSpec

    data class Delay(
        val initialMin: Int,
        val initialMax: Int,
        val onApply: (Int, Int) -> Unit,
    ) : EditorSpec
}
