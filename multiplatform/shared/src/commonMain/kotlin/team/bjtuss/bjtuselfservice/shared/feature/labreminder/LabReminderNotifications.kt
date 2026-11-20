package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.runtime.Composable

interface LabReminderNotifier {
    val hint: String
    suspend fun requestPermission(): Boolean
    suspend fun replace(reminders: List<LabReminder>): String?
    fun cancel()
}

@Composable
expect fun rememberLabReminderNotifier(): LabReminderNotifier
