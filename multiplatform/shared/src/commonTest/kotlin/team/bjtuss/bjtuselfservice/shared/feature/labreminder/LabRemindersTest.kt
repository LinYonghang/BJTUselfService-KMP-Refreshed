package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*
import kotlinx.coroutines.*
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoBooking

class LabRemindersTest {
    private fun epoch(text: String) = Instant.parse(text).epochSeconds
    private val lab = PhysicsLab(LocalDate(2026, 11, 20), 1, "测试实验", "测试教室", "", 1)
    private val record = lab.reminderRecord()!!
    private fun document(vararg records: LabReminderRecord) = LabReminderDocument(true, records.toList())

    @Test fun milestonesUseSchoolTimeAndScheduledEnd() {
        val plan = labReminderPlan(document(record))
        assertEquals(listOf(epoch("2026-11-19T05:20:00Z"), epoch("2026-11-21T07:50:00Z"),
            epoch("2026-11-27T07:50:00Z"), epoch("2026-12-04T07:50:00Z")), plan.map { it.at })
        assertEquals("2026-11-20 13:20", reminderTime(record.starts.single(), TimeZone.of("Asia/Shanghai")))
    }

    @Test fun twoWeekLabPreparesForBothSessionsButFollowsUpOnlyAfterFinalSession() {
        val twoWeeks = lab.copy(weekCount = 2).reminderRecord()!!
        val plan = labReminderPlan(document(twoWeeks))
        assertEquals(5, plan.size)
        assertEquals(epoch("2026-11-26T05:20:00Z"), plan[1].at)
        assertEquals(epoch("2026-11-28T07:50:00Z"), plan[2].at)
        assertEquals(record.id, twoWeeks.id)
    }

    @Test fun demoAndPhysicsStayDistinctAndDemoUsesOwnTimeTable() {
        val demo = PhysicsDemoBooking("近代与综合", LocalDate(2026, 11, 20), 5).reminderRecord()
        assertEquals(epoch("2026-11-20T12:10:00Z"), demo.end)
        assertNotEquals(record.id, demo.id)
        assertEquals(8, labReminderPlan(document(record, demo)).size)
    }

    @Test fun disabledHandledCancelledAndDisabledSourcesProduceNoPrompts() {
        assertTrue(labReminderPlan(LabReminderDocument(records = listOf(record))).isEmpty())
        assertTrue(labReminderPlan(document(record.copy(handled = true))).isEmpty())
        assertTrue(labReminderPlan(document(record.copy(cancelled = true))).isEmpty())
        assertTrue(labReminderPlan(document(record).forSources(false, true)).isEmpty())
    }

    @Test fun overdueNodesCollapsePerLabAndBoundaryIsInclusive() {
        val plan = labReminderPlan(document(record))
        assertTrue(dueLabReminders(document(record), plan.first().at - 1).isEmpty())
        assertEquals(plan.first(), dueLabReminders(document(record), plan.first().at).single())
        assertEquals(plan.last(), dueLabReminders(document(record), plan.last().at + 1).single())
    }

    @Test fun preparationDoesNotKeepTellingTheUserToPrepareAfterTheSessionStarts() {
        assertTrue(dueLabReminders(document(record), record.starts.single()).isEmpty())
        assertEquals(LabReminderKind.PREPARATION,
            dueLabReminders(document(record), record.starts.single() - 1).single().kind)
    }

    @Test fun onlyFutureUnacknowledgedNodesAreRegisteredAndQueueIsBounded() {
        val plan = labReminderPlan(document(record))
        assertEquals(plan.drop(2), futureLabReminders(document(record), plan[1].at))
        assertEquals(plan.drop(3), futureLabReminders(document(record).copy(acknowledged = setOf(plan[2].id)), plan[1].at))
        val many = document(*(1..20).map { record.copy(id = "lab-$it") }.toTypedArray())
        val queued = futureLabReminders(many, 0)
        assertEquals(48, queued.size)
        assertEquals(queued.sortedWith(compareBy({ it.at }, { it.id })), queued)
    }

    @Test fun successfulEmptyRefreshCancelsFutureButRetainsPastFollowupsAndOtherSource() {
        val demo = record.copy(id = "demo", source = LabSource.DEMO)
        val cancelled = reconcileLabRecords(listOf(record, demo), emptyList(), LabSource.PHYSICS, record.starts.first() - 1)
        assertTrue(cancelled.first { it.id == record.id }.cancelled)
        assertFalse(cancelled.first { it.id == demo.id }.cancelled)
        assertFalse(reconcileLabRecords(listOf(record), emptyList(), LabSource.PHYSICS, record.end).single().cancelled)
        assertFalse(reconcileLabRecords(listOf(record), emptyList(), LabSource.PHYSICS, 0, authoritative = false).single().cancelled)
    }

    @Test fun resyncPreservesManualChoiceAndCorrectsTimeOrRestoresCancelledBooking() {
        val updated = record.copy(end = record.end + 3600)
        assertEquals(updated.copy(handled = true), reconcileLabRecords(listOf(record.copy(handled = true, cancelled = true)),
            listOf(updated), LabSource.PHYSICS, record.end).single())
    }

    @Test fun initialImportDoesNotReviveVeryOldExperiments() {
        assertTrue(reconcileLabRecords(emptyList(), listOf(record), LabSource.PHYSICS, record.end + 14 * 86400 + 1).isEmpty())
        assertEquals(listOf(record), reconcileLabRecords(listOf(record), listOf(record), LabSource.PHYSICS, record.end + 90 * 86400))
    }

    @Test fun invalidPersistedRecordIsRejected() {
        assertFailsWith<IllegalArgumentException> { record.copy(starts = emptyList()) }
        assertFailsWith<IllegalArgumentException> { record.copy(end = record.starts.last()) }
    }

    @Test fun markReadAndHandledSurviveReloadAndCanBeReversed() = runBlocking {
        val persistence = MemoryPersistence()
        val store = LabReminderStore(persistence)
        store.load(); store.reconcile(listOf(record), LabSource.PHYSICS, 0); store.enable(true)
        val nodes = labReminderPlan(store.state.value.document)
        store.acknowledge(record.id, nodes[2].at)
        assertTrue(dueLabReminders(store.state.value.document, nodes[2].at).isEmpty())
        assertEquals(nodes.last(), dueLabReminders(store.state.value.document, nodes.last().at).single())
        store.handle(record.id, true)
        val reloaded = LabReminderStore(persistence).also { it.load() }
        assertTrue(labReminderPlan(reloaded.state.value.document).isEmpty())
        reloaded.handle(record.id, false)
        assertEquals(listOf(nodes.last()), futureLabReminders(reloaded.state.value.document, nodes[2].at))
    }

    @Test fun readAndWriteFailuresCannotOverwriteTheLastGoodDocument() = runBlocking {
        val persistence = MemoryPersistence().apply { value = "not-json" }
        val store = LabReminderStore(persistence)
        store.load(); store.enable(true)
        assertEquals("not-json", persistence.value)
        assertFalse(store.state.value.loaded)
        persistence.value = null; store.load(); store.enable(true)
        val good = store.state.value.document
        persistence.failWrite = true; store.enable(false)
        assertEquals(good, store.state.value.document)
        assertNotNull(store.state.value.error)
        Unit
    }

    @Test fun sourceAccountChangeDropsPriorOwnersRecordsAndReadMarks() = runBlocking {
        val store = LabReminderStore(MemoryPersistence())
        store.load(); store.enable(true)
        store.reconcile(listOf(record), LabSource.PHYSICS, 0, owner = "synthetic-a")
        store.handle(record.id, true); store.acknowledge(record.id, record.end + 15 * 86400)
        store.reconcile(listOf(record), LabSource.PHYSICS, 0, owner = "synthetic-b")
        assertFalse(store.state.value.document.records.single().handled)
        assertTrue(store.state.value.document.acknowledged.isEmpty())
    }

    @Test fun logoutWinsAgainstInFlightRegistrationAndRejectsLaterRescheduling() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = LabReminderStore(MemoryPersistence())
        val calls = mutableListOf<String>()
        val notifier = object : LabReminderNotifier {
            override val hint = "test"
            override suspend fun requestPermission() = true
            override suspend fun replace(reminders: List<LabReminder>): String? {
                calls += "register-start"; started.complete(Unit); release.await(); calls += "register-end"; return null
            }
            override fun cancel() { calls += "cancel" }
        }
        val registering = launch { store.syncNotifications(document(record), notifier, 0) }
        started.await()
        assertFalse(store.clearWith { error("must not clear while registering") })
        val logout = launch(start = CoroutineStart.UNDISPATCHED) { store.stopNotifications() }
        release.complete(Unit); registering.join(); logout.join()
        store.syncNotifications(document(record), notifier, 0)
        assertEquals(listOf("register-start", "register-end", "cancel"), calls)
    }

    @Test fun clearCacheResetsPreferenceAndRecordsWithoutRewritingDeletedData() = runBlocking {
        val persistence = MemoryPersistence()
        val store = LabReminderStore(persistence)
        store.load(); store.enable(true); store.reconcile(listOf(record), LabSource.PHYSICS, 0)
        assertTrue(store.clearWith { persistence.value = null; true })
        assertEquals(LabReminderDocument(), store.state.value.document)
        assertNull(persistence.value)
    }

    @Test fun wipeWaitsForInFlightWritesAndPreventsOldActionsRecreatingDeletedRecords() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var value: String? = null
        var blockWrite = false
        val store = LabReminderStore(object : LabReminderPersistence {
            override fun load() = value
            override fun save(next: String) {
                if (blockWrite) runBlocking { started.complete(Unit); release.await() }
                value = next
            }
        })
        store.load()
        blockWrite = true
        val saving = launch { store.enable(true) }
        started.await()
        var wiped = false
        val wiping = async(start = CoroutineStart.UNDISPATCHED) { store.wipeWith { value = null; wiped = true; true } }
        assertFalse(wiped)
        release.complete(Unit); saving.join(); assertTrue(wiping.await())
        store.reconcile(listOf(record), LabSource.PHYSICS, 0)
        store.enable(true)
        assertNull(value)
        assertEquals(LabReminderDocument(), store.state.value.document)
    }

    @Test fun unsuccessfulWipeKeepsReminderPreferenceEditable() = runBlocking {
        val persistence = MemoryPersistence()
        val store = LabReminderStore(persistence)
        store.load(); store.enable(true)
        assertFalse(store.wipeWith { false })
        store.enable(false)
        assertFalse(store.state.value.document.enabled)
        assertNotNull(persistence.value)
        Unit
    }

    private class MemoryPersistence : LabReminderPersistence {
        var value: String? = null
        var failWrite = false
        override fun load() = value
        override fun save(value: String) { check(!failWrite); this.value = value }
    }
}
