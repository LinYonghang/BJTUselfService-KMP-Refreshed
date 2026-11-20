package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme

private const val fixtureNow = 1795824000L
private val previewNotifier = object : LabReminderNotifier {
    override val hint = "测试预览 · 使用虚构实验，无网络请求，不登记系统通知。"
    override suspend fun requestPermission() = true
    override suspend fun replace(reminders: List<LabReminder>): String? = null
    override fun cancel() {}
}

private fun fixtureStore(enabled: Boolean = true): LabReminderStore = LabReminderStore(object : LabReminderPersistence {
    var value: String? = null
    override fun load() = value
    override fun save(value: String) { this.value = value }
}).also { store ->
    runBlocking {
        store.load()
        store.reconcile(listOf(
            LabReminderRecord("synthetic", LabSource.PHYSICS, "测试专题实验（两周）",
                listOf(fixtureNow - 8 * 86400, fixtureNow - 86400 - 3600), fixtureNow - 86400)), LabSource.PHYSICS, 0)
        store.reconcile(listOf(
            LabReminderRecord("demo", LabSource.DEMO, "光学（演示实验）",
                listOf(fixtureNow + 10 * 86400), fixtureNow + 10 * 86400 + 6000)), LabSource.DEMO, 0)
        store.enable(enabled)
    }
}

class LabReminderRenderTest {
    @Test fun accountRecordsAreIsolatedAndClearWithExistingCacheTransaction() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            val first = LabReminderStore(cache, "synthetic-a")
            val second = LabReminderStore(cache, "synthetic-b")
            first.load(); second.load(); first.enable(true)
            assertFalse(second.state.value.document.enabled)
            assertNull(cache.metadata("synthetic-b", "lab.reminders.v1"))
            assertTrue(first.clearWith { cache.clearAccount("synthetic-a"); true })
            val reloaded = LabReminderStore(cache, "synthetic-a").also { it.load() }
            assertEquals(LabReminderDocument(), reloaded.state.value.document)
        } finally { cache.close() }
    }

    @Test fun renderLightDarkLargeTextAndDisabledComponents() = SwingUtilities.invokeAndWait {
        for (variant in listOf("card-light", "card-dark", "card-large", "settings-light", "settings-dark", "settings-large", "disabled")) {
            val store = fixtureStore(variant != "disabled")
            val scene = ImageComposeScene(420, 1000, density = Density(1f, if (variant.endsWith("large")) 1.7f else 1f),
                coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(variant.endsWith("dark"), false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (variant.startsWith("settings")) LabReminderSettings(store, LabReminderBinding(previewNotifier, null), true, true)
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            LabReminderCard(store, LabReminderBinding(previewNotifier, null), fixtureNow, true, true)
                        }
                    }
                }
            }
            try {
                repeat(5) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                val image = scene.render(1_200_000_000L)
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!
                    try {
                        File("../../.artifacts/lab-reminders/$variant.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                        assertTrue(data.bytes.size > 1000)
                    } finally { data.close() }
                } finally { image.close() }
            } finally { scene.close() }
        }
    }
}

/** Standalone local acceptance window: synthetic, in-memory data; never logs into a school service. */
fun main() {
    val store = fixtureStore()
    application {
        Window(onCloseRequest = ::exitApplication, title = "Lab reminder acceptance (synthetic)",
            state = rememberWindowState(width = 460.dp, height = 880.dp)) {
            var dark by remember { mutableStateOf(false) }
            PlatformAppTheme(dark, false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        TextButton(onClick = { dark = !dark }) { Text("切换浅色 / 深色（测试）") }
                        LabReminderCard(store, LabReminderBinding(previewNotifier, null), fixtureNow, true, true)
                    }
                }
            }
        }
    }
}
