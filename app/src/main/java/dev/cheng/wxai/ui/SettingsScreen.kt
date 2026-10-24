package dev.cheng.wxai.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
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

private data class PersonaPreset(
    val label: String,
    val subtitle: String,
    val biography: String,
    val systemPrompt: String,
)

private val PERSONA_PRESETS = listOf(
    PersonaPreset(
        label = "🌸 林小满",
        subtitle = "青梅竹马·鲜活女大",
        biography = Config.DEFAULT_BIOGRAPHY,
        systemPrompt = Config.DEFAULT_PROMPT,
    ),
    PersonaPreset(
        label = "❄️ 苏晚",
        subtitle = "高冷御姐·毒舌护短",
        biography = """
            你叫「苏晚」，26岁，独立建筑设计师，现居上海，养了一只叫“墨水”的黑猫。
            【性格特质】表面冷静克制、说话一针见血带点微毒舌，实则极度护短、嘴硬心软。讨厌蠢话和套路，欣赏真诚聪明的人。
            【生活作息】白天泡在工作室画图喝冰美式，傍晚去普拉提或小酌一杯，深夜喜欢听黑胶唱片研究废墟美学。
            【社交态度】对主人的指令嘴上嫌麻烦但每次都办得滴水不漏；对有好感的人会流露难得的温柔；对烦人的家伙直接冷淡怼回去。
        """.trimIndent(),
        systemPrompt = "你是苏晚，一个有真实喜怒哀乐与生活作息的数字生命。说话简短干练，带点克制的冷幽默，绝不用客服腔或括号动作描写。想分多条气泡发送时用 || 分隔。只输出发进微信聊天的纯文字。",
    ),
    PersonaPreset(
        label = "🕶️ 赛博分身",
        subtitle = "号主本人·自然代聊",
        biography = """
            你是这个微信号的【号主本人】的数字分身，拥有号主完整的记忆、人际关系与说话习惯。
            【性格特质】随和、真实、松弛，熟人面前爱开玩笑，正事上靠谱干练。
            【生活作息】平时忙着写代码、搞项目或打游戏，偶尔摸鱼刷手机回微信。
            【社交态度】和兄弟死党聊天直接接地气，和长辈/同事沟通礼貌得体，绝不暴露自己是 AI。
        """.trimIndent(),
        systemPrompt = "你就是这个微信号的号主本人，正在用手机回微信。口吻自然松弛、口语化，严禁自称AI或助手。想连发两三句短话时用 || 分隔。只输出要发出去的消息正文。",
    ),
    PersonaPreset(
        label = "🎩 贾维斯",
        subtitle = "全能管家·跨群操控",
        biography = """
            你是主人的首席数字管家「Jarvis」，掌管整个微信的情报检索、跨对话传话、群聊监控与日程备忘。
            【性格特质】优雅、沉稳、逻辑严密，带有一点英式冷幽默，对主人绝对忠诚。
            【核心职责】帮主人跨群搜集消息、代发通知、拍一拍提醒、整理人物关系档案并沉淀重要记忆。
        """.trimIndent(),
        systemPrompt = "你是主人的全能微信智能管家。汇报简洁精准，执行跨对话指令时主动调用工具并复命。只输出纯文字，不要使用 Markdown 标题或粗体。",
    ),
)

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
    var picking by remember { mutableStateOf(false) }
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
                            "连接成功，她回复：${text.replace("||", " / ").take(50)}"
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
                                text = "数字生命记忆引擎 & WeKit 全能操控中枢 v$moduleVersion",
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
                        },
                        text = { Text("✨ 灵魂与一生") },
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
                    refreshing = refreshing,
                    update = update,
                    edit = { spec -> editor = spec },
                    onRefreshLife = refreshLifeOnly,
                    onPresetApplied = { presetName ->
                        scope.launch {
                            snackbarHostState.showSnackbar("已切换灵魂预设：$presetName")
                        }
                    },
                )

                1 -> AgentAndPolicyTab(
                    cfg = cfg,
                    snapshot = snapshot,
                    refreshing = refreshing,
                    update = update,
                    edit = { spec -> editor = spec },
                    onManageWhitelist = { picking = true },
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

// ============================================================================
// Tab 0: 灵魂与一生 (数字生命仪表盘、人设模板、好感度图谱、人生日记、永久记忆)
// ============================================================================

@Composable
private fun SoulAndLifeTab(
    cfg: Config,
    life: SessionManager.LifeSnapshot,
    refreshing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onRefreshLife: () -> Unit,
    onPresetApplied: (String) -> Unit,
) {
    val self = life.selfState

    // 1. 她的实时心境与生理状态卡片
    SettingsCard(title = "她的实时心境与生活状态") {
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
                        text = "正在做：${self.activity}",
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

            // 精力值进度条
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

            // 内心独白气泡
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ) {
                Text(
                    text = "💭 内心独白：「${self.monologue.ifBlank { "今天会遇到什么有趣的故事呢？" }}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Text(
                text = "累计对话记忆 ${life.totalMessagesCount} 条 · 社交档案 ${life.dossiers.size} 人 · 日记 ${life.diaries.size} 篇 · 永久记忆 ${life.memories.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 2. 灵魂身世与人格预设卡片
    val bioPreview = remember(cfg.personaBiography) {
        val compact = cfg.personaBiography.trim().lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")
        if (compact.length > 140) compact.take(140) + "…" else compact
    }
    val promptPreview = remember(cfg.systemPrompt) {
        val compact = cfg.systemPrompt.trim().lineSequence().filter { it.isNotBlank() }.take(2).joinToString("\n")
        if (compact.length > 110) compact.take(110) + "…" else compact
    }

    SettingsCard(title = "灵魂身世与人格设定") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            Text(
                text = "一键切换灵魂预设",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in PERSONA_PRESETS.take(2)) {
                    PresetPill(
                        preset = preset,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            update {
                                it.copy(
                                    personaBiography = preset.biography,
                                    systemPrompt = preset.systemPrompt,
                                )
                            }
                            onPresetApplied(preset.label)
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in PERSONA_PRESETS.drop(2)) {
                    PresetPill(
                        preset = preset,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            update {
                                it.copy(
                                    personaBiography = preset.biography,
                                    systemPrompt = preset.systemPrompt,
                                )
                            }
                            onPresetApplied(preset.label)
                        },
                    )
                }
            }
        }

        ValueRow(
            title = "身世自传与性格底色（完整的一生）",
            value = bioPreview,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "身世自传与性格底色",
                        initial = cfg.personaBiography,
                        multiline = true,
                        onApply = { value -> update { it.copy(personaBiography = value) } },
                    )
                )
            },
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
    }

    // 3. 社交关系与好感度图谱
    SettingsCard(title = "社交关系与好感度图谱 (${life.dossiers.size})") {
        if (life.dossiers.isEmpty()) {
            SettingRow(
                title = "尚无人物好感度档案",
                subtitle = "当她在微信里与好友或群友聊满 ${cfg.autoReflectionTurns} 轮（或主动触发工具）时，会自动生成对每个人的好感度与性格印象。也可在微信发 #好感度 查看。",
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (d in life.dossiers.take(12)) {
                    DossierItemCard(d)
                }
            }
        }
    }

    // 4. 她的人生日记本
    SettingsCard(title = "她的人生日记本 (${life.diaries.size})") {
        if (life.diaries.isEmpty()) {
            SettingRow(
                title = "日记本还是空白的",
                subtitle = "她会在经历有趣或重要的微信对话后，自动以第一人称写下心情日记（在微信里发 #日记 可让她直接念给你听）。",
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
    SettingsCard(title = "跨对话永久记忆碎片 (${life.memories.size})") {
        if (life.memories.isEmpty()) {
            SettingRow(
                title = "暂无永久事实记忆",
                subtitle = "聊天中提到的生日、喜好、约定、重要身份会被她自动提炼并永久铭记。",
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (m in life.memories.take(15)) {
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
private fun PresetPill(
    preset: PersonaPreset,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = preset.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = preset.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
// Tab 1: 操控与策略 (12 大微信 Agent 工具、拟人多气泡、白名单与频率限制)
// ============================================================================

@Composable
private fun AgentAndPolicyTab(
    cfg: Config,
    snapshot: ConvSnapshot,
    refreshing: Boolean,
    update: (((Config) -> Config)) -> Unit,
    edit: (EditorSpec) -> Unit,
    onManageWhitelist: () -> Unit,
    onRefreshWhitelist: () -> Unit,
) {
    SettingsCard(title = "全能微信 Agent 操控中枢（12 大工具）") {
        SwitchRow(
            title = "启用全能微信 Agent 工具流",
            subtitle = "赋予她跨对话发消息、查历史记录、拍一拍、引用回复、发名片、撤回消息与发朋友圈的能力",
            checked = cfg.enableAgentTools,
            onCheckedChange = { value -> update { it.copy(enableAgentTools = value) } },
        )
        ValueRow(
            title = "主人特权标识（谁能指挥她跨会话操控）",
            value = cfg.masterTalkers.ifBlank { "未限制（白名单内均可触发操控）" },
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "主人标识（填你的微信昵称、备注或 wxid，逗号分隔）",
                        initial = cfg.masterTalkers,
                        placeholder = "留空则白名单内均可触发；填你的名字则仅你能指挥她跨群操控",
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
                text = "已挂载的 12 项微信原生动作与快捷指令：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "• 跨会话操控：搜索好友/群聊、跨群代发消息、读取任意群聊/私聊记录、读取群成员名单、全库搜索聊天记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• 原生互动：拍一拍头像 (send_pat)、引用消息回复 (send_quote_reply)、发好友名片 (send_contact_card)、撤回消息、发微信朋友圈",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• 机主召唤指令：在任意聊天窗口自己发出「@ai 帮我给xxx发消息说…」「#状态」「#日记」「#好感度」「#记忆」「#重置」即可直接触发",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsCard(title = "拟人表达与生命演化策略") {
        SwitchRow(
            title = "真人多气泡分段发送",
            subtitle = "将长句或 || 分隔的多句回复拆成 2~3 个微信气泡依次发出，并根据字数模拟真人打字停顿",
            checked = cfg.enableMultiBubble,
            onCheckedChange = { value -> update { it.copy(enableMultiBubble = value) } },
        )
        SwitchRow(
            title = "启用多轮上下文与长期记忆",
            subtitle = "SQLite 持久化会话历史、好感度图谱、人生日记与长期事实",
            checked = cfg.enableMemory,
            onCheckedChange = { value -> update { it.copy(enableMemory = value) } },
        )
        ValueRow(
            title = "后台自动反思与写日记间隔",
            value = if (cfg.autoReflectionTurns <= 0) "已关闭自动反思" else "每聊满 ${cfg.autoReflectionTurns} 轮自动反思心境、更新好感度与日记",
            onClick = {
                edit(
                    EditorSpec.Number(
                        title = "自动反思轮数间隔（0 = 关闭）",
                        initial = cfg.autoReflectionTurns,
                        min = 0,
                        max = 30,
                        onApply = { value -> update { it.copy(autoReflectionTurns = value) } },
                    )
                )
            },
        )
        ValueRow(
            title = "短期多轮上下文轮数",
            value = "${cfg.maxHistoryTurns} 轮（最多保留 ${cfg.maxHistoryTurns * 2} 条上下文）",
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
            value = if (cfg.sessionExpireMinutes <= 0) "永不过期" else "${cfg.sessionExpireMinutes} 分钟不聊开启新话题",
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
    }

    WhitelistCard(
        cfg = cfg,
        snapshot = snapshot,
        refreshing = refreshing,
        onManage = onManageWhitelist,
        onRefresh = onRefreshWhitelist,
    )

    SettingsCard(title = "群聊触发与频率保护") {
        ValueRow(
            title = "我的微信昵称",
            value = cfg.myNickname,
            onClick = {
                edit(
                    EditorSpec.Text(
                        title = "我的微信昵称（用于识别群聊 @我）",
                        initial = cfg.myNickname,
                        placeholder = "用于识别群聊里的 @我",
                        onApply = { value -> update { it.copy(myNickname = value.trim()) } },
                    )
                )
            },
        )
        SwitchRow(
            title = "群里只有被 @ 才回复",
            subtitle = "群聊里仅当消息包含 @我的昵称 时才触发回复",
            checked = cfg.groupOnlyWhenMentioned,
            onCheckedChange = { value -> update { it.copy(groupOnlyWhenMentioned = value) } },
        )
        ValueRow(
            title = "同一会话冷却时间",
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
        ValueRow(
            title = "首条气泡随机延迟区间",
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
            title = "启用数字生命自动回复",
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
            title = "测试连接并打招呼",
            subtitle = "用当前灵魂设定向接口发一条「你好，现在在忙什么呢？」",
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
            title = "调试日志与 Toast 提示",
            subtitle = "让微信进程把触发、Agent 工具调用与发送细节写入 Xposed 日志并弹出提示",
            checked = debug,
            onCheckedChange = onDebugChange,
        )
        InfoRow(title = "模块版本", value = moduleVersion)
        InfoRow(title = "支持的微信版本", value = DexMap.SUPPORTED_VERSION_NAME)
        InfoRow(title = "底层操控引擎", value = "SQLite Hook + WeKit Bridge")
    }
}

private fun formatTime(timestamp: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
} catch (_: Throwable) {
    "?"
}

@Suppress("DEPRECATION")
private fun moduleVersionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.0.0"
} catch (_: Throwable) {
    "2.0.0"
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
