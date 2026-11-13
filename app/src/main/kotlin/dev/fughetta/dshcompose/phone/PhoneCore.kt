package dev.fughetta.dshcompose.phone

import dev.fughetta.dshcompose.domain.phone.Availability
import dev.fughetta.dshcompose.domain.phone.HandoffCause
import dev.fughetta.dshcompose.domain.phone.HandoffResult
import dev.fughetta.dshcompose.domain.phone.PhoneAction
import dev.fughetta.dshcompose.domain.phone.PhonePolicies
import dev.fughetta.dshcompose.domain.phone.PhoneScreen
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import java.time.ZoneId

enum class A11yState {
    CONNECTED,
    ENABLED_NOT_CONNECTED,
    DISABLED,
}

/** What the core needs from the device; [PhoneControl] on Android, a fake in the JVM tests. */
data class PhoneEnv(
    val enabled: () -> Boolean,
    val policies: () -> PhonePolicies,
    val isLocked: () -> Boolean,
    val gate: () -> UserGate?,
    val driver: (PhoneScreen) -> PhoneDriver?,
    val a11yState: () -> A11yState,
    val backgroundUnavailable: () -> String,
    val system: () -> SystemApi,
    val zone: () -> ZoneId,
    val notifyFix: (String) -> Unit,
    val uiText: () -> PhoneUiText = { PhoneUiText.DEFAULT },
    val device: () -> PhoneDevice? = { null },
    val installApk: (String, String, SerialWorker.Scope) -> ToolOutput = { _, _, _ ->
        ToolOutput("INSTALL_UNAVAILABLE", isError = true)
    },
    /** Releases background input ownership before handing the task to the person. */
    val beforeHandoff: (PhoneScreen) -> String? = { null },
    val sleep: (Long) -> Unit = Thread::sleep,
)

/**
 * Runs the fixed tool set: argument checks, screen-id routing (`s` main / `b` background, never
 * falling back across screens), the safety rules, one serial worker with per-call budgets, the
 * post-action screen, and the action log. [call] is what the MCP server invokes.
 */
class PhoneCore(
    internal val env: PhoneEnv,
    val tasks: PhoneTasks,
    val log: ActionLog,
    val worker: SerialWorker = SerialWorker("dsh-phone"),
) {
    internal val ui: PhoneUiText
        get() = env.uiText()

    val safety = SafetyGate(env.policies, tasks, env.gate, uiText = env.uiText())

    /** The overlay's status line; 免确认 stays visible the whole time nothing is asked. */
    internal fun say(text: String) {
        env.gate()
            ?.status(
                if (safety.waived()) ui.get("phone_waived_status", "免确认 · %1\$s", text) else text,
            ) // l10n:keep test default
    }

    /** The screen of the last screen-based call, for `handoff`. */
    @Volatile internal var lastScreen: PhoneScreen = PhoneScreen.MAIN

    /**
     * The task's last hard refusal (密码框, 系统设置, 不碰名单), for the `handoff` that usually follows it:
     * the card says which rule it was. Cleared by a write that went through, a new task, or the
     * handoff it named.
     */
    @Volatile private var refusal: Pair<PhoneTasks.Owner, HandoffCause>? = null

    internal class Out(
        val text: String,
        val error: Boolean = false,
        val screen: ScreenSnapshot? = null,
        val app: String? = null,
        val summary: String? = null,
        val decision: String? = null,
    )

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        // In flight from here until the result goes back: no turn ends while a call runs
        // (PhoneTasks.finish).
        if (ApkInstallGate.active) return ToolOutput("INSTALL_BUSY", isError = true)
        tasks.callStarted()
        try {
            return runCall(name, args)
        } finally {
            tasks.callEnded()
        }
    }

    private fun runCall(name: String, args: Map<String, Any?>): ToolOutput {
        if (name !in PhoneToolCatalog.names) {
            // l10n:keep agent tool result
            return ToolOutput(
                "没有这个手机工具：$name。可用：${PhoneToolCatalog.names.joinToString("、")}",
                isError = true,
            )
        }
        if (!env.enabled()) {
            // l10n:keep agent tool result
            return ToolOutput("手机操控已在 DSH 设置里关闭。请用户在 设置 → 手机操控 里打开后重启引擎。", isError = true)
        }
        // Discovery never starts or adopts a phone task. Viewing a restored chat grants nothing.
        if (name == PhoneToolCatalog.DEVICES) {
            val devices = listOfNotNull(env.device()?.toMap())
            return ToolOutput(Json.write(mapOf("devices" to devices)))
        }
        if (name == PhoneToolCatalog.INSTALL_APK) {
            val deviceId =
                (args["deviceId"] as? String)?.takeIf(String::isNotBlank)
                    ?: return ToolOutput(
                        "INSTALL_DEVICE_REQUIRED: Select a target from devices and pass deviceId. Do not infer the target from the open conversation.",
                        isError = true,
                    )
            if (env.device()?.id != deviceId)
                return ToolOutput(
                    "INSTALL_DEVICE_MISMATCH: The selected device is not served by this connection. Do not switch devices or retry automatically.",
                    isError = true,
                )
        }
        val outcome =
            worker.run(budgetMs(name), MAX_TOTAL_MS) { scope -> execute(name, Args(args), scope) }
        tasks.touch()
        val out =
            when (outcome) {
                is SerialWorker.Outcome.Done -> outcome.value
                is SerialWorker.Outcome.TimedOut -> {
                    val text =
                        if (outcome.queued) {
                            // l10n:keep agent tool result
                            "前面的手机操作还没结束，这一步没有执行。稍后再试。"
                        } else {
                            // l10n:keep agent tool result
                            "这一步超时了，可能执行了也可能没有。不要自动重试同一个动作：先用 screen 看看现在的屏幕。"
                        }
                    Out(text, error = true, summary = name, decision = "timeout")
                }
                SerialWorker.Outcome.Interrupted ->
                    Out(
                        PhoneTasks.STOPPED_MESSAGE + "。",
                        error = true,
                        summary = name,
                        decision = "stopped",
                    )
                is SerialWorker.Outcome.Failed ->
                    Out(
                        // l10n:keep agent tool result
                        "手机操作出错：${outcome.error.javaClass.simpleName}${outcome.error.message?.let { "：$it" } ?: ""}",
                        error = true,
                        summary = name,
                        decision = "error",
                    )
            }
        out.summary?.let {
            log.add(name, out.app, it, out.decision ?: if (out.error) "error" else "ok")
        }
        noteRefusal(name, out.decision)
        val interjections = tasks.drainInterjections()
        val text = buildString {
            // l10n:keep agent tool result
            for (line in interjections) append("用户插话：「").append(line).append("」\n\n")
            append(out.text)
            out.screen?.let {
                if (out.text.isNotEmpty()) append("\n\n")
                append(it.text)
            }
        }
        val image = out.screen?.image
        return ToolOutput(text, out.error, image, if (image != null) out.screen.imageMime else null)
    }

    private fun noteRefusal(tool: String, decision: String?) {
        val owner =
            tasks.activeOwner()
                ?: run {
                    refusal = null
                    return
                }
        val cause = HandoffCause.ofDecision(decision)
        if (cause != null) {
            refusal = owner to cause
        } else if (tool in WRITES && decision in DONE) {
            refusal = null
        }
    }

    private fun execute(name: String, a: Args, scope: SerialWorker.Scope): Out {
        when (val b = tasks.begin()) {
            is PhoneTasks.Begin.Refused ->
                return Out(b.message + "。", error = true, summary = name, decision = "stopped")
            is PhoneTasks.Begin.Go -> Unit
        }
        if (tasks.isPaused) {
            val go = scope.userWait { tasks.awaitResume(PAUSE_WAIT_MS) }
            if (!go) {
                // Stopped, or the task ended under the wait (the engine went away): never go on.
                return if (tasks.isStopped() || !tasks.isActive) {
                    Out(
                        PhoneTasks.STOPPED_MESSAGE + "。",
                        error = true,
                        summary = name,
                        decision = "stopped",
                    )
                } else {
                    // l10n:keep agent tool result
                    Out(
                        "用户暂停了手机操作，超过 10 分钟没有恢复。停下来，告诉用户可以恢复后再继续。",
                        error = true,
                        summary = name,
                        decision = "paused",
                    )
                }
            }
        }
        return when (name) {
            PhoneToolCatalog.INSTALL_APK -> {
                val path = a.str("path") ?: return Out("INSTALL_PATH_REQUIRED", error = true)
                val deviceId =
                    a.str("deviceId") ?: return Out("INSTALL_DEVICE_REQUIRED", error = true)
                if (env.isLocked()) return locked(name)
                val result = env.installApk(path, deviceId, scope)
                Out(
                    result.text,
                    error = result.isError,
                    summary = name,
                    decision =
                        when {
                            result.isError -> "install-failed"
                            result.text.startsWith("INSTALL_AWAITING_CONFIRMATION") ->
                                "install-pending"
                            else -> "installed"
                        },
                )
            }
            PhoneToolCatalog.APPS -> apps(a)
            PhoneToolCatalog.OPEN -> open(a, scope)
            PhoneToolCatalog.INTENT -> intent(a)
            PhoneToolCatalog.NOTIFY -> notify(a)
            PhoneToolCatalog.CLIPBOARD -> clipboard(a)
            PhoneToolCatalog.SCREEN -> screen(a)
            PhoneToolCatalog.COLLECT -> collect(a)
            PhoneToolCatalog.TAP,
            PhoneToolCatalog.TYPE,
            PhoneToolCatalog.SCROLL,
            PhoneToolCatalog.TAP_XY -> act(name, a, scope)
            PhoneToolCatalog.KEY -> key(a)
            PhoneToolCatalog.HANDOFF -> handoff(a, scope)
            // l10n:keep agent tool result
            else -> Out("没有这个手机工具：$name", error = true)
        }
    }

    // ── System actions ──────────────────────────────────────────────────────────────

    // ── Screens ─────────────────────────────────────────────────────────────────────
    internal fun unavailable(screen: PhoneScreen): String =
        when (screen) {
            PhoneScreen.BACKGROUND -> env.backgroundUnavailable()
            PhoneScreen.MAIN ->
                when (env.a11yState()) {
                    A11yState.ENABLED_NOT_CONNECTED -> {
                        env.notifyFix("a11y")
                        // l10n:keep agent tool result
                        "无障碍服务没连上（被系统关了或还在启动）：已通知用户到系统设置重新打开。现在只能用 apps / open / intent / notify / clipboard。"
                    }
                    else -> {
                        env.notifyFix("a11y")
                        // l10n:keep agent tool result
                        "无障碍没开，看不了屏幕：已通知用户到 DSH 设置 → 手机操控 开启。现在只能用 apps / open / intent / notify / clipboard。"
                    }
                }
        }

    /** The driver for [screen], or the sentence explaining why there is none. */
    internal fun driverFor(screen: PhoneScreen): Either<String, PhoneDriver> {
        val d = env.driver(screen) ?: return Either.Left(unavailable(screen))
        val av =
            try {
                d.availability()
            } catch (e: Exception) {
                Availability(false, null)
            }
        if (!av.ok) {
            if (screen == PhoneScreen.MAIN) env.notifyFix("a11y")
            return Either.Left(av.reason ?: unavailable(screen))
        }
        return Either.Right(d)
    }

    internal fun routeScreenId(
        id: String?,
        parameterName: String = "screen",
    ): Either<String, PhoneScreen> {
        // l10n:keep agent tool result
        if (id == null) return Either.Left("缺少 $parameterName（屏幕号，如 s7）。先调用 screen 拿到屏幕号。")
        return PhoneScreen.ofScreenId(id)?.let { Either.Right(it) }
            // l10n:keep agent tool result
            ?: Either.Left("屏幕号「$id」不对：主屏以 s 开头（如 s7），后台屏以 b 开头（如 b3）。先调用 screen 拿到屏幕号。")
    }

    internal fun locked(summary: String) =
        // l10n:keep agent tool result
        Out(
            "手机锁着屏，DSH 不在锁屏状态下操作。请用户解锁后再说一声。",
            error = true,
            summary = summary,
            decision = "refused:locked",
        )

    internal fun screen(a: Args): Out {
        val which = if (a.bool("background") == true) PhoneScreen.BACKGROUND else PhoneScreen.MAIN
        val summary =
            if (which == PhoneScreen.BACKGROUND) ui.get("phone_log_background_read", "读后台屏")
            else ui.get("phone_log_screen_read", "读屏") // l10n:keep test default
        val driver =
            when (val d = driverFor(which)) {
                is Either.Left ->
                    return Out(d.value, error = true, summary = summary, decision = "unavailable")
                is Either.Right -> d.value
            }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        say(
            if (which == PhoneScreen.BACKGROUND) ui.get("phone_reading_background", "DSH 正在看后台屏")
            else ui.get("phone_reading_screen", "DSH 正在看屏幕"),
        ) // l10n:keep test default
        val snap =
            driver.observe(detail = a.bool("detail") == true, image = a.bool("image") == true)
        tasks.setApp(snap.appLabel)
        val shown = safety.filterScreen(snap)
        val refused = shown !== snap
        return Out(
            // l10n:keep agent tool result
            if (refused) "没有读取这个屏幕的内容。" else "",
            error = refused,
            screen = shown,
            app = snap.appLabel ?: snap.packageName,
            summary = "$summary ${snap.id}",
            decision = if (refused) "refused:read" else "ok",
        )
    }

    private fun collect(a: Args): Out {
        val sid = a.str("screen")
        val which =
            when (val r = routeScreenId(sid)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
        // l10n:keep agent tool result
        val list = a.int("list") ?: return Out("collect 需要 list（列表编号）。", error = true)
        val pages = (a.int("max_pages") ?: 10).coerceIn(1, 30)
        val summary =
            ui.get(
                "phone_log_collect",
                "收集 %1\$s#%2\$d",
                sid.toString(),
                list,
            ) // l10n:keep test default
        val driver =
            when (val d = driverFor(which)) {
                is Either.Left ->
                    return Out(d.value, error = true, summary = summary, decision = "unavailable")
                is Either.Right -> d.value
            }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        val pkg = driver.foregroundPackage()
        safety.checkRead(pkg, null)?.let {
            return Out(
                it.message,
                error = true,
                app = pkg,
                summary = summary,
                decision = "refused:${it.rule}",
            )
        }
        say(ui.get("phone_reading_list", "DSH 正在滚动读取列表")) // l10n:keep test default
        val r = driver.collect(sid!!, list, pages, a.str("until"))
        val screen = r.screen?.let { safety.filterScreen(it) }
        val text = buildString {
            // l10n:keep agent tool result
            append("收集到 ${r.items.size} 条（翻了 ${r.pages} 页，停下原因：${r.stopReason}）：\n")
            var size = 0
            for ((i, item) in r.items.withIndex()) {
                val line = "${i + 1}. $item\n"
                size += line.length
                if (size > MAX_COLLECT_CHARS) {
                    // l10n:keep agent tool result
                    append("…其余 ${r.items.size - i} 条超出长度上限，没有列出\n")
                    break
                }
                append(line)
            }
        }
            .trimEnd()
        return Out(
            text,
            screen = screen,
            app = screen?.appLabel ?: pkg,
            summary = summary,
            decision = "ok",
        )
    }

    /** tap / type / scroll / tap_xy: route, resolve, gate, perform. */
    private fun key(a: Args): Out {
        val key =
            when (a.str("key")?.lowercase()) {
                "back" -> PhoneAction.GlobalKey.BACK
                "home" -> PhoneAction.GlobalKey.HOME
                "recents" -> PhoneAction.GlobalKey.RECENTS
                "notifications" -> PhoneAction.GlobalKey.NOTIFICATIONS
                // l10n:keep agent tool result
                else -> return Out("key 只能是 back / home / recents / notifications。", error = true)
            }
        val sid = a.str("screen")
        val which =
            if (sid == null) {
                PhoneScreen.MAIN
            } else {
                when (val r = routeScreenId(sid)) {
                    is Either.Left -> return Out(r.value, error = true)
                    is Either.Right -> r.value
                }
            }
        val summary =
            ui.get("phone_log_key", "按键 %1\$s", key.name.lowercase()) // l10n:keep test default
        val driver =
            when (val d = driverFor(which)) {
                is Either.Left ->
                    return Out(d.value, error = true, summary = summary, decision = "unavailable")
                is Either.Right -> d.value
            }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        say(
            ui.get(
                "phone_status_press",
                "DSH 正在操作 · 按%1\$s",
                when (key) { // l10n:keep test default
                    PhoneAction.GlobalKey.BACK ->
                        ui.get("phone_key_back", "返回") // l10n:keep test default
                    PhoneAction.GlobalKey.HOME ->
                        ui.get("phone_key_home", "桌面") // l10n:keep test default
                    PhoneAction.GlobalKey.RECENTS ->
                        ui.get("phone_key_recents", "最近任务") // l10n:keep test default
                    PhoneAction.GlobalKey.NOTIFICATIONS ->
                        ui.get("phone_key_notifications", "通知栏") // l10n:keep test default
                },
            ),
        )
        val res = driver.perform(PhoneAction.Key(sid ?: "${which.prefix}0", key))
        return Out(
            res.message,
            error = !res.ok,
            screen = res.screen?.let { safety.filterScreen(it) },
            summary = summary,
            decision = if (res.ok) "ok" else "failed",
        )
    }

    private fun handoff(a: Args, scope: SerialWorker.Scope): Out {
        val owner = tasks.activeOwner() ?: return stoppedOut("handoff")
        // l10n:keep agent tool result
        val reason = a.str("reason") ?: return Out("handoff 需要 reason：一句话告诉用户要做什么。", error = true)
        val gate =
            env.gate()
                ?: return Out(
                    // l10n:keep agent tool result
                    "现在没办法把手机交给用户（DSH 不能发通知，悬浮条也没开）。直接在回复里告诉用户要做什么。",
                    error = true,
                    summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
                    decision = "refused:no-gate",
                )
        val cause =
            refusal
                ?.takeIf { (who, _) -> tasks.isCurrent(who) && who.taskId == owner.taskId }
                ?.second
        refusal = null
        val note = env.beforeHandoff(lastScreen)
        if (!tasks.isCurrent(owner)) return stoppedOut("handoff")
        val result = scope.userWait { gate.handoff(reason, HANDOFF_MS, cause) }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted)
            return stoppedOut("handoff")
        val prefix = note?.let { "$it\n" } ?: ""
        return when (result) {
            HandoffResult.CONTINUE -> {
                lastScreen = PhoneScreen.MAIN
                Out(
                    prefix + "用户点了「继续」。下面是现在的屏幕；接着做之前先确认状态。",
                    screen = mainScreenAfter(0),
                    summary = ui.get("phone_log_handoff", "交还用户"),
                    decision = "continue",
                ) // l10n:keep test default
            }
            HandoffResult.STOP -> {
                // l10n:keep agent tool result
                tasks.stopIfCurrent(owner, "用户在交还时选择了停止")
                Out(
                    prefix + PhoneTasks.STOPPED_MESSAGE + "。",
                    error = true,
                    summary = ui.get("phone_log_handoff", "交还用户"),
                    decision = "stopped",
                ) // l10n:keep test default
            }
            HandoffResult.TIMEOUT ->
                Out(
                    // l10n:keep agent tool result
                    prefix + "等了 10 分钟用户没有回应。停下来，告诉用户回来后可以接着做。",
                    error = true,
                    summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
                    decision = "timeout",
                )
        }
    }

    internal fun stoppedOut(summary: String) =
        Out(
            PhoneTasks.STOPPED_MESSAGE + "。",
            error = true,
            summary = summary,
            decision = "stopped",
        )

    companion object {
        /** Under DSH's 660 s MCP timeout, so the bridge always answers first. */
        const val MAX_TOTAL_MS = 640_000L
        const val PAUSE_WAIT_MS = 600_000L
        const val HANDOFF_MS = 600_000L

        /** Tools whose success means the agent got past whatever refused it before. */
        private val WRITES =
            setOf(
                PhoneToolCatalog.TAP,
                PhoneToolCatalog.TYPE,
                PhoneToolCatalog.TAP_XY,
                PhoneToolCatalog.OPEN,
            )
        private val DONE = setOf("ok", "allowed", "confirmed", "confirmed-app", "confirmed-task")
        const val SETTLE_AFTER_LAUNCH_MS = 1200L
        const val MAX_APPS = 150
        const val MAX_COLLECT_CHARS = 16_000

        /** The call's own time, not counting waits for the person. */
        fun budgetMs(tool: String): Long =
            when (tool) {
                PhoneToolCatalog.INSTALL_APK -> 120_000L
                PhoneToolCatalog.APPS -> 15_000L
                PhoneToolCatalog.NOTIFY,
                PhoneToolCatalog.CLIPBOARD -> 10_000L
                PhoneToolCatalog.OPEN,
                PhoneToolCatalog.INTENT -> 25_000L
                PhoneToolCatalog.SCREEN -> 12_000L
                PhoneToolCatalog.COLLECT -> 120_000L
                PhoneToolCatalog.HANDOFF -> 30_000L
                else -> 30_000L
            }
    }
}
