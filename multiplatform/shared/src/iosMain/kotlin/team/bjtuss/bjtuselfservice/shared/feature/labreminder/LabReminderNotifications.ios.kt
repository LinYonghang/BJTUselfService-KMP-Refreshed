@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.runtime.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.*
import kotlin.coroutines.resume
import kotlin.time.Clock

@Composable
actual fun rememberLabReminderNotifier(): LabReminderNotifier = remember { IosLabReminderNotifier }

private object IosLabReminderNotifier : LabReminderNotifier {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private const val key = "bjtu.lab.pendingIds"
    override val hint = "iOS 由系统投递本地通知；请允许通知。最多登记最近48个未来节点，打开应用时续排；报告状态仅在应用同步或手动处理后更新。"
    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }
    override fun cancel() {
        val ids = NSUserDefaults.standardUserDefaults.stringArrayForKey(key).orEmpty()
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }
    override suspend fun replace(reminders: List<LabReminder>): String? {
        cancel()
        val pending = reminders.take(48)
        val ids = pending.map { "bjtu.lab|${it.id}" }
        NSUserDefaults.standardUserDefaults.setObject(ids, forKey = key)
        var failed = false
        for ((index, reminder) in pending.withIndex()) {
            val seconds = reminder.at - Clock.System.now().epochSeconds
            if (seconds <= 0) continue
            val content = UNMutableNotificationContent().apply {
                setTitle(reminder.title)
                setBody(reminder.body)
                setSound(UNNotificationSound.defaultSound())
            }
            val request = UNNotificationRequest.requestWithIdentifier(ids[index], content,
                UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds.toDouble(), false))
            val success = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    center.addNotificationRequest(request) { error -> if (continuation.isActive) continuation.resume(error == null) }
                }
            } == true
            if (!success) failed = true
        }
        if (pending.isNotEmpty()) {
            val allowed = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    center.getNotificationSettingsWithCompletionHandler { settings ->
                        if (continuation.isActive) continuation.resume(
                            settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                                settings?.authorizationStatus == UNAuthorizationStatusProvisional)
                    }
                }
            }
            if (allowed != true) return "系统通知未获授权或状态不可用；首页提醒仍可查看，请在系统设置中检查通知。"
        }
        return if (failed) "部分系统通知未能登记，请检查系统通知权限；首页提醒仍可查看。" else null
    }
}
