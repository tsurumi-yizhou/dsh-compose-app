package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import android.net.LinkProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

/**
 * Process-wide owner of the local runtime: install state, the DSH process, logs, and the failure
 * detection around it. The app supplies a host and shares one owner with its service.
 */
class RuntimeController(private val app: Context, private val host: RuntimeHost) :
    EngineProcess.Listener {

    enum class Phase(val wire: String) {
        NOT_INSTALLED("notInstalled"),
        INSTALLING("installing"),
        INSTALLED("installed"),
        STARTING("starting"),
        RUNNING("running"),
        STOPPING("stopping"),
        ERROR("error"),
    }

    fun interface EventSink {
        fun emit(event: Map<String, Any?>)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-runtime") }
    private val sinks = LinkedHashSet<EventSink>()
    private val logDir = File(app.filesDir, "runtime/logs")
    private val runtimeLogs =
        RuntimeLogs(
            File(logDir, "dsh.log"),
            SystemClock::elapsedRealtime,
            post = { action -> main.post { action() } },
            deliver = { entries ->
                val event = mapOf("type" to "logs", "entries" to entries.map { it.toMap() })
                val targets = synchronized(sinks) { sinks.toList() }
                targets.forEach { it.emit(event) }
            },
        )
    private val lkgFile = File(app.filesDir, "runtime/lkg/lkg.json")
    private val prefs = host.preferences

    val manifest: RuntimeManifest by lazy { RuntimeManifest.load(app) }
    private val installer by lazy { RuntimeInstaller(app, manifest, ::note, host::text) }
    @Volatile private var process: EngineProcess? = null
    @Volatile private var lastLaunch: LaunchDiagnostics? = null
    /**
     * The start in progress is turning PRoot's fake hard links into real files (see
     * [L2sMigration]).
     */
    @Volatile
    var phase: Phase = Phase.NOT_INSTALLED
        private set

    @Volatile private var baseUrl: String? = null
    @Volatile private var token: String? = null
    @Volatile private var error: String? = null
    @Volatile private var installProgress: Map<String, Any?> = emptyMap()

    private fun reportInstall(step: String, done: Long = 0L, total: Long = 0L) {
        val progress = mapOf("type" to "progress", "step" to step, "done" to done, "total" to total)
        installProgress = progress
        emit(progress)
    }

    @Volatile private var pendingEnv: Map<String, String> = emptyMap()
    @Volatile private var pendingPhoneStorage = false
    /**
     * Whether the managed AGENTS.md tells the model about ```dsh-ui views (settings, on by
     * default).
     */
    @Volatile private var pendingVisualReplies = true

    // Failure state survives the app being killed through [prefs].
    @Volatile private var strikes = prefs.getInt(K_STRIKES, 0)
    /** Latest unexpected exit; the failure card shows it until a later run gets going. */
    @Volatile private var failure: RunFailure? = readFailure(K_FAILURE)

    /** Latest failure of the main profile. */
    @Volatile private var mainFailure: RunFailure? = readFailure(K_MAIN_FAILURE)

    /** Mode of the current (or last) launched process. */
    @Volatile private var runGen = 0
    @Volatile private var stopGen = -1
    @Volatile private var resetting = false
    // Retire skill writes at the stop request, before the Service receives ACTION_STOP.
    // This gate does not change process lifecycle or A5's ownership/launch decisions.
    private val managedSkillGate = ManagedSkillRuntimeGate()
    private val managedSkills =
        ManagedSkillQueue(
            execute = { action -> worker.execute { action() } },
            deliver = { action ->
                if (Looper.myLooper() == Looper.getMainLooper()) action()
                else main.post { action() }
            },
        )
    private val autoRestarts = ArrayDeque<Long>()
    @Volatile private var readyAt: Long? = null
    @Volatile private var healthyAt: Long? = null

    /**
     * The main profile as it was when the current normal run booted; becomes the LKG when healthy.
     */
    @Volatile private var bootProfile: ProfileSnapshot? = null
    private val runTail = ArrayDeque<String>()
    private val networkAccess = NetworkAccess()
    private val dnsLog = DnsConfigLog()

    /** Start → spawn → listening, logged in one line when DSH comes up. */
    private val timeline = StartupTimeline.forThisProcess()

    init {
        phase =
            try {
                if (installer.isCurrent()) Phase.INSTALLED else Phase.NOT_INSTALLED
            } catch (e: Exception) {
                error = "manifest: ${e.message}"
                Phase.ERROR
            }
    }

    fun addSink(sink: EventSink) = synchronized(sinks) { sinks += sink }

    fun removeSink(sink: EventSink) = synchronized(sinks) { sinks -= sink }

    fun status(): Map<String, Any?> =
        mapOf(
            "abi" to
                (runCatching { manifest.abi }.getOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull()),
            "bundledVersion" to runCatching { manifest.runtimeVersion }.getOrNull(),
            "installedVersion" to runCatching { installer.installedVersion() }.getOrNull(),
            "installStep" to installProgress["step"],
            "installDone" to installProgress["done"],
            "installTotal" to installProgress["total"],
            "dshVersion" to runCatching { manifest.dshVersion }.getOrNull(),
            "nodeVersion" to runCatching { manifest.nodeVersion }.getOrNull(),
            "phase" to phase.wire,
            "baseUrl" to baseUrl,
            "token" to token,
            "managedSkillGeneration" to managedSkillGate.generationFor(token),
            "error" to error,
            "mode" to "normal",
            "strikes" to strikes,
            "failure" to failure?.toMap(),
            "mainFailure" to mainFailure?.toMap(),
            "healthyAt" to healthyAt,
            "networkBlocked" to networkAccess.isBlocked,
            "networkBlockedAt" to networkAccess.blockedAt,
            "currentExecutionMode" to process?.executionMode,
            "launchSelectedMode" to lastLaunch?.selectedMode,
            "launchSpawned" to (lastLaunch?.spawned == true),
            "launchFailureReason" to lastLaunch?.failureReason,
        )

    /** [dropKept]: give up what a reset kept but cannot be put back (see [KeepFailed]). */
    fun install(dropKept: Boolean = false, onDone: (Throwable?) -> Unit) {
        if (phase == Phase.INSTALLING) return onDone(null)
        if (installer.isCurrent()) {
            if (phase == Phase.NOT_INSTALLED) setPhase(Phase.INSTALLED)
            return onDone(null)
        }
        if (process != null || phase == Phase.STARTING || phase == Phase.STOPPING)
            return onDone(IllegalStateException(host.text(RuntimeMessage.STOP_ENGINE_FIRST)))
        reportInstall("extract")
        setPhase(Phase.INSTALLING)
        worker.execute {
            try {
                log(
                    "[dsh-app] unpacking runtime ${manifest.runtimeVersion} (${manifest.abi})",
                    "system",
                )
                val started = System.currentTimeMillis()
                if (dropKept) log("[dsh-app] installing without what could not be kept", "system")
                installer.install(dropKept, onInstallStage = { reportInstall(it) }) { done, total ->
                    reportInstall("extract", done, total)
                }
                log(
                    "[dsh-app] runtime ready in ${(System.currentTimeMillis() - started) / 1000}s",
                    "system",
                )
                error = null
                // A new runtime may well fix what failed before.
                strikes = 0
                failure = null
                prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).apply()
                installProgress = emptyMap()
                setPhase(Phase.INSTALLED)
                main.post { onDone(null) }
            } catch (e: Throwable) {
                error = "install failed: ${e.message}"
                log("[dsh-app] ${error}", "system")
                setPhase(Phase.ERROR)
                main.post { onDone(e) }
            }
        }
    }

    /** Starts the foreground service, which calls [startProcess]. */
    fun start(
        env: Map<String, String>,
        phoneStorage: Boolean = false,
        visualReplies: Boolean = pendingVisualReplies,
    ) {
        pendingPhoneStorage = phoneStorage
        pendingVisualReplies = visualReplies
        if (phase == Phase.RUNNING || phase == Phase.STARTING) return
        pendingEnv = env
        host.startService()
    }

    /**
     * Writes the app's diagnostics snapshot where the engine's agent can read it
     * (`$DSH_HOME/dsh-app/diagnostics.md`). A few KB on the platform thread, after the engine came
     * up or when the person asks from 设置 → 诊断. Null when the runtime is not installed or the write
     * failed.
     */
    fun publishDiagnostics(markdown: String): Map<String, Any?>? =
        try {
            if (!installer.isCurrent()) null
            else {
                val file =
                    GuestSetup.writeDiagnostics(
                        installer.rootfs,
                        markdown,
                    )
                mapOf("path" to file.absolutePath, "bytes" to file.length())
            }
        } catch (_: Exception) {
            null
        }

    fun stop() {
        managedSkillGate.stop(runGen)
        host.requestStop()
    }

    /** Private bundled-skill transaction; never falls back to a different executor/root. */
    fun installManagedSkill(
        owner: ManagedSkillOwner,
        raw: Any?,
        onDone: (Map<String, Any?>) -> Unit,
    ) {
        val request = ManagedSkillRequest.parse(raw)
        val run = request?.let {
            managedSkillGate.capture(owner, it.expectedToken, it.expectedGeneration)
        }
        if (request == null || run == null) {
            onDone(
                mapOf(
                    "result" to null,
                    "current" to false,
                    "reason" to if (request == null) "invalid" else "stale",
                ),
            )
            return
        }
        managedSkills.submit(
            owner,
            request,
            installer.rootfs,
            current = {
                managedSkillGate.current(run) &&
                    phase == Phase.RUNNING &&
                    runGen == run.runtimeGeneration &&
                    stopGen < run.runtimeGeneration &&
                    !resetting &&
                    token == run.token
            },
            onDone = onDone,
        )
    }

    fun cancelManagedSkill(owner: ManagedSkillOwner, requestId: String? = null) {
        managedSkills.cancel(owner, requestId)
    }

    fun attachManagedSkillOwner(): ManagedSkillOwner {
        val owner = ManagedSkillOwner()
        // Gate attachment and the last unretired real ready share a short lock.
        // Do not replay a phase/token observation across onReady or retirement.
        managedSkillGate.attach(owner)?.let { managedSkills.cancel(it) }
        return owner
    }

    fun detachManagedSkillOwner(owner: ManagedSkillOwner) {
        managedSkillGate.detach(owner)
        managedSkills.cancel(owner)
    }

    @Synchronized
    fun startProcess() {
        if (process != null || phase == Phase.INSTALLING || phase == Phase.STOPPING) return
        lastLaunch = null
        if (!installer.isCurrent()) {
            error = "runtime not installed"
            setPhase(Phase.ERROR)
            host.stopService()
            return
        }
        baseUrl = null
        token = null
        error = null
        val gen = ++runGen
        readyAt = null
        healthyAt = null
        synchronized(runTail) { runTail.clear() }
        setPhase(Phase.STARTING)
        timeline.requested()
        worker.execute {
            var launched: EngineProcess? = null
            try {
                BundledSkills.install(app, installer.rootfs)
                RuntimeProfile.prepare(installer.rootfs)
                noteDns(GuestSetup.writeResolvConf(app, installer.rootfs), DnsConfigSource.START)
                val binds = host.storageBinds(pendingPhoneStorage, installer.rootfs)
                val storage = binds.isNotEmpty()
                // Prepare the phone bridge and guest files before DSH starts.
                val capabilities = host.prepareCapabilities(installer.rootfs) { log(it, "system") }
                RuntimeBundles.install(app, installer.rootfs)
                if (pendingPhoneStorage && !storage)
                    log("[dsh-app] phone storage requested but permission not granted", "system")
                val env = LinkedHashMap<String, String>()
                env["TZ"] = java.util.TimeZone.getDefault().id
                env.putAll(pendingEnv)
                host.applyEnvironment(env, manifest)
                bootProfile =
                    try {
                        LastKnownGood.read(installer.rootfs)
                    } catch (_: Exception) {
                        null
                    }
                // Stopped (or started again) while this was queued: launching now would leave an
                // orphan.
                if (stopGen >= gen || gen != runGen) {
                    host.engineStopped()
                    return@execute
                }
                val p = EngineProcess(app, manifest, installer.rootfs, this)
                launched = p
                lastLaunch = p.launchDiagnostics
                process = p
                timeline.spawned()
                p.start(
                    env,
                    binds,
                    capabilities.patches,
                ) {
                    GuestSetup.writeAgentsMd(
                        installer.rootfs,
                        storage,
                        capabilities.instructions,
                        pendingVisualReplies,
                    )
                }
                host.capabilitiesLaunched(capabilities, p.mountedPatches)
            } catch (e: Throwable) {
                // Keep ownership while the old UID sweep runs, including a
                // failure after spawning but before the waiter was allocated.
                launched?.launchDiagnostics?.failed("startFailed")
                launched?.let { p ->
                    p.failStart(e.message ?: e.javaClass.simpleName)
                    p.awaitExit()
                }
                if (gen != runGen) return@execute
                process = null
                host.engineStopped()
                if (stopGen >= gen) return@execute
                error = "start failed: ${e.message}"
                log("[dsh-app] $error", "system")
                setPhase(Phase.ERROR)
                host.stopService()
            }
        }
    }

    @Synchronized
    fun stopProcess() {
        if (phase == Phase.INSTALLING) return
        stopGen = runGen
        val p = process
        if (p == null) {
            if (phase == Phase.STARTING) {
                // The launch is still queued on the worker: finish the stop behind it.
                setPhase(Phase.STOPPING)
                worker.execute {
                    val launched = process
                    if (launched != null) launched.stop()
                    else if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                }
            } else if (!resetting && phase != Phase.NOT_INSTALLED && phase != Phase.ERROR) {
                // Keep ERROR: the service's own stop after a crash must not hide the failure.
                setPhase(Phase.INSTALLED)
            }
            return
        }
        setPhase(Phase.STOPPING)
        p.stop()
    }

    /** The default network changed: point the guest's resolv.conf at its DNS. */
    fun refreshDns(properties: LinkProperties) {
        val servers = GuestSetup.pickDns(properties.dnsServers)
        worker.execute {
            if ((phase == Phase.RUNNING || phase == Phase.STARTING) && installer.isCurrent()) {
                noteDns(
                    GuestSetup.writeResolvConf(installer.rootfs, servers),
                    DnsConfigSource.NETWORK,
                )
            }
        }
    }

    private fun noteDns(report: DnsConfigReport, source: DnsConfigSource) {
        dnsLog
            .line(report, source, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            ?.let { log(it, "system") }
    }

    /**
     * The system blocked or restored this app's network (the host service's default network
     * callback).
     */
    fun onNetworkBlocked(blocked: Boolean, inForeground: Boolean) {
        val line = networkAccess.onBlocked(blocked, inForeground) ?: return
        log(line, "system")
        emitStatus()
    }

    /** The host service stopped watching the network. */
    fun onNetworkWatchStopped() = networkAccess.reset()

    /** A line of the app's own into the runtime log. */
    fun note(line: String) = log(line, "system")

    /**
     * Stops DSH and waits for it to exit, then deletes the runtime with everything in /root except
     * [keep], which the next install puts back.
     */
    fun reset(keep: Set<ResetKeep> = emptySet(), onDone: (Throwable?) -> Unit) {
        stopGen = runGen
        resetting = true
        setPhase(Phase.STOPPING)
        worker.execute {
            try {
                process?.let { p ->
                    p.stop()
                    check(p.awaitExit(15_000)) {
                        "runtime stop incomplete; retry reset after cleanup"
                    }
                }
                lastLaunch = null
                try {
                    check(runtimeLogs.pauseDisk()) { "log reset pause timed out" }
                    installer.reset(keep.flatMap { it.paths })
                } finally {
                    runtimeLogs.resumeDisk()
                }
                if (keep.isNotEmpty())
                    log("[dsh-app] reset keeps ${keep.joinToString { it.wire }}", "system")
                baseUrl = null
                token = null
                error = null
                // A reset also clears recorded failures.
                strikes = 0
                failure = null
                mainFailure = null
                prefs
                    .edit()
                    .remove(K_SAFE_STATE)
                    .remove(K_SAFE_REASON)
                    .remove(K_STRIKES)
                    .remove(K_FAILURE)
                    .remove(K_MAIN_FAILURE)
                    .remove(K_MOVED)
                    .apply()
                setPhase(Phase.NOT_INSTALLED)
                main.post { onDone(null) }
            } catch (e: Throwable) {
                log("[dsh-app] reset failed: ${e.message}", "system")
                // Still stopping while the old process hasn't exited (its exit report moves the
                // phase on); otherwise the runtime is either still whole or gone.
                setPhase(
                    when {
                        process != null -> Phase.STOPPING
                        runCatching { installer.isCurrent() }.getOrDefault(false) -> Phase.INSTALLED
                        else -> Phase.NOT_INSTALLED
                    },
                )
                main.post { onDone(e) }
            } finally {
                resetting = false
            }
        }
    }

    fun recentLogs(lines: Int): List<String> = runtimeLogs.recent(lines)

    fun logSnapshot(lines: Int): Map<String, Any?> = runtimeLogs.snapshot(lines).toMap()

    // ── Safe mode / last known good (called by RuntimePlugin) ───────────────────────

    /** Hides the failure card; the strike count stays. */
    fun clearFailure() {
        failure = null
        prefs.edit().remove(K_FAILURE).apply()
        emitStatus()
    }

    /**
     * Output around the failure on the card, else around the main profile's last failure (tokens
     * masked).
     */
    fun failureLog(): List<String> {
        val name =
            when {
                failure != null -> LATEST_FAILURE_LOG
                mainFailure != null -> MAIN_FAILURE_LOG
                else -> return emptyList()
            }
        return try {
            File(logDir, name).readLines()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * What [restoreLastKnownGood] would change: `{available, at, disable, enable, changed,
     * patchChanged, empty}`.
     */
    fun lastKnownGoodStatus(onDone: (Map<String, Any?>) -> Unit) {
        worker.execute {
            val result: Map<String, Any?> =
                try {
                    val lkg = LastKnownGood.load(lkgFile)
                    val current = LastKnownGood.read(installer.rootfs)
                    if (lkg == null || current == null) {
                        mapOf("available" to false, "at" to lkg?.at)
                    } else {
                        LkgPlan.of(lkg.snapshot, current).toMap() +
                            mapOf("available" to true, "at" to lkg.at)
                    }
                } catch (e: Exception) {
                    mapOf("available" to false, "error" to e.message)
                }
            main.post { onDone(result) }
        }
    }

    /** Puts the main profile's bundle list and settings layer back to the last good run's. */
    fun restoreLastKnownGood(onDone: (Map<String, Any?>?, Throwable?) -> Unit) {
        if ((phase == Phase.RUNNING || phase == Phase.STARTING)) {
            return onDone(
                null,
                IllegalStateException(host.text(RuntimeMessage.STOP_ENGINE_FIRST)),
            )
        }
        worker.execute {
            try {
                val lkg =
                    LastKnownGood.load(lkgFile)
                        ?: throw IllegalStateException(host.text(RuntimeMessage.NO_GOOD_CONFIG))
                val plan =
                    LastKnownGood.restore(
                        installer.rootfs,
                        lkg.snapshot,
                        RuntimeFiles.stamp(),
                        host.text(RuntimeMessage.CONFIG_MISSING),
                        host.text(RuntimeMessage.CONFIG_BUSY),
                    )
                log(
                    "[dsh-app] restored the last good plugin set from ${RuntimeFiles.formatTime(lkg.at)}: " +
                        "disabled ${plan.disable}, enabled ${plan.enable}, settings layer ${if (plan.patchChanged) "restored" else "unchanged"}",
                    "system",
                )
                main.post { onDone(plan.toMap(), null) }
            } catch (e: Throwable) {
                log("[dsh-app] restoring the last good plugin set failed: ${e.message}", "system")
                main.post { onDone(null, e) }
            }
        }
    }

    /** Called two minutes after the login line if the same run is still up. */
    private fun onHealthy() {
        healthyAt = null
        strikes = 0
        failure = null
        mainFailure = null
        prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).remove(K_MAIN_FAILURE).apply()
        val booted = bootProfile
        worker.execute {
            try {
                if (
                    LastKnownGood.pinBooted(booted) {
                        LastKnownGood.save(lkgFile, it, System.currentTimeMillis())
                    }
                ) {
                    log(
                        "[dsh-app] DSH has been up for 2 minutes; recorded its plugin set as the last good one",
                        "system",
                    )
                }
            } catch (e: Exception) {
                log("[dsh-app] could not record the last good plugin set: ${e.message}", "system")
            }
        }
        emitStatus()
    }

    /** Bundles enabled or upgraded since the last good run: the first suspects of a new failure. */
    private fun recentChanges(): List<String> =
        try {
            val lkg = LastKnownGood.load(lkgFile)
            val current = bootProfile ?: LastKnownGood.read(installer.rootfs)
            if (lkg == null || current == null) {
                emptyList()
            } else {
                val plan = LkgPlan.of(lkg.snapshot, current)
                (plan.disable + plan.changed).distinct()
            }
        } catch (_: Exception) {
            emptyList()
        }

    private fun recordFailure(f: RunFailure, tail: List<String>) {
        failure = f
        val json = runtimeJson.encodeToString(FailureRecord.serializer(), f.toRecord())
        val edit = prefs.edit().putString(K_FAILURE, json)
        // A later kill from outside says nothing about the profile: keep the plugin failure it
        // followed.
        val main = !f.safeMode && (f.kind != FailureKind.KILLED || mainFailure == null)
        if (main) {
            mainFailure = f
            edit.putString(K_MAIN_FAILURE, json)
        }
        edit.apply()
        try {
            logDir.mkdirs()
            val text = tail.joinToString("\n", postfix = "\n") { CrashClassifier.redact(it) }
            File(logDir, LATEST_FAILURE_LOG).writeText(text)
            if (main) File(logDir, MAIN_FAILURE_LOG).writeText(text)
        } catch (_: Exception) {}
    }

    private fun readFailure(key: String): RunFailure? =
        try {
            prefs.getString(key, null)?.let { text ->
                runtimeJson.decodeFromString(FailureRecord.serializer(), text).toFailure()
            }
        } catch (_: Exception) {
            null
        }

    private fun notice(text: String) = emit(mapOf("type" to "notice", "text" to text))

    // EngineProcess.Listener

    override fun onLog(line: String, stream: String) {
        if (stream != "system") {
            synchronized(runTail) {
                runTail.addLast(line)
                while (runTail.size > CrashClassifier.TAIL_LINES) runTail.removeFirst()
            }
        }
        log(line, stream)
    }

    @Synchronized
    override fun onReady(source: EngineProcess, baseUrl: String, token: String) {
        // A late report from a stopped or replaced process.
        if (source !== process || phase == Phase.STOPPING) return
        host.cancelStoppedAlert()
        this.baseUrl = baseUrl
        this.token = token
        if ((phase == Phase.STARTING || phase == Phase.RUNNING) && stopGen < runGen && !resetting) {
            managedSkillGate.ready(runGen, token)
        }
        readyAt = SystemClock.elapsedRealtime()
        if (failure != null) {
            // This run got going, so the previous failure card is stale; strikes reset only when
            // healthy.
            failure = null
            prefs.edit().remove(K_FAILURE).apply()
        }
        run {
            // First boot creates the profile after startProcess reads it. Capture it before
            // RUNNING lets the app change it; a later disk read would not describe this boot.
            bootProfile =
                LastKnownGood.bootedAtReady(bootProfile) { LastKnownGood.read(installer.rootfs) }
        }
        healthyAt = System.currentTimeMillis() + RestartPolicy.HEALTHY_MS
        log(
            "[dsh-app] DSH is listening at $baseUrl",
            "system",
        )
        timeline.ready()?.let { log(it, "system") }
        setPhase(Phase.RUNNING)
        host.engineReady()
        val gen = runGen
        main.postDelayed(
            { if (gen == runGen && phase == Phase.RUNNING && process != null) onHealthy() },
            RestartPolicy.HEALTHY_MS,
        )
    }

    @Synchronized
    override fun onExit(source: EngineProcess, code: Int, expected: Boolean) {
        if (source !== process) return
        try {
            if (!expected && stopGen < runGen) {
                source.launchDiagnostics.failed(
                    if (source.startupError != null) "startFailed" else "exit:$code",
                )
            }
            process = null
            host.engineStopped()
            baseUrl = null
            token = null
            healthyAt = null
            log("[dsh-app] DSH exited with code $code", "system")
            if (expected || stopGen >= runGen) {
                if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                host.stopService()
                return
            }
            source.startupError?.let {
                // A local startup failure is neither a user stop nor proof of a
                // plugin failure; do not count it toward automatic safe mode.
                error = "start failed: $it"
                setPhase(Phase.ERROR)
                host.postStoppedAlert()
                host.stopService()
                return
            }
            val tail = synchronized(runTail) { runTail.toList() }
            var f =
                CrashClassifier.classify(
                    code,
                    readyAt,
                    SystemClock.elapsedRealtime(),
                    System.currentTimeMillis(),
                    tail,
                    false,
                )
            f = f.copy(recent = recentChanges())
            val now = SystemClock.elapsedRealtime()
            val delay =
                RestartPolicy.autoRestartDelay(
                    f.kind,
                    code,
                    readyAt?.let { now - it },
                    autoRestarts.toList(),
                    now,
                )
            if (delay != null && host.serviceRunning) {
                // Killed from outside after a good run (memory pressure, a phantom-process sweep):
                // try again.
                autoRestarts.removeAll { now - it >= 600_000L }
                autoRestarts.addLast(now)
                val gen = runGen
                setPhase(Phase.STARTING)
                log("[dsh-app] killed from outside; restarting in ${delay / 1000}s", "system")
                main.postDelayed(
                    {
                        if (
                            gen == runGen &&
                                stopGen < gen &&
                                phase == Phase.STARTING &&
                                process == null &&
                                host.serviceRunning
                        ) {
                            startProcess()
                        }
                    },
                    delay,
                )
                return
            }
            recordFailure(f, tail)
            if (f.kind.counts) strikes++
            prefs.edit().putInt(K_STRIKES, strikes).apply()
            log(
                "[dsh-app] classified as ${f.kind.wire}${if (f.safeMode) " (safe mode)" else ""}; consecutive failures: $strikes",
                "system",
            )
            error = "DSH exited unexpectedly (code $code)"
            setPhase(Phase.ERROR)
            host.postStoppedAlert()
            host.stopService()
        } finally {
            runtimeLogs.flush()
        }
    }

    /** Starts again right away; [startProcess] picks the mode. [how] names it if that fails. */
    private fun restartNow(how: String) {
        val gen = runGen
        main.post {
            if (gen != runGen || stopGen >= gen) return@post
            if (host.serviceRunning) {
                startProcess()
            } else {
                phase = Phase.INSTALLED
                try {
                    start(pendingEnv, pendingPhoneStorage)
                } catch (e: Exception) {
                    log("[dsh-app] could not restart $how: ${e.message}", "system")
                    setPhase(Phase.ERROR)
                }
            }
        }
    }

    private fun log(line: String, stream: String) = runtimeLogs.accept(line, stream)

    private fun setPhase(next: Phase) {
        if (next != Phase.RUNNING) managedSkillGate.retire()
        phase = next
        emitStatus()
        host.updateNotification()
    }

    private fun emitStatus() = emit(mapOf("type" to "status", "status" to status()))

    private fun emit(event: Map<String, Any?>) {
        val targets = synchronized(sinks) { sinks.toList() }
        if (targets.isEmpty()) return
        main.post { targets.forEach { it.emit(event) } }
    }

    companion object {
        private const val K_SAFE_STATE = "safeState"
        private const val K_SAFE_REASON = "safeReason"
        private const val K_STRIKES = "strikes"
        private const val K_FAILURE = "failure"
        private const val K_MAIN_FAILURE = "mainFailure"
        private const val K_MOVED = "movedAside"
        private const val K_AUTO_SAFE = "autoSafe"
        private const val MAIN_FAILURE_LOG = "failure-normal.log"
        private const val LATEST_FAILURE_LOG = "failure-latest.log"
    }
}
