package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.sheetBodyScroll

@Composable
fun LabReminderCard(store: LabReminderStore, binding: LabReminderBinding, now: Long,
    physicsEnabled: Boolean, demoEnabled: Boolean) {
    val state by store.state.collectAsState()
    val document = state.document.forSources(physicsEnabled, demoEnabled)
    val due = remember(document, now) { dueLabReminders(document, now) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("实验准备与报告跟进", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showSettings = true }) { Text("设置") }
            }
            when {
                !state.loaded -> Text(state.error ?: "正在读取实验提醒…")
                !document.enabled -> Text("未开启 · 实验前24小时及计划结束后24小时、7天、14天提醒",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                due.isEmpty() -> Text("当前没有待处理的实验提醒", style = MaterialTheme.typography.bodyMedium)
            }
            due.forEach { reminder ->
                HorizontalDivider()
                Text(reminder.title, style = MaterialTheme.typography.titleSmall)
                Text(reminder.body)
                Text("提醒时刻：${reminderTime(reminder.at)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Stack actions so large text and narrow windows never clip the labels.
                TextButton(onClick = { scope.launch { store.acknowledge(reminder.recordId, now) } }) { Text("本次已看，保留后续提醒") }
                if (reminder.kind == LabReminderKind.REPORT) {
                    TextButton(onClick = { scope.launch { store.handle(reminder.recordId, true) } }) { Text("报告已提交 / 不再提醒") }
                }
            }
            state.error?.takeIf { state.loaded }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            binding.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showSettings) AppleSheet(onDismissRequest = { showSettings = false }, title = "实验提醒设置", dismissLabel = "关闭") {
        LabReminderSettings(store, binding, physicsEnabled, demoEnabled)
    }
}

@Composable
internal fun LabReminderSettings(store: LabReminderStore, binding: LabReminderBinding,
    physicsEnabled: Boolean, demoEnabled: Boolean) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().sheetBodyScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("开启实验提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = state.document.enabled, enabled = state.loaded && !busy, onCheckedChange = { enabled ->
                busy = true
                scope.launch {
                    try {
                        if (enabled) permissionMessage = if (binding.notifier.requestPermission()) null
                            else "未获系统通知权限；首页提醒仍可用，请在系统设置中允许通知。"
                        store.enable(enabled)
                    } finally { busy = false }
                }
            })
        }
        Text("实验开始前24小时：准备提醒。\n最后一次计划结束后24小时、7天、14天：报告跟进。")
        Text("两周实验每次开始前提醒一次，报告跟进从最后一次计划结束计算。以上时刻不是报告截止时间，也不表示已实际完成实验。")
        Text("报告提交情况由你手动标记，仅保存在本账号的本机记录中，不会提交或修改学校资料；暂不自动识别网站上的报告状态。")
        Text("来源同步关闭时暂停该来源提醒，记录保留。离线继续使用上次数据；改期或取消需成功同步后更新。首次登记只导入未来及近14天的实验。")
        Text("物理实验：${if (physicsEnabled) "开启" else "暂停"} · 演示实验：${if (demoEnabled) "开启" else "暂停"}")
        Text(binding.notifier.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("最多登记最近48个未来节点，打开应用时续排；错过的多个节点在首页合并显示最近一条。", style = MaterialTheme.typography.bodySmall)
        (permissionMessage ?: binding.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            if (!state.loaded) TextButton(onClick = { scope.launch { store.load() } }) { Text("重试读取") }
        }
        HorizontalDivider()
        Text("实验记录", style = MaterialTheme.typography.titleMedium)
        if (state.document.records.isEmpty()) Text("暂无记录。请先开启并同步物理实验或演示实验。")
        state.document.records.asReversed().forEach { record ->
            Text(record.title, style = MaterialTheme.typography.titleSmall)
            Text("最后计划结束：${reminderTime(record.end)}", style = MaterialTheme.typography.bodySmall)
            Text(when { record.cancelled -> "已取消（同步结果中已无此未来预约）"; record.handled -> "已手动处理，不再提醒"; else -> "保留提醒" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!record.cancelled) TextButton(onClick = { scope.launch { store.handle(record.id, !record.handled) } }) {
                Text(if (record.handled) "恢复后续提醒" else "报告已提交 / 不再提醒")
            }
            HorizontalDivider()
        }
    }
}
