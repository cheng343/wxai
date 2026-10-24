package dev.cheng.wxai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置页的分组卡片：圆角 Surface + 可选的分组标题。
 */
@Composable
fun SettingsCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
                )
            }
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 通用设置行：标题 + 说明 + 右侧附加内容，整行可点击。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/**
 * 点击后弹出编辑框的设置行，右侧带铅笔图标。
 */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    SettingRow(
        title = title,
        subtitle = value.ifBlank { "未设置" },
        subtitleColor = valueColor,
        subtitleMaxLines = 3,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/**
 * 只读信息行：左标题右取值。
 */
@Composable
fun InfoRow(
    title: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/**
 * 单个文本 / 密码 / 多行文本的编辑对话框。
 *
 * @param validate 返回错误文案；返回 null 表示通过，确定按钮可用。
 */
@Composable
fun EditTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    multiline: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    validate: ((String) -> String?)? = null,
) {
    var draft by remember { mutableStateOf(initial) }
    var revealed by remember { mutableStateOf(false) }
    val error = validate?.invoke(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                maxLines = if (multiline) 8 else 1,
                placeholder = if (placeholder != null) {
                    { Text(placeholder) }
                } else {
                    null
                },
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else {
                    null
                },
                visualTransformation = if (password && !revealed) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                trailingIcon = if (password) {
                    {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "隐藏" else "显示")
                        }
                    }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (error == null) onConfirm(draft) },
                enabled = error == null,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 两个数字的对话框：随机延迟区间的上下限。
 */
@Composable
fun DelayDialog(
    initialMin: Int,
    initialMax: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var minText by remember { mutableStateOf(initialMin.toString()) }
    var maxText by remember { mutableStateOf(initialMax.toString()) }
    val minValue = minText.trim().toIntOrNull()
    val maxValue = maxText.trim().toIntOrNull()
    val error = when {
        minValue == null -> "最小延迟请填整数（毫秒）"
        maxValue == null -> "最大延迟请填整数（毫秒）"
        minValue < 0 || maxValue < 0 -> "延迟不能是负数"
        maxValue < minValue -> "最大延迟不能小于最小延迟"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("随机延迟区间（毫秒）") },
        text = {
            Column {
                Text(
                    text = "每条回复会在该区间内随机等待，模拟真人打字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("最小延迟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { maxText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("最大延迟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val min = minValue
                    val max = maxValue
                    if (error == null && min != null && max != null) {
                        onConfirm(min, max)
                    }
                },
                enabled = error == null,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 单选对话框：用于人设风格预设切换等列表选择场景。
 */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (value, label) ->
                    val isSelected = value.equals(current, ignoreCase = true)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelect(value) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
