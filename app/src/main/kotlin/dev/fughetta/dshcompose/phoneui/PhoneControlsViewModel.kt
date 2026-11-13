@file:Suppress("REDUNDANT_ELSE_IN_WHEN")

package dev.fughetta.dshcompose.phoneui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.phone.PhonePolicies
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.*
import dev.fughetta.dshcompose.phone.background.PrivilegedActionGate
import dev.fughetta.dshcompose.runtime.PhoneStorage
import dev.fughetta.dshcompose.ui.phone.*
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PhoneLocalRuntime(val active: Boolean = false)

private class PhoneUiFailure(val code: String) : java.io.IOException(code)

/** Owns phone settings, permission confirmations and native task lifetimes for the page. */
class PhoneControlsViewModel(
    context: Context,
    private val localRuntime: () -> PhoneLocalRuntime,
    private val restartLocalEngine: suspend () -> Unit,
) : ViewModel() {
    private val app = context.applicationContext
    private val scope
        get() = viewModelScope

    private val control = PhoneControl.get(app)
    private val snapshotSource = PhoneControlSnapshotSource(app, control)
    private val prefs = app.localPreferences("phone_controls_ui")
    private val mutable = MutableStateFlow(PhoneControlsUiState())
    val state: StateFlow<PhoneControlsUiState> = mutable.asStateFlow()
    val phoneStorageEnabled
        get() = prefs.getBoolean("phoneStorage", false)

    val returnAfterDone
        get() = prefs.getBoolean("returnAfterDone", true)

    private var observing = false
    private var closed = false
    private var generation = 0L
    private var statusSequence = 0L
    private var read: Job? = null
    private var write: Job? = null
    private var filtering: Job? = null
    private var writeSequence = 0L
    private var filterSequence = 0L
    private val serial = Mutex()
    private val privileged = PrivilegedActionGate()
    private var allApps = persistentListOf<PhoneAppRuleView>()
    private val retained = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var pendingStorageEnable = false
    private var permissionPrompt: String? = null
    private val sink: (Map<String, Any?>) -> Unit = { value ->
        if (!closed && observing) {
            statusSequence++
            val previous = mutable.value.status.task
            val status = phoneControlStatus(value)
            update { it.copy(status = status, loaded = true) }
            if (previous.active && !status.task.active) refreshLog()
        }
    }

    fun action(action: PhoneControlsAction) {
        if (closed) return
        when (action) {
            is PhoneControlsAction.Observe -> observe(action.observing)
            is PhoneControlsAction.Page -> {
                update { it.copy(page = action.page, error = null, notice = null) }
                if (action.page == PhoneControlsPage.Log) refreshLog() else refresh()
            }
            PhoneControlsAction.Refresh -> refresh()
            is PhoneControlsAction.Enabled -> requestEnabled(action.enabled)
            is PhoneControlsAction.ConfirmMode ->
                if (action.mode in setOf("ask", "task", "always")) {
                    if (action.mode == "always") risk(PhoneRisk(PhoneRiskKind.AlwaysConfirmOff))
                    else policies(mapOf("confirmMode" to action.mode))
                }
            is PhoneControlsAction.Unlock ->
                if (action.key in UNLOCKS) {
                    if (action.enabled) risk(PhoneRisk(PhoneRiskKind.Unlock, unlock = action.key))
                    else policies(mapOf("unlocks" to mapOf(action.key to false)))
                }
            is PhoneControlsAction.AppRule -> requestRule(action.packageName, action.rule)
            is PhoneControlsAction.Query -> {
                update { it.copy(query = action.text.take(200)) }
                filter()
            }
            PhoneControlsAction.AddPackage -> addPackage()
            PhoneControlsAction.ResetRules -> risk(PhoneRisk(PhoneRiskKind.ResetRules))
            PhoneControlsAction.ConfirmRisk -> confirmRisk()
            PhoneControlsAction.CancelRisk -> update { it.copy(risk = null) }
            PhoneControlsAction.TestScreen ->
                mutate {
                    val result = control.testScreen()
                    accepted(result)
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        update { it.copy(screenText = result["text"] as? String) }
                    }
                }
            PhoneControlsAction.CloseScreenText -> update { it.copy(screenText = null) }
            PhoneControlsAction.StopTask -> {
                // Stop is immediate even when a root or wireless setup request is waiting.
                control.stopTask(AppStrings.get(app, UiR.string.phone_controls_stop_reason))
                refresh()
            }
            is PhoneControlsAction.Storage ->
                if (action.enabled) risk(PhoneRisk(PhoneRiskKind.Storage))
                else {
                    pendingStorageEnable = false
                    saveStorage(false)
                }
            is PhoneControlsAction.ReturnAfterDone -> preference("returnAfterDone", action.enabled)
            is PhoneControlsAction.OpenAccess -> openAccess(action.kind)
            is PhoneControlsAction.MarkVendorVisited -> markVisited(action.kind, action.visited)
            is PhoneControlsAction.PermissionResult -> permissionResult(action.id, action.granted)
            is PhoneControlsAction.BackgroundMode ->
                if (action.mode == "root") risk(PhoneRisk(PhoneRiskKind.Root))
                else selectMode(action.mode)
            is PhoneControlsAction.BackgroundKeep ->
                mutate {
                    val mode = mutable.value.status.background.mode
                    val (ticket, failure) = control.background.changeKeep(mode, action.enabled)
                    if (ticket == null) throw PhoneUiFailure(failure ?: "phone/keep")
                    accepted(control.background.saveKeep(ticket))
                }
            PhoneControlsAction.PrepareRoot -> {
                val ticket = privileged.select()
                mutate(replace = true) {
                    accepted(control.background.prepareRoot { wanted(ticket) })
                }
            }
            PhoneControlsAction.RequestPrivilegedPermission -> {
                val background = mutable.value.status.background
                val provider = background.provider ?: background.mode
                val ticket = privileged.snapshot()
                mutate {
                    if (!wanted(ticket) || !control.background.requestShizukuPermission(provider))
                        throw PhoneUiFailure("phone/permission")
                }
            }
            PhoneControlsAction.GrantSecureSettings -> {
                val ticket = privileged.snapshot()
                mutate {
                    accepted(
                        if (control.background.rootGrantSelected())
                            control.background.grantRootSecureSettings()
                        else control.grantSecureSettings { wanted(ticket) },
                    )
                }
            }
            PhoneControlsAction.StartAdbPairing ->
                mutate { accepted(control.background.adbStartPairing()) }
            is PhoneControlsAction.PairingCode ->
                update { it.copy(pairingCode = action.code.filter(Char::isDigit).take(6)) }
            is PhoneControlsAction.PairingPortChanged ->
                update { it.copy(pairingPort = action.port.filter(Char::isDigit).take(5)) }
            PhoneControlsAction.PairAdb -> {
                val code = mutable.value.pairingCode
                if (code.length != 6) return fail("phone/pairing-code")
                val port = port() ?: if (mutable.value.pairingPort.isNotBlank()) return else null
                update { it.copy(pairingCode = "") }
                mutate { accepted(control.background.adbPair(code, port)) }
            }
            PhoneControlsAction.ConnectAdb -> {
                val port = port() ?: if (mutable.value.pairingPort.isNotBlank()) return else null
                val ticket = privileged.snapshot()
                mutate { accepted(control.background.adbConnect(port) { wanted(ticket) }) }
            }
        }
    }

    private fun observe(value: Boolean) {
        if (observing == value) return
        observing = value
        if (value) {
            control.addSink(sink)
            refresh()
        } else {
            control.removeSink(sink)
            read?.cancel()
            filtering?.cancel()
            generation++
        }
    }

    fun refresh(clearError: Boolean = true) {
        if (closed || !observing) return
        read?.cancel()
        val own = ++generation
        val status = statusSequence
        update { it.copy(loading = true, error = if (clearError) null else it.error) }
        read = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { snapshotSource.read(retained) }
                if (closed || own != generation) return@launch
                applySnapshot(result, status == statusSequence)
                if (pendingStorageEnable && result.access.storage) {
                    pendingStorageEnable = false
                    saveStorage(true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed && own == generation) fail("phone/read")
            } finally {
                if (!closed && own == generation) update { it.copy(loading = false) }
            }
        }
    }

    private fun applySnapshot(snapshot: PhoneControlSnapshot, applyStatus: Boolean) {
        allApps = snapshot.apps
        update {
            it.copy(
                loaded = true,
                loading = false,
                status = if (applyStatus) snapshot.status else it.status,
                confirmMode = snapshot.policies.confirmMode.wire,
                unlocks =
                    snapshot.policies.unlocks
                        .toMap()
                        .mapValues { it.value == true }
                        .toPersistentMap(),
                log = snapshot.log,
                access = snapshot.access,
                disclosureAccepted = snapshot.disclosure,
                phoneStorage = snapshot.storage,
                returnAfterDone = snapshot.returnAfterDone,
                awaitingAccessibility = false,
                accessibilityAttemptFailed =
                    it.awaitingAccessibility && !snapshot.status.accessibilityEnabled ||
                        it.accessibilityAttemptFailed && !snapshot.status.accessibilityEnabled,
                needsAllFiles =
                    pendingStorageEnable && !snapshot.access.storage && Build.VERSION.SDK_INT >= 30,
            )
        }
        filter()
    }

    private fun refreshLog() {
        scope.launch {
            val log = withContext(Dispatchers.IO) { snapshotSource.logs() }
            if (!closed) update { it.copy(log = log) }
        }
    }

    private fun filter() {
        filtering?.cancel()
        val revision = ++filterSequence
        val query = mutable.value.query.trim().lowercase()
        val source = allApps
        filtering = scope.launch {
            val rows =
                withContext(Dispatchers.Default) {
                    source
                        .filterIndexed { index, row ->
                            if (index % 128 == 0) currentCoroutineContext().ensureActive()
                            query.isEmpty() ||
                                row.label.lowercase().contains(query) ||
                                row.packageName.lowercase().contains(query)
                        }
                        .toPersistentList()
                }
            if (!closed && revision == filterSequence) update { it.copy(apps = rows) }
        }
    }

    private fun requestEnabled(enabled: Boolean) {
        if (enabled && !mutable.value.disclosureAccepted) {
            risk(PhoneRisk(PhoneRiskKind.Disclosure))
            return
        }
        val ticket = if (!enabled) privileged.select() else privileged.snapshot()
        if (!enabled) control.background.cancelRootSelection()
        mutate(replace = !enabled, restart = true) {
            if (!wanted(ticket)) throw PhoneUiFailure("phone/selection-changed")
            if (!enabled) control.background.saveKeep(control.background.clearKeep())
            if (enabled && !control.background.preparePhoneEnable() || !control.setEnabled(enabled))
                throw PhoneUiFailure("phone/change")
            if (!enabled) accepted(control.background.setBackgroundMode("off") { wanted(ticket) })
        }
    }

    private fun requestRule(pkg: String, rule: PhoneAppRule) {
        if (!PACKAGE.matches(pkg)) return fail("phone/package")
        val old = control.policies()
        val previous =
            when (pkg) {
                in old.denyPackages -> PhoneAppRule.Deny
                in old.confirmEveryWritePackages -> PhoneAppRule.Confirm
                else -> PhoneAppRule.Allow
            }
        if (
            previous == PhoneAppRule.Deny && rule != PhoneAppRule.Deny ||
                previous == PhoneAppRule.Confirm && rule == PhoneAppRule.Allow
        )
            risk(PhoneRisk(PhoneRiskKind.AppRule, pkg, rule))
        else writeRule(pkg, rule)
    }

    private fun writeRule(pkg: String, rule: PhoneAppRule) = mutate {
        val old = control.policies()
        val deny =
            old.denyPackages.toMutableSet().apply {
                remove(pkg)
                if (rule == PhoneAppRule.Deny) add(pkg)
            }
        val confirm =
            old.confirmEveryWritePackages.toMutableSet().apply {
                remove(pkg)
                if (rule == PhoneAppRule.Confirm) add(pkg)
            }
        if (
            !control.setPolicies(
                mapOf(
                    "denyPackages" to deny.toList(),
                    "confirmEveryWritePackages" to confirm.toList(),
                ),
            )
        )
            throw PhoneUiFailure("phone/change")
        retained.add(pkg)
    }

    private fun addPackage() {
        val pkg = mutable.value.query.trim()
        if (!PACKAGE.matches(pkg)) return fail("phone/package")
        retained.add(pkg)
        update { it.copy(query = pkg) }
        refresh()
    }

    private fun policies(patch: Map<String, Any?>) = mutate {
        if (!control.setPolicies(patch)) throw PhoneUiFailure("phone/change")
    }

    private fun risk(value: PhoneRisk) {
        if (!mutable.value.busy) update { it.copy(risk = value, error = null) }
    }

    private fun confirmRisk() {
        val risk = mutable.value.risk ?: return
        update { it.copy(risk = null) }
        when (risk.kind) {
            PhoneRiskKind.Disclosure ->
                mutate(restart = true) {
                    if (!prefs.edit().putBoolean("disclosureAccepted", true).commit())
                        throw PhoneUiFailure("phone/preferences")
                    if (!control.background.preparePhoneEnable() || !control.setEnabled(true))
                        throw PhoneUiFailure("phone/change")
                }
            PhoneRiskKind.Root -> selectMode("root")
            PhoneRiskKind.AlwaysConfirmOff -> policies(mapOf("confirmMode" to "always"))
            PhoneRiskKind.Unlock ->
                mutate {
                    val key = risk.unlock ?: throw PhoneUiFailure("phone/unlock")
                    if (!control.setPolicies(mapOf("unlocks" to mapOf(key to true))))
                        throw PhoneUiFailure("phone/task-active")
                }
            PhoneRiskKind.AppRule -> writeRule(risk.packageName ?: return, risk.rule ?: return)
            PhoneRiskKind.Storage -> {
                pendingStorageEnable = true
                if (PhoneStorage.isGranted(app)) {
                    pendingStorageEnable = false
                    saveStorage(true)
                } else requestPermissions(PhoneAccessKind.Storage)
            }
            PhoneRiskKind.ResetRules ->
                policies(
                    mapOf(
                        "denyPackages" to PhonePolicies().denyPackages,
                        "confirmEveryWritePackages" to PhonePolicies().confirmEveryWritePackages,
                    ),
                )
        }
    }

    private fun saveStorage(value: Boolean) =
        mutate(restart = true) {
            if (value && !PhoneStorage.isGranted(app)) throw PhoneUiFailure("phone/storage")
            if (!prefs.edit().putBoolean("phoneStorage", value).commit())
                throw PhoneUiFailure("phone/preferences")
        }

    private fun preference(key: String, value: Boolean) = mutate {
        if (!prefs.edit().putBoolean(key, value).commit()) throw PhoneUiFailure("phone/preferences")
    }

    private fun selectMode(mode: String) {
        if (mode !in mutable.value.status.background.supported) return fail("phone/mode")
        val ticket = privileged.select()
        control.background.clearKeepForMode(mode)?.let { clear ->
            scope.launch(Dispatchers.IO) { control.background.saveKeep(clear) }
        }
        if (mode != "root") control.background.cancelRootSelection()
        mutate(replace = true) {
            accepted(control.background.setBackgroundMode(mode) { wanted(ticket) })
        }
    }

    private fun port(): Int? =
        mutable.value.pairingPort
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
            .also {
                if (it == null && mutable.value.pairingPort.isNotBlank()) fail("phone/pairing-port")
            }

    private fun wanted(ticket: Long) = !closed && privileged.current(ticket)

    private fun accepted(result: Map<String, Any?>) {
        if (result["ok"] != true)
            throw PhoneUiFailure(
                result["reason"] as? String ?: result["reasonCode"] as? String ?: "phone/change",
            )
    }

    private fun mutate(
        replace: Boolean = false,
        restart: Boolean = false,
        work: suspend () -> Unit,
    ) {
        if (mutable.value.busy && !replace || closed) return
        if (replace) write?.cancel()
        val own = ++writeSequence
        read?.cancel()
        generation++
        update { it.copy(busy = true, error = null, notice = null) }
        write = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    serial.withLock {
                        currentCoroutineContext().ensureActive()
                        work()
                    }
                }
                if (!closed && own == writeSequence && restart && localRuntime().active) {
                    restartLocalEngine()
                    update {
                        it.copy(
                            notice =
                                AppStrings.get(app, UiR.string.phone_controls_restart_requested),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed && own == writeSequence)
                    fail((error as? PhoneUiFailure)?.code ?: "phone/change")
            } finally {
                if (!closed && own == writeSequence) {
                    update { it.copy(busy = false) }
                    refresh(clearError = false)
                }
            }
        }
    }

    fun reportError(code: String) = fail(code)

    private fun fail(code: String) = update { it.copy(error = phoneControlFailure(app, code)) }

    private fun update(transform: (PhoneControlsUiState) -> PhoneControlsUiState) {
        if (!closed) mutable.update { transform(it).copy(revision = it.revision + 1) }
    }

    private fun granted(permission: String) =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions(kind: PhoneAccessKind) {
        if (permissionPrompt != null) return
        val permissions =
            when (kind) {
                    PhoneAccessKind.Storage ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) emptyList()
                        else
                            listOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                    PhoneAccessKind.Notifications ->
                        if (Build.VERSION.SDK_INT >= 33)
                            listOf(Manifest.permission.POST_NOTIFICATIONS)
                        else emptyList()
                    else -> emptyList()
                }
                .filterNot(::granted)
                .toPersistentList()
        if (permissions.isEmpty()) {
            if (kind == PhoneAccessKind.Storage && !PhoneStorage.isGranted(app)) {
                update { it.copy(needsAllFiles = Build.VERSION.SDK_INT >= 30) }
            } else refresh()
            return
        }
        val id = UUID.randomUUID().toString()
        permissionPrompt = id
        update { it.copy(platformRequest = PhonePlatformRequest(id, permissions, kind)) }
    }

    /** Claims an effect before ActivityResult launch, so recomposition cannot launch it twice. */
    fun claimPermission(id: String): Boolean {
        if (closed || mutable.value.platformRequest?.id != id) return false
        update { it.copy(platformRequest = null) }
        return true
    }

    private fun permissionResult(id: String?, result: Boolean) {
        if (id != null && id != permissionPrompt) return
        permissionPrompt = null
        update { it.copy(platformRequest = null) }
        if (pendingStorageEnable && !PhoneStorage.isGranted(app)) {
            update { it.copy(needsAllFiles = Build.VERSION.SDK_INT >= 30) }
            if (!result) fail("phone/storage")
        }
        refresh(clearError = false)
    }

    private fun markVisited(kind: PhoneAccessKind, visited: Boolean) {
        if (
            kind !in
                setOf(
                    PhoneAccessKind.VendorBackground,
                    PhoneAccessKind.Autostart,
                    PhoneAccessKind.Popup,
                )
        )
            return
        preference("visited_${kind.name}", visited)
    }

    private fun openAccess(kind: PhoneAccessKind) {
        when (kind) {
            PhoneAccessKind.Storage -> {
                requestPermissions(kind)
                return
            }
            PhoneAccessKind.Notifications ->
                if (
                    Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    requestPermissions(kind)
                    return
                }
            else -> Unit
        }
        if (kind == PhoneAccessKind.Accessibility) update { it.copy(awaitingAccessibility = true) }
        val intent = phoneAccessIntent(app, control, kind)
        try {
            if (intent == null) throw PhoneUiFailure("phone/permission")
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            fail("phone/permission")
        }
    }

    override fun onCleared() {
        if (closed) return
        control.removeSink(sink)
        privileged.select()
        closed = true
    }

    private companion object {
        val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        val UNLOCKS = setOf("systemPages", "secretFields", "ownApp")
    }
}
