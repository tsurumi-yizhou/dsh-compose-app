package dev.fughetta.dshcompose.phone

import dev.fughetta.dshcompose.domain.phone.PhoneAction
import dev.fughetta.dshcompose.domain.phone.SafetyRules
import dev.fughetta.dshcompose.domain.phone.TargetInfo
import dev.fughetta.dshcompose.domain.phone.WriteKind
import dev.fughetta.dshcompose.phone.PhoneCore.Out

internal fun PhoneCore.act(name: String, a: Args, scope: SerialWorker.Scope): Out {
    val screenParameter = if (name == PhoneToolCatalog.TAP_XY) "observation" else "screen"
    val sid = a.str(screenParameter)
    val which =
        when (val r = routeScreenId(sid, screenParameter)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
    sid!!
    val id = a.int("id")
    val action: PhoneAction
    val kind: WriteKind
    var typed: String? = null
    var submit = false
    var point: Pair<Int, Int>? = null
    var summary: String
    when (name) {
        PhoneToolCatalog.TAP -> {
            // l10n:keep agent tool result
            id ?: return Out("tap 需要 id（元素编号）。", error = true)
            val long = a.bool("long") == true
            action = PhoneAction.Tap(sid, id, long)
            kind = if (long) WriteKind.LONG_TAP else WriteKind.TAP
            summary =
                if (long) ui.get("phone_log_long_tap", "长按 %1\$s#%2\$d", sid, id)
                else
                    ui.get(
                        "phone_log_tap",
                        "点 %1\$s#%2\$d",
                        sid,
                        id,
                    ) // l10n:keep test default
        }
        PhoneToolCatalog.TYPE -> {
            // l10n:keep agent tool result
            id ?: return Out("type 需要 id（输入框编号）。", error = true)
            // l10n:keep agent tool result
            typed = a.text("text") ?: return Out("type 需要 text。", error = true)
            submit = a.bool("submit") == true
            action = PhoneAction.Type(sid, id, typed, submit)
            kind = WriteKind.TYPE
            // Masked until the input is known not to be a password or code field.
            summary = secretSummary(sid, id, submit)
        }
        PhoneToolCatalog.SCROLL -> {
            val dir =
                when (a.str("direction")?.lowercase()) {
                    "up" -> PhoneAction.Direction.UP
                    "down" -> PhoneAction.Direction.DOWN
                    "left" -> PhoneAction.Direction.LEFT
                    "right" -> PhoneAction.Direction.RIGHT
                    // l10n:keep agent tool result
                    else ->
                        return Out(
                            "scroll 需要 direction：up / down / left / right。",
                            error = true,
                        )
                }
            action = PhoneAction.Scroll(sid, id, dir)
            kind = WriteKind.SCROLL
            summary =
                ui.get(
                    "phone_log_scroll",
                    "滚动 %1\$s %2\$s",
                    sid,
                    dir.name.lowercase(),
                ) // l10n:keep test default
        }
        else -> {
            val x = a.int("x")
            val y = a.int("y")
            // l10n:keep agent tool result
            if (x == null || y == null || x < 0 || y < 0)
                return Out("tap_xy 需要非负整数 x 和 y。", error = true)
            point = x to y
            action = PhoneAction.TapXY(sid, x, y)
            kind = WriteKind.TAP_XY
            summary =
                ui.get(
                    "phone_log_tap_xy",
                    "点坐标 %1\$s (%2\$d,%3\$d)",
                    sid,
                    x,
                    y,
                ) // l10n:keep test default
        }
    }
    val driver =
        when (val d = driverFor(which)) {
            is Either.Left ->
                return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
    if (env.isLocked()) return locked(summary)
    lastScreen = which
    val target: TargetInfo? =
        if (id != null && kind != WriteKind.TAP_XY) {
            driver.target(sid, id) ?: return stale(driver, sid, summary)
        } else {
            null
        }
    val secret = kind == WriteKind.TYPE && SafetyRules.isSecretTarget(target)
    if (kind == WriteKind.TYPE && !secret) {
        summary =
            ui.get(
                "phone_log_type",
                "输入 %1\$s#%2\$d（%3\$d 字%4\$s）",
                sid,
                id!!,
                typed!!.length,
                if (submit) ui.get("phone_log_submit", "，提交") else "",
            ) // l10n:keep test default
    }
    val pkg = target?.packageName ?: driver.foregroundPackage()
    val appLabel = target?.appLabel
    val app = appLabel ?: pkg
    val owner = tasks.activeOwner() ?: return stoppedOut(summary)
    val verdict = safety.checkWrite(kind, target, pkg, appLabel, typed, submit, point)
    val decision =
        when (val r = safety.resolve(verdict, scope)) {
            is SafetyGate.Result.No ->
                return Out(
                    r.message,
                    error = true,
                    app = app,
                    summary = summary,
                    decision = r.decision,
                )
            is SafetyGate.Result.Go -> r.decision
        }
    if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut(summary)
    tasks.setApp(appLabel ?: pkg)
    say(
        ui.get(
            "phone_acting",
            "DSH 正在操作 · %1\$s",
            statusLine(kind, target, if (secret) SafetyRules.MASK else typed),
        ),
    ) // l10n:keep test default
    val res = driver.perform(action)
    return Out(
        res.message,
        error = !res.ok,
        screen = res.screen?.let { safety.filterScreen(it) },
        app = app,
        summary = summary,
        decision = if (res.ok) decision else "failed",
    )
}

/** A `type` in the log without its length or text: 「输入 s3#5（••••）」. */
internal fun PhoneCore.secretSummary(sid: String, id: Int, submit: Boolean): String =
    ui.get(
        "phone_log_type_secret",
        "输入 %1\$s#%2\$d（%3\$s%4\$s）",
        sid,
        id,
        SafetyRules.MASK,
        if (submit) ui.get("phone_log_submit", "，提交") else "",
    ) // l10n:keep test default

internal fun PhoneCore.statusLine(kind: WriteKind, t: TargetInfo?, typed: String?): String {
    val label =
        t?.label?.takeIf { it.isNotBlank() }?.let { SafetyRules.quoteText(it, 16) }
            ?: t?.role?.let(ui::role)
            ?: ""
    return when (kind) {
        WriteKind.TAP -> ui.get("phone_status_tap", "点「%1\$s」", label) // l10n:keep test default
        WriteKind.LONG_TAP ->
            ui.get("phone_status_long_tap", "长按「%1\$s」", label) // l10n:keep test default
        WriteKind.TYPE ->
            ui.get(
                "phone_status_type",
                "输入「%1\$s」",
                if (typed == SafetyRules.MASK) typed else SafetyRules.quoteText(typed ?: "", 16),
            ) // l10n:keep test default
        WriteKind.SCROLL -> ui.get("phone_status_scroll", "滚动") // l10n:keep test default
        WriteKind.TAP_XY -> ui.get("phone_status_tap_xy", "点截图上的位置") // l10n:keep test default
        WriteKind.KEY -> ui.get("phone_status_key", "按键") // l10n:keep test default
    }
}

/** The element no longer matches its snapshot: refuse and show the screen as it is now. */
internal fun PhoneCore.stale(driver: PhoneDriver, sid: String, summary: String): Out {
    val now =
        try {
            safety.filterScreen(driver.observe(detail = false, image = false))
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }
    return Out(
        // l10n:keep agent tool result
        "屏幕 $sid 已经过期，或者这个元素变了，没有执行。按下面的新屏幕重新选。",
        error = true,
        screen = now,
        summary = summary,
        decision = "stale",
    )
}
