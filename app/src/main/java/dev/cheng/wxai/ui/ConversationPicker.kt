package dev.cheng.wxai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cheng.wxai.ConvEntry
import dev.cheng.wxai.ConvSnapshot

private enum class ConvFilter(val label: String) {
    ALL("全部"),
    CHATS("单聊"),
    GROUPS("群聊"),
}

/**
 * 全屏的「选择白名单会话」界面：搜索 + 类型筛选 + 多选列表 + 底部确定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationPicker(
    initialSelected: Set<String>,
    snapshot: ConvSnapshot,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ConvFilter.ALL) }
    var selected by remember { mutableStateOf(initialSelected) }

    val filtered = remember(snapshot, query, filter) {
        val keyword = query.trim()
        snapshot.items.filter { entry ->
            val matchesKeyword = keyword.isEmpty() ||
                entry.name.contains(keyword, ignoreCase = true) ||
                entry.username.contains(keyword, ignoreCase = true)
            val matchesFilter = when (filter) {
                ConvFilter.ALL -> true
                ConvFilter.CHATS -> !entry.isGroup
                ConvFilter.GROUPS -> entry.isGroup
            }
            matchesKeyword && matchesFilter
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("选择白名单会话") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { selected = emptySet() },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text("清空")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 8.dp,
                ) {
                    Button(
                        onClick = { onConfirm(orderSelected(selected, snapshot.items)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text("确定 · 已选 ${selected.size}")
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("搜索名称或 wxid") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConvFilter.values().forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                if (refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val error = snapshot.error
                if (error != null) {
                    PickerErrorNotice(
                        message = error,
                        refreshing = refreshing,
                        onRetry = onRefresh,
                    )
                }
                if (filtered.isEmpty()) {
                    EmptyPickerState(
                        snapshotEmpty = snapshot.items.isEmpty(),
                        refreshing = refreshing,
                        onRefresh = onRefresh,
                        onClearFilter = {
                            query = ""
                            filter = ConvFilter.ALL
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(filtered, key = { it.username }) { entry ->
                            PickerConversationRow(
                                entry = entry,
                                checked = entry.username in selected,
                                onToggle = {
                                    selected = if (entry.username in selected) {
                                        selected - entry.username
                                    } else {
                                        selected + entry.username
                                    }
                                },
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerConversationRow(
    entry: ConvEntry,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name.ifBlank { entry.username },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversationSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyPickerState(
    snapshotEmpty: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = if (snapshotEmpty) "还没有拿到会话列表" else "没有匹配的会话",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (snapshotEmpty) "点下面的按钮从微信读取" else "换个关键词或筛选条件试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (snapshotEmpty) {
                Button(onClick = { if (!refreshing) onRefresh() }, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (refreshing) "正在读取…" else "刷新")
                }
            } else {
                TextButton(onClick = onClearFilter) {
                    Text("清除筛选")
                }
            }
        }
    }
}

@Composable
private fun PickerErrorNotice(message: String, refreshing: Boolean, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
            TextButton(onClick = { if (!refreshing) onRetry() }, enabled = !refreshing) {
                Text("重试")
            }
        }
    }
}

private fun conversationSubtitle(entry: ConvEntry): String {
    val kind = if (entry.isGroup) "群聊" else "单聊"
    val unread = if (entry.unread > 0) " · 未读 ${entry.unread}" else ""
    return "$kind · ${entry.username}$unread"
}

/**
 * 已选集合按快照顺序排序；快照里找不到的旧白名单项保留在末尾，避免静默丢数据。
 */
private fun orderSelected(selected: Set<String>, items: List<ConvEntry>): List<String> {
    val known = items.map { it.username }
    val ordered = known.filter { it in selected }
    val rest = selected.filterNot { it in known }.sorted()
    return ordered + rest
}
