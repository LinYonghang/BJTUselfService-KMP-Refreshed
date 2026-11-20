package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.EventQueue
import java.awt.image.BufferedImage
import javax.swing.UIManager
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

@Composable
actual fun rememberLabReminderNotifier(): LabReminderNotifier = remember { DesktopLabReminderNotifier }

private object DesktopLabReminderNotifier : LabReminderNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableListOf<Job>()
    private var icon: TrayIcon? = null
    private val generation = AtomicLong()
    override val hint = "电脑端仅在应用运行（包括托盘驻留）时通知；退出或关机后不投递。错过的提醒下次在首页查看。"
    override suspend fun requestPermission() = System.getProperty("os.name").contains("Mac", true) || SystemTray.isSupported()
    @Synchronized override fun cancel() {
        generation.incrementAndGet()
        jobs.forEach { it.cancel() }; jobs.clear()
        EventQueue.invokeLater { icon?.let { SystemTray.getSystemTray().remove(it) }; icon = null }
    }
    override suspend fun replace(reminders: List<LabReminder>): String? {
        synchronized(this) {
            cancel()
            val registeredGeneration = generation.get()
            reminders.forEach { reminder ->
                jobs += scope.launch(Dispatchers.IO) {
                    delay(((reminder.at - Clock.System.now().epochSeconds) * 1000).coerceAtLeast(1000))
                    ensureActive()
                    if (System.getProperty("os.name").contains("Mac", true)) {
                        runCatching {
                            ProcessBuilder("/usr/bin/osascript", "-e",
                                "on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run",
                                reminder.title, reminder.body).start().let { process ->
                                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()
                            }
                        }
                    } else if (SystemTray.isSupported()) EventQueue.invokeLater {
                        if (generation.get() != registeredGeneration) return@invokeLater
                        runCatching {
                            // Reuse the host's tray icon when it is resident; only own the fallback.
                            val tray = SystemTray.getSystemTray().trayIcons.firstOrNull() ?: run {
                                val symbol = UIManager.getIcon("OptionPane.informationIcon")
                                val image = BufferedImage(symbol.iconWidth, symbol.iconHeight, BufferedImage.TYPE_INT_ARGB)
                                image.createGraphics().let { graphics -> symbol.paintIcon(null, graphics, 0, 0); graphics.dispose() }
                                TrayIcon(image, "实验提醒").apply {
                                    isImageAutoSize = true; SystemTray.getSystemTray().add(this); icon = this
                                }
                            }
                            tray.displayMessage(reminder.title, reminder.body, TrayIcon.MessageType.INFO)
                        }
                    }
                }
            }
        }
        return if (requestPermission()) null else "当前系统不支持桌面通知，提醒仍会显示在首页。"
    }
}
