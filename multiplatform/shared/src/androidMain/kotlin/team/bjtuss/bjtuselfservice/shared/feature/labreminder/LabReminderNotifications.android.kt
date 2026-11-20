package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred

/** Activity owns the launcher and detaches on destruction; the notifier retains only application Context. */
object AndroidLabReminderPermission {
    private var owner: Any? = null
    private var launcher: (() -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null
    fun attach(owner: Any, launcher: () -> Unit) { this.owner = owner; this.launcher = launcher }
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null; launcher = null; complete(false)
    }
    fun complete(granted: Boolean) { pending?.complete(granted); pending = null }
    suspend fun request(): Boolean {
        pending?.let { return it.await() }
        val launch = launcher ?: return false
        val result = CompletableDeferred<Boolean>()
        pending = result
        try { launch() } catch (_: Exception) { complete(false) }
        return result.await()
    }
}

private const val CHANNEL = "lab_report_reminders"
private const val PREFS = "lab_reminder_notifications"
@Serializable
private data class AndroidLabAlarm(val id: String, val at: Long, val title: String, val body: String)

private fun alarmIntent(context: Context, alarm: AndroidLabAlarm): PendingIntent = PendingIntent.getBroadcast(context, 0,
    Intent(context, LabReminderReceiver::class.java).setAction("lab.reminder").setData(Uri.parse("bjtu-lab:" + Uri.encode(alarm.id)))
        .putExtra("id", alarm.id).putExtra("title", alarm.title).putExtra("body", alarm.body),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

private fun saved(context: Context): List<AndroidLabAlarm> = runCatching {
    Json.decodeFromString<List<AndroidLabAlarm>>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending", "[]")!!)
}.getOrDefault(emptyList())

private fun schedule(context: Context, alarms: List<AndroidLabAlarm>) {
    val manager = context.getSystemService(AlarmManager::class.java)
    alarms.filter { it.at * 1000 > System.currentTimeMillis() }.forEach {
        // Approximate delivery deliberately avoids requesting exact-alarm access for report follow-up.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at * 1000, alarmIntent(context, it))
    }
}

class LabReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            schedule(context, saved(context))
    }
}

class LabReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        // Ignore a stale broadcast if the user cancelled or handled the report meanwhile.
        if (saved(context).none { it.id == id }) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "实验准备与报告跟进", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notice = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(intent.getStringExtra("title")).setContentText(intent.getStringExtra("body"))
            .setStyle(Notification.BigTextStyle().bigText(intent.getStringExtra("body")))
            .setContentIntent(launch).setAutoCancel(true).build()
        manager.notify(id, 0, notice)
        val remaining = saved(context).filterNot { it.id == id }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pending", Json.encodeToString(remaining)).commit()
    }
}

@Composable
actual fun rememberLabReminderNotifier(): LabReminderNotifier {
    val context = LocalContext.current.applicationContext
    return remember(context) { object : LabReminderNotifier {
        override val hint = "Android 使用系统通知，省电策略可能延迟；请在系统设置中允许本应用通知。错过的节点可在首页查看。"
        override suspend fun requestPermission(): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                AndroidLabReminderPermission.request()
            return hasPermission()
        }
        private fun hasPermission(): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        override fun cancel() {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            saved(context).forEach { alarmManager.cancel(alarmIntent(context, it)) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pending").commit()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
        }
        override suspend fun replace(reminders: List<LabReminder>): String? = runCatching {
            cancel()
            val alarms = reminders.map { AndroidLabAlarm(it.id, it.at, it.title, it.body) }
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pending", Json.encodeToString(alarms)).commit())
            schedule(context, alarms)
            if (alarms.isEmpty() || hasPermission()) null else "系统通知未获授权；首页提醒仍可使用，请在系统设置中允许通知。"
        }.getOrElse { "系统提醒登记失败；首页提醒仍可查看。" }
    } }
}
