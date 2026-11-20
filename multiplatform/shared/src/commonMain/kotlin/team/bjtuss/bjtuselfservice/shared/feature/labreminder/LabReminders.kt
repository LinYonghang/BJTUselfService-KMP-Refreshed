package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoBooking

@Serializable
enum class LabSource { PHYSICS, DEMO }

@Serializable
data class LabReminderRecord(
    val id: String, val source: LabSource, val title: String,
    val starts: List<Long>, val end: Long,
    val handled: Boolean = false, val cancelled: Boolean = false,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(starts.isNotEmpty() && starts == starts.distinct().sorted() && end > starts.last())
    }
}

@Serializable
data class LabReminderDocument(
    val enabled: Boolean = false,
    val records: List<LabReminderRecord> = emptyList(),
    val acknowledged: Set<String> = emptySet(),
    val sourceOwners: Map<LabSource, String> = emptyMap(),
)

enum class LabReminderKind { PREPARATION, REPORT }
data class LabReminder(val id: String, val recordId: String, val at: Long, val title: String, val body: String,
    val kind: LabReminderKind = LabReminderKind.REPORT)

private val schoolZone = TimeZone.of("Asia/Shanghai")
private fun seconds(date: LocalDate, clock: String) = LocalDateTime(date, LocalTime.parse(clock)).toInstant(schoolZone).epochSeconds

fun PhysicsLab.reminderRecord(): LabReminderRecord? = timeRange?.split('-')?.takeIf { it.size == 2 }?.let { range ->
    LabReminderRecord("physics|$durationSettingKey", LabSource.PHYSICS, name,
        dates.map { seconds(it, range[0]) }, seconds(dates.last(), range[1]))
}

fun PhysicsDemoBooking.reminderRecord(): LabReminderRecord {
    val range = timeRange.split('-')
    return LabReminderRecord("demo|$name|$date|$period", LabSource.DEMO, "$name（演示实验）",
        listOf(seconds(date, range[0])), seconds(date, range[1]))
}

/** Only a successful, authoritative source refresh may cancel a future booking. Past records survive disappearing from the website. */
fun reconcileLabRecords(old: List<LabReminderRecord>, incoming: List<LabReminderRecord>, source: LabSource, now: Long, authoritative: Boolean = true): List<LabReminderRecord> {
    require(incoming.all { it.source == source })
    require(incoming.map { it.id }.distinct().size == incoming.size)
    val byId = incoming.associateBy { it.id }
    val merged = old.map { record ->
        if (record.source != source) record
        else byId[record.id]?.copy(handled = record.handled) ?: if (authoritative && record.starts.first() > now) record.copy(cancelled = true) else record
    }
    // An initial import should not resurrect years of historical report prompts.
    return (merged + incoming.filter { fresh -> fresh.end + 14 * 86400L >= now && old.none { it.id == fresh.id } }).sortedBy { it.end }
}

fun LabReminderDocument.forSources(physics: Boolean, demo: Boolean) = copy(
    records = records.filter { if (it.source == LabSource.PHYSICS) physics else demo },
)

fun futureLabReminders(document: LabReminderDocument, now: Long): List<LabReminder> =
    labReminderPlan(document).filter { it.at > now && it.id !in document.acknowledged }.take(48)

/** Four milestones; days mean elapsed 24-hour periods, never an invented submission deadline. */
fun labReminderPlan(document: LabReminderDocument): List<LabReminder> = if (!document.enabled) emptyList() else buildList {
    document.records.filterNot { it.handled || it.cancelled }.forEach { record ->
        record.starts.forEach { start ->
            add(LabReminder("${record.id}|before|$start", record.id, start - 86400, "实验准备提醒",
                "${record.title} · 计划开始：${reminderTime(start, schoolZone)}（北京时间）。请核对时间、地点并准备材料。", LabReminderKind.PREPARATION))
        }
        for ((days, label) in listOf(1 to "24小时", 7 to "7天", 14 to "14天")) {
            add(LabReminder("${record.id}|after|$days", record.id, record.end + days * 86400L,
                "实验报告跟进", "${record.title}计划结束已满$label，请核对报告要求及是否已提交。"))
        }
    }
}.sortedWith(compareBy({ it.at }, { it.id }))

/** One latest outstanding reminder per experiment; don't burst all missed milestones after an offline period. */
fun dueLabReminders(document: LabReminderDocument, now: Long): List<LabReminder> = labReminderPlan(document)
    .filter { it.at <= now && it.id !in document.acknowledged }
    .filter { it.kind != LabReminderKind.PREPARATION || now < it.at + 86400 }
    .groupBy { it.recordId }.values.mapNotNull { it.maxByOrNull { reminder -> reminder.at } }
    .sortedBy { it.at }

fun reminderTime(epoch: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    Instant.fromEpochSeconds(epoch).toLocalDateTime(zone).toString().replace('T', ' ').take(16)
