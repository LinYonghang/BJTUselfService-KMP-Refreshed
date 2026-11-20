package team.bjtuss.bjtuselfservice.shared.feature.labreminder

import androidx.compose.runtime.*
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState

data class LabReminderBinding(val notifier: LabReminderNotifier, val message: String?)

/** Session-owned effects, also active while another page is visible; no new school requests. */
@Composable
fun bindLabReminders(store: LabReminderStore, physics: PhysicsLabState, demo: PhysicsDemoState,
    physicsEnabled: Boolean): LabReminderBinding {
    val service = rememberLabReminderNotifier()
    val state by store.state.collectAsState()
    var message by remember(store) { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { store.load() }
    LaunchedEffect(store, state.loaded, physics) {
        if (state.loaded && physics.configured && !physics.refreshing && !physics.failed && (physics.synced || physics.fromCache)) {
            store.reconcile(physics.labs.mapNotNull { it.reminderRecord() }, LabSource.PHYSICS,
                Clock.System.now().epochSeconds, authoritative = physics.synced && !physics.fromCache, owner = physics.username)
        }
    }
    LaunchedEffect(store, state.loaded, demo) {
        if (state.loaded && demo.configured && !demo.refreshing && !demo.failed && (demo.synced || demo.fromCache)) {
            store.reconcile(demo.bookings.map { it.reminderRecord() }, LabSource.DEMO,
                Clock.System.now().epochSeconds, authoritative = demo.synced && !demo.fromCache, owner = demo.username)
        }
    }
    // Don't key this on the minute clock: re-registering near the firing time could cancel delivery.
    LaunchedEffect(store, state.document, state.loaded, physicsEnabled, physics.configured, demo.enabled, demo.configured, service) {
        message = store.syncNotifications(
            state.document.forSources(physicsEnabled && physics.configured, demo.enabled && demo.configured), service, Clock.System.now().epochSeconds)
    }
    return LabReminderBinding(service, message)
}
