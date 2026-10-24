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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cheng.wxai.AiClient
import dev.cheng.wxai.Config
import dev.cheng.wxai.ConvSnapshot
import dev.cheng.wxai.ConversationClient
import dev.cheng.wxai.DexMap
import dev.cheng.wxai.Prefs
import dev.cheng.wxai.SessionManager
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
    var lifeSnapshot by remember { mutableStateOf(ConversationClient.peekLifeSnapshot(context)) }
    var refreshing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var pickingWhitelist by remember { mutableStateOf(false) }
    var pickingMasters by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorSpec?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val wechatInstalled = remember { ConversationClient.isWechatInstalled(context) }
    val wechatVersion = remember { ConversationClient.wechatVersionName(context) }
    val moduleVersion = remember { moduleVersionName(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val update: ((Config) -> Config) -> Unit = { transform ->
        cfg = transform(cfg)
    }

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
                lifeSnapshot = ConversationClient.peekLifeSnapshot(context)
                refreshing = false
                // 微信内如果通过 #认主 修改了配置，同步拉取最新 Config
                cfg = Prefs.load(context)
                if (showToast) {
                    snackbarHostState.showSnackbar(
                        if (result.error != null) "同步失败" else "已同步微信会话与生命状态（${result.items.size} 个会话）"
                    )
                }
            }
        }
    }

    val refreshLifeOnly: () -> Unit = {
        lifeSnapshot = ConversationClient.peekLifeSnapshot(context)
        cfg = Prefs.load(context)
        refresh(true)
    }

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
                        val result = AiClient.complete(request, "你好，现在在忙什么呢？")
                        val text = result.text
                        if (!text.isNullOrBlank()) {
                            val cacheNote = if (result.promptTokens > 0) {
                                " (缓存 ${result.cacheHitTokens}/${result.promptTokens})"
                            } else {
                                ""
                            }
                            "连接成功$cacheNote：${text.replace("||", " / ").take(45)}"
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

    LaunchedEffect(cfg) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        delay(400)
        try {
            Prefs.save(context, cfg)
            snackbarHostState.showSnackbar("配置已保存并实时同步至微信")
        } catch (t: Throwable) {
            snackbarHostState.showSnackbar("保存失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    LaunchedEffect(Unit) {
        lifeSnapshot = ConversationClient.peekLifeSnapshot(context)
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
            Column {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("微信 AI · 完整的一生")
                            Text(
                                text = "KV-Cache 长记忆引擎 & 全能微信操控 v$moduleVersion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            lifeSnapshot = ConversationClient.peekLifeSnapshot(context)
                            cfg = Prefs.load(context)
                        },
                        text = { Text("✨ 记忆与一生") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("🕹️ 操控与策略") },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("⚙️ 模型与服务") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (selectedTab) {
                0 -> SoulAndLifeTab(
                    cfg = cfg,
                    life = lifeSnapshot,
                    snapshot = snapshot,
                    refreshing = refreshing,
                    update = update,
                    edit = { spec -> editor = spec },
                    onPickMasters = { pickingMasters = true },
                    onRefreshLife = refreshLifeOnly,
                )

                1 -> AgentAndPolicyTab(
                    cfg = cfg,
                    snapshot = snapshot,
                    refreshing = refreshing,
                    update = update,
                    edit = { spec -> editor = spec },
                    onPickMasters = { pickingMasters = true },
                    onManageWhitelist = { pickingWhitelist = true },
                    onRefreshWhitelist = { refresh(true) },
                )

                2 -> ModelAndServiceTab(
                    cfg = cfg,
                    wechatInstalled = wechatInstalled,
                    wechatVersion = wechatVersion,
                    moduleVersion = moduleVersion,
                    testing = testing,
                    update = update,
                    edit = { spec -> editor = spec },
                    onTest = testConnection,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (pickingWhitelist) {
        ConversationPicker(
            initialSelected = cfg.whitelist.toSet(),
            snapshot = snapshot,
            refreshing = refreshing,
            onRefresh = { refresh(true) },
            onConfirm = { selected ->
                update { it.copy(whitelist = selected) }
                pickingWhitelist = false
            },
            onDismiss = { pickingWhitelist = false },
        )
    }

    if (pickingMasters) {
        val currentMasterSet = remember(cfg.masterTalkers) {
            cfg.masterTalkers.split(',', '，', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        ConversationPicker(
            initialSelected = currentMasterSet,
            snapshot = snapshot,
            refreshing = refreshing,
            onRefresh = { refresh(true) },
            onConfirm = { selectedWxids ->
                // 同时将选中主人的会话自动加入白名单，确保主人发消息直接响应
                val mergedWhitelist = (cfg.whitelist + selectedWxids).distinct()
                update {
                    it.copy(
                        masterTalkers = selectedWxids.joinToString(", "),
                        whitelist = mergedWhitelist,
                    )
                }
                pickingMasters = false
            },
            onDismiss = { pickingMasters = false },
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

            is EditorSpec.Choice -> ChoiceDialog(
                title = spec.title,
                options = spec.options,
                current = spec.current,
                onDismiss = { editor = null },
                onSelect = { selected ->
                    editor = null
                    spec.onApply(selected)
                },
            )
        }
    }
}

// ============================================================================
// Tab 0: 记忆与一生 (实时心境、主人身份、好感度图谱、人生日记、永久记忆)
// ============================================================================

@Composable
private fun SoulAndLifeTab(
    cfg: Config,
    life: SessionManager.LifeSnapshot,
    snapshot: ConvSnapshot,
    refreshing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onPickMasters: () -> Unit,
    onRefreshLife: () -> Unit,
) {
    val self = life.selfState

    val masterDisplay = remember(cfg.masterTalkers, snapshot) {
        val raw = cfg.masterTalkers.trim()
        if (raw.isEmpty()) {
            "尚未绑定（点此从微信列表选择，或在微信发 #认主 自动绑定）"
        } else {
            val nameMap = snapshot.items.associate { it.username to it.name }
            raw.split(',', '，', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("、") { token ->
                    val mapped = nameMap[token]
                    if (!mapped.isNullOrBlank() && mapped != token) "$mapped ($token)" else token
                }
        }
    }

    // 1. 实时心境与状态卡片
    SettingsCard(title = "实时心境与自我状态") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "心情：${self.mood}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "当前状态：${self.activity}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { if (!refreshing) onRefreshLife() }) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新生命状态",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "精力值 ${self.energy}/100",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                )
                LinearProgressIndicator(
                    progress = { (self.energy / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ) {
                Text(
                    text = "💭 内心独白：「${self.monologue.ifBlank { "随时待命，记录每一次对话与故事。" }}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Text(
                text = "累计对话消息 ${life.totalMessagesCount} 条 · 社交档案 ${life.dossiers.size} 人 · 日记 ${life.diaries.size} 篇 · 永久记忆 ${life.memories.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 2. 主人绑定与表达设定卡片（无预设虚构人设）
    val bioPreview = remember(cfg.personaBiography) {
        if (cfg.personaBiography.isBlank()) {
            "未设置（默认不加载任何虚构人设，点此可自定义背景设定）"
        } else {
            val compact = cfg.personaBiography.trim().lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")
            if (compact.length > 140) compact.take(140) + "…" else compact
        }
    }
    val promptPreview = remember(cfg.systemPrompt) {
        val compact = cfg.systemPrompt.trim().lineSequence().filter { it.isNotBlank() }.take(2).joinToString("\n")
        if (compact.length > 110) compact.take(110) + "…" else compact
    }

    SettingsCard(title = "唯一主人绑定与性格设定") {
        ValueRow(
            title = "唯一主人（从微信会话列表点选）",
            value = masterDisplay,
            onClick = onPickMasters,
        )
        ValueRow(
            title = "微信聊天表达守则（System Prompt）",
            value = promptPreview,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "微信聊天表达守则",
                        initial = cfg.systemPrompt,
                        multiline = true,
                        onApply = { value -> update { it.copy(systemPrompt = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "自定义背景设定（可选，留空即无预设人设）",
            value = bioPreview,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "自定义背景设定（留空则不强加任何虚构人设）",
                        initial = cfg.personaBiography,
                        multiline = true,
                        placeholder = "留空即可；或填入你希望赋予TA的自定义身份背景",
                        onApply = { value -> update { it.copy(personaBiography = value.trim()) } },
                    )
                )
            },
        )
    }

    // 人格风格与深度交互（DeepSeek QQ 同款体验）
    SettingsCard(title = "人格风格与深度交互（DeepSeek QQ 同款体验）") {
        ValueRow(
            title = "人格风格预设（微信发 #人设 也可一键切换）",
            value = cfg.personaPresetLabel(),
            onClick = {
                edit(
                    EditorSpec.Choice(
                        title = "选择人格风格预设",
                        options = listOf(
                            "group" to "🤪 机智网瘾群友（默认，高网感幽默接梗）",
                            "maid" to "🐱 傲娇忠犬赛博女仆（对主人极度顺从护短）",
                            "tsundere" to "😈 毒舌腹黑损友（神级吐槽、嘴硬心软）",
                            "catgirl" to "🌸 软萌可爱猫娘（甜美黏人、句尾带喵~）",
                            "butler" to "🎩 全能高智商管家（从容优雅、全能靠谱）",
                        ),
                        current = cfg.personaPreset,
                        onApply = { value -> update { it.copy(personaPreset = value) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "微信拍一拍趣味反拍与互动",
            subtitle = "有人拍你时自动在微信反拍对方，并附带傲娇/机智神回复",
            checked = cfg.enablePatResponse,
            onCheckedChange = { value -> update { it.copy(enablePatResponse = value) } },
        )
        SwitchRow(
            title = "群聊优先微信原生引用回复",
            subtitle = "在群聊被 @ 时，第一条气泡自动引用对方消息卡片发出，让群聊互动更清晰",
            checked = cfg.enableQuoteReply,
            onCheckedChange = { value -> update { it.copy(enableQuoteReply = value) } },
        )
    }

    // 3. 社交关系与好感度图谱
    SettingsCard(title = "社交关系与好感度图谱 (${life.dossiers.size})") {
        if (life.dossiers.isEmpty()) {
            SettingRow(
                title = "尚无人物好感度档案",
                subtitle = "在微信发送 #认主 会自动建立「唯一主人 (100/100)」档案；日常聊满 ${cfg.autoReflectionTurns} 轮也会自动生成对每个人的好感度与性格印象。",
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (d in life.dossiers.take(15)) {
                    DossierItemCard(d)
                }
            }
        }
    }

    // 4. 人生日记本
    SettingsCard(title = "第一人称人生日记 (${life.diaries.size})") {
        if (life.diaries.isEmpty()) {
            SettingRow(
                title = "日记本暂无记录",
                subtitle = "经历有趣或重要的微信对话后会自动写下第一人称日记（在微信发 #日记 可直接查看）。",
            )
        } else {
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (diary in life.diaries.take(10)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "📒 心情：${diary.mood}" +
                                        (if (diary.relatedPerson.isNotBlank()) " · 关于 ${diary.relatedPerson}" else ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = fmt.format(Date(diary.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = diary.entry,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }

    // 5. 永久记忆库
    SettingsCard(title = "跨对话永久事实记忆 (${life.memories.size})") {
        if (life.memories.isEmpty()) {
            SettingRow(
                title = "暂无永久事实记忆",
                subtitle = "聊天中提到的重要事实、约定、身份会被自动提炼并永久铭记。",
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (m in life.memories.take(20)) {
                    Text(
                        text = "• [${if (m.scope == "global") "全局" else m.scope}] ${m.fact}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DossierItemCard(d: SessionManager.ContactDossier) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${d.displayName} · ${d.relationship}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "❤️ ${d.affection}/100",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (d.affection / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
            if (d.profileSummary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "印象：${d.profileSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ============================================================================
// Tab 1: 操控与策略 (主人特权、Prefix KV Cache 长上下文、防抖聚合、白名单)
// ============================================================================

@Composable
private fun AgentAndPolicyTab(
    cfg: Config,
    snapshot: ConvSnapshot,
    refreshing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onPickMasters: () -> Unit,
    onManageWhitelist: () -> Unit,
    onRefreshWhitelist: () -> Unit,
) {
    SettingsCard(title = "主人身份识别与全能微信 Agent（12 大工具）") {
        SwitchRow(
            title = "启用全能微信 Agent 工具流",
            subtitle = "支持跨会话代发消息、查聊天记录、全库搜消息、拍一拍、引用回复、发名片、撤回消息与发朋友圈",
            checked = cfg.enableAgentTools,
            onCheckedChange = { value -> update { it.copy(enableAgentTools = value) } },
        )
        ValueRow(
            title = "从微信会话列表选择主人",
            value = cfg.masterTalkers.ifBlank { "未绑定（点此从微信列表勾选你的微信号，或在微信发 #认主）" },
            onClick = onPickMasters,
        )
        ValueRow(
            title = "手动编辑主人标识（wxid 或备注/昵称）",
            value = cfg.masterTalkers.ifBlank { "留空则白名单内均可触发工具；填你的标识则仅主人拥有跨群操控权" },
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "主人标识（wxid 或昵称，逗号分隔）",
                        initial = cfg.masterTalkers,
                        placeholder = "也可直接在微信聊天中发送 #认主 自动绑定",
                        onApply = { value -> update { it.copy(masterTalkers = value.trim()) } },
                    )
                )
            },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "三大交互方式与快捷指令：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "1. 微信内认主：用你的微信号给TA发一句「#认主」（或在群里 @TA #认主），即可一键绑定为唯一主人(好感度100)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "2. 文件传输助手直通：将「文件传输助手」勾选进白名单后，直接在文件传输助手打字即可免前缀指挥TA。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "3. 快捷指令：支持「@ai 帮我给xxx发消息…」「#认主」「#状态」「#日记」「#好感度」「#记忆」「#重置」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsCard(title = "长上下文与 Prefix KV 缓存优化") {
        SwitchRow(
            title = "启用多轮记忆与分块 KV 缓存锚定",
            subtitle = "保持 System Prompt 与历史前缀 100% 静态追加，DeepSeek 缓存命中率可达 90%+，大幅降低延迟与 Token 消耗",
            checked = cfg.enableMemory,
            onCheckedChange = { value -> update { it.copy(enableMemory = value) } },
        )
        ValueRow(
            title = "单会话长上下文轮数",
            value = "${cfg.maxHistoryTurns} 轮（保留 ${cfg.maxHistoryTurns * 2} 条消息，分块滚动不破坏缓存）",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "多轮上下文保留轮数（10 ~ 500 轮）",
                        initial = cfg.maxHistoryTurns,
                        min = 10,
                        max = 500,
                        onApply = { value -> update { it.copy(maxHistoryTurns = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "会话超时分割",
            value = if (cfg.sessionExpireMinutes <= 0) "0 分钟（永不断片，跨天保持完整上下文与缓存）" else "${cfg.sessionExpireMinutes} 分钟不聊开启新窗口",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "会话超时分钟数（推荐填 0 = 永不断片）",
                        initial = cfg.sessionExpireMinutes,
                        min = 0,
                        max = 10080,
                        onApply = { value -> update { it.copy(sessionExpireMinutes = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "后台静默反思与写日记间隔",
            value = if (cfg.autoReflectionTurns <= 0) "已关闭自动反思" else "每聊满 ${cfg.autoReflectionTurns} 轮自动更新心境、好感度与日记",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "自动反思轮数间隔（0 = 关闭）",
                        initial = cfg.autoReflectionTurns,
                        min = 0,
                        max = 50,
                        onApply = { value -> update { it.copy(autoReflectionTurns = value) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "真人多气泡分段发送",
            subtitle = "将 || 分隔或多行短句拆成 2~3 个微信气泡依次发出，模拟真人打字停顿",
            checked = cfg.enableMultiBubble,
            onCheckedChange = { value -> update { it.copy(enableMultiBubble = value) } },
        )
    }

    WhitelistCard(
        cfg = cfg,
        snapshot = snapshot,
        refreshing = refreshing,
        onManage = onManageWhitelist,
        onRefresh = onRefreshWhitelist,
    )

    SettingsCard(title = "防抖聚合与群聊触发策略") {
        ValueRow(
            title = "我的微信昵称（留空自动读取本机微信号昵称）",
            value = cfg.myNickname.ifBlank { "自动识别当前登录微信号昵称" },
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "我的微信昵称（留空则自动从微信数据库读取）",
                        initial = cfg.myNickname,
                        placeholder = "留空即可自动识别群聊 @我",
                        onApply = { value -> update { it.copy(myNickname = value.trim()) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "群里只有被 @ 才回复",
            subtitle = "群聊被 @ 时会自动附带群内刚才大家的聊天背景，不会答非所问",
            checked = cfg.groupOnlyWhenMentioned,
            onCheckedChange = { value -> update { it.copy(groupOnlyWhenMentioned = value) } },
        )
        ValueRow(
            title = "连发消息防抖合并等待区间",
            value = "${cfg.replyDelayMinMs} ~ ${cfg.replyDelayMaxMs} 毫秒（对方连发多句自动合并为一轮）",
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
        ValueRow(
            title = "两次回复最小间隔（平滑等待不丢消息）",
            value = "${cfg.cooldownSeconds} 秒",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "最小间隔（秒）",
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
            title = "单次回复最大字数",
            value = "${cfg.maxReplyChars} 字",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "单次回复最大字数",
                        initial = cfg.maxReplyChars,
                        min = 1,
                        max = 10000,
                        onApply = { value -> update { it.copy(maxReplyChars = value) } },
                    )
                )
            },
        )
    }
}

// ============================================================================
// Tab 2: 模型与服务 (运行状态、DeepSeek/OpenAI 接口、调试日志)
// ============================================================================

@Composable
private fun ModelAndServiceTab(
    cfg: Config,
    wechatInstalled: Boolean,
    wechatVersion: String?,
    moduleVersion: String,
    testing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onTest: () -> Unit,
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
        edit = edit,
        onTest = onTest,
    )
    VisionServiceCard(
        cfg = cfg,
        update = update,
        edit = edit,
    )
    DebugCard(
        debug = cfg.debug,
        onDebugChange = { value -> update { it.copy(debug = value) } },
        moduleVersion = moduleVersion,
    )
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
        versionName == supported -> "$versionName（已完美适配）"
        else -> "$versionName（当前适配目标：$supported）"
    }
    val versionColor = when {
        !installed || versionName.isNullOrBlank() -> MaterialTheme.colorScheme.error
        versionName == supported -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    SettingsCard(title = "运行状态与总开关") {
        InfoRow(
            title = "微信状态",
            value = if (installed) "已安装" else "未安装",
            valueColor = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        SettingRow(
            title = "微信版本",
            subtitle = versionSummary,
            subtitleColor = versionColor,
        )
        SwitchRow(
            title = "启用自动回复与 Agent 操控",
            subtitle = "关闭后微信内暂停一切自动回复与工具调用",
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
    SettingsCard(title = "大模型服务（OpenAI / DeepSeek 兼容）") {
        ValueRow(
            title = "服务地址（Base URL）",
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
            title = "模型名称（需支持 Function Calling）",
            value = cfg.model,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "模型名称",
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
            title = "测试连接并查看缓存状态",
            subtitle = "用当前设定向接口发一条「你好，现在在忙什么呢？」",
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
private fun VisionServiceCard(
    cfg: Config,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
) {
    SettingsCard(title = "多模态视觉与表情包识图（DeepSeek 接入 QQ 同款体验）") {
        SwitchRow(
            title = "启用图片与表情包识图理解",
            subtitle = "截获微信表情包 (type=47) 与图片 (type=3) 转为高精画面描述并送入 AI，实现懂图懂梗",
            checked = cfg.enableVision,
            onCheckedChange = { value -> update { it.copy(enableVision = value) } },
        )
        if (cfg.enableVision) {
            ValueRow(
                title = "视觉模型（默认 deepseek-flash）",
                value = cfg.visionModel.ifBlank { "默认 deepseek-flash" },
                onClick = {
                    edit(
                        EditorSpec.Text(
                            title = "视觉模型名称",
                            initial = cfg.visionModel,
                            placeholder = "deepseek-flash",
                            onApply = { value -> update { it.copy(visionModel = value.trim()) } },
                        )
                    )
                },
            )
            ValueRow(
                title = "视觉接口 Base URL（留空沿用主模型）",
                value = cfg.visionBaseUrl.ifBlank { "沿用主模型接口" },
                onClick = {
                    edit(
                        EditorSpec.Text(
                            title = "视觉接口 Base URL",
                            initial = cfg.visionBaseUrl,
                            keyboardType = KeyboardType.Uri,
                            placeholder = "https://api.siliconflow.cn/v1",
                            onApply = { value -> update { it.copy(visionBaseUrl = value.trim()) } },
                        )
                    )
                },
            )
            ValueRow(
                title = "视觉接口 API Key（留空沿用主模型）",
                value = if (cfg.visionApiKey.isBlank()) "沿用主模型 API Key" else "已单独设置（点此修改）",
                onClick = {
                    edit(
                        EditorSpec.Text(
                            title = "视觉接口 API Key",
                            initial = cfg.visionApiKey,
                            password = true,
                            keyboardType = KeyboardType.Password,
                            placeholder = "sk-…",
                            onApply = { value -> update { it.copy(visionApiKey = value.trim()) } },
                        )
                    )
                },
            )
        }
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
                text = "灵动度 temperature",
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
        else -> names.take(3).joinToString("、") + " 等 ${names.size} 个会话"
    }
    val statusLine = when {
        snapshot.error != null -> "上次读取失败，可点此重试"
        snapshot.timestamp == 0L -> "尚未读取过会话列表"
        else -> "已读取 ${snapshot.items.size} 个会话 · ${formatTime(snapshot.timestamp)}"
    }
    SettingsCard(title = "白名单会话") {
        ValueRow(
            title = "已启用 ${cfg.whitelist.size} 个自动回复会话",
            value = preview,
            onClick = onManage,
        )
        ValueRow(
            title = "管理白名单会话",
            value = "从微信会话列表勾选私聊或群聊",
            onClick = onManage,
        )
        SettingRow(
            title = "从微信同步会话列表与生命状态",
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
            title = "调试日志与缓存命中率 Toast 提示",
            subtitle = "回复后在 Toast 和 Xposed 日志中显示本次 Prefix KV Cache 命中百分比",
            checked = debug,
            onCheckedChange = onDebugChange,
        )
        InfoRow(title = "模块版本", value = moduleVersion)
        InfoRow(title = "支持的微信版本", value = DexMap.SUPPORTED_VERSION_NAME)
        InfoRow(title = "底层操控引擎", value = "SQLite Hook + WeKit Bridge + KV-Cache Anchor")
    }
}

private fun formatTime(timestamp: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
} catch (_: Throwable) {
    "?"
}

@Suppress("DEPRECATION")
private fun moduleVersionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.1.0"
} catch (_: Throwable) {
    "2.1.0"
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

    data class Choice(
        val title: String,
        val options: List<Pair<String, String>>,
        val current: String,
        val onApply: (String) -> Unit,
    ) : EditorSpec
}
