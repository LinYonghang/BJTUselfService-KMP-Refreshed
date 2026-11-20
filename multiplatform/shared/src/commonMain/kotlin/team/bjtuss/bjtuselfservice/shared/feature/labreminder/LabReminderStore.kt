package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore

interface LabReminderPersistence {
    fun load(): String?
    fun save(value: String)
}

data class LabReminderState(val document: LabReminderDocument = LabReminderDocument(), val loaded: Boolean = false, val error: String? = null)

class LabReminderStore(private val persistence: LabReminderPersistence) {
    constructor(cache: CacheStore, account: String) : this(object : LabReminderPersistence {
        override fun load() = cache.metadata(account, "lab.reminders.v1")
        override fun save(value: String) = cache.putMetadata(account, "lab.reminders.v1", value)
    })
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(LabReminderState())
    val state = mutable.asStateFlow()
    private val notificationMutex = Mutex()
    private var notifier: LabReminderNotifier? = null
    private var stopped = false
    suspend fun load() = mutex.withLock {
        if (mutable.value.loaded) return@withLock
        try {
            val document = withContext(Dispatchers.Default) { persistence.load()?.let { json.decodeFromString<LabReminderDocument>(it) } ?: LabReminderDocument() }
            mutable.value = LabReminderState(document, true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = LabReminderState(error = "实验提醒记录读取失败，未覆盖原记录。") }
    }
    private suspend fun change(update: (LabReminderDocument) -> LabReminderDocument) = mutex.withLock {
        if (!mutable.value.loaded) return@withLock
        val next = update(mutable.value.document)
        if (next == mutable.value.document) return@withLock
        try {
            withContext(NonCancellable + Dispatchers.Default) {
                persistence.save(json.encodeToString(next))
                mutable.value = LabReminderState(next, true)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = "实验提醒保存失败，请重试。") }
    }
    suspend fun enable(value: Boolean) = change { it.copy(enabled = value) }
    suspend fun reconcile(records: List<LabReminderRecord>, source: LabSource, now: Long, authoritative: Boolean = true, owner: String = "") = change {
        val changedOwner = owner.isNotBlank() && it.sourceOwners[source]?.let { previous -> previous != owner } == true
        val old = if (changedOwner) it.records.filterNot { record -> record.source == source } else it.records
        val removedIds = if (changedOwner) it.records.filter { record -> record.source == source }.map { record -> record.id } else emptyList()
        it.copy(records = reconcileLabRecords(old, records, source, now, authoritative),
            sourceOwners = if (owner.isBlank()) it.sourceOwners else it.sourceOwners + (source to owner),
            acknowledged = it.acknowledged.filterNot { id -> removedIds.any { record -> id.startsWith("$record|") } }.toSet())
    }
    suspend fun handle(id: String, handled: Boolean) = change {
        it.copy(records = it.records.map { record -> if (record.id == id) record.copy(handled = handled) else record })
    }
    suspend fun acknowledge(recordId: String, now: Long) = change {
        it.copy(acknowledged = it.acknowledged + labReminderPlan(it).filter { reminder -> reminder.recordId == recordId && reminder.at <= now }.map { reminder -> reminder.id })
    }
    /** Serialize registration and logout, including uncancellable OS completion callbacks. */
    suspend fun syncNotifications(document: LabReminderDocument, service: LabReminderNotifier, now: Long): String? =
        withContext(NonCancellable) {
            notificationMutex.withLock {
                if (stopped) return@withLock null
                notifier = service
                try { service.replace(futureLabReminders(document, now)) }
                catch (_: Exception) { "系统提醒登记失败；首页提醒仍可查看。" }
            }
        }

    suspend fun stopNotifications() = withContext(NonCancellable) {
        notificationMutex.withLock { stopped = true; notifier?.cancel() }
    }

    /** Share the settings clear-cache transaction; never let an in-flight save restore deleted records. */
    fun clearWith(clear: () -> Boolean): Boolean {
        if (!notificationMutex.tryLock()) return false
        try {
            if (!mutex.tryLock()) return false
            try {
                if (!clear()) return false
                notifier?.cancel()
                mutable.value = LabReminderState(loaded = true)
                return true
            } finally { mutex.unlock() }
        } finally { notificationMutex.unlock() }
    }
}
