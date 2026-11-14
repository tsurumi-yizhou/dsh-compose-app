package dev.fughetta.dshcompose.phone

import dev.fughetta.dshcompose.domain.phone.PhoneScreen
import dev.fughetta.dshcompose.domain.phone.SafetyRules
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import dev.fughetta.dshcompose.phone.PhoneCore.Companion.MAX_APPS
import dev.fughetta.dshcompose.phone.PhoneCore.Companion.SETTLE_AFTER_LAUNCH_MS
import dev.fughetta.dshcompose.phone.PhoneCore.Out

internal fun PhoneCore.apps(a: Args): Out {
    val q = a.str("query")
    val all =
        env.system()
            .apps()
            .filter { it.packageName != SafetyRules.OWN_PACKAGE }
            .sortedBy { it.label }
    val hits = AppMatch.filter(all, q)
    if (hits.isEmpty())
        return Out(
            if (q == null) "没读到应用列表。" else "没有找到名称或包名含「$q」的应用。",
            summary = ui.get("phone_log_apps", "列出应用"),
        ) // l10n:keep test default
    val shown = hits.take(MAX_APPS)
    val text = buildString {
        // l10n:keep agent tool result
        append(if (q == null) "共 ${hits.size} 个应用" else "匹配「$q」的应用 ${hits.size} 个")
            .append("（名称 · 包名）：\n")
        shown.forEach { append(it.label).append(" · ").append(it.packageName).append('\n') }
        // l10n:keep agent tool result
        if (hits.size > shown.size) append("…另有 ${hits.size - shown.size} 个，用 query 缩小范围\n")
    }
        .trimEnd()
    return Out(
        text,
        summary =
            if (q == null) ui.get("phone_log_apps", "列出应用")
            else ui.get("phone_log_apps_filtered", "列出应用（筛选）"),
    ) // l10n:keep test default
}

internal fun PhoneCore.resolveApp(query: String): Either<String, AppEntry> =
    when (val r = AppMatch.resolve(env.system().apps(), query)) {
        is AppMatch.Result.Found -> Either.Right(r.app)
        is AppMatch.Result.Ambiguous ->
            Either.Left(
                // l10n:keep agent tool result
                "「$query」对应多个应用，请用包名指定：" +
                    r.candidates.joinToString("；") { "${it.label} · ${it.packageName}" },
            )
        // l10n:keep agent tool result
        AppMatch.Result.NotFound -> Either.Left("没找到应用「$query」。用 apps 查一下名称或包名。")
    }

internal fun PhoneCore.open(a: Args, scope: SerialWorker.Scope): Out {
    val owner = tasks.activeOwner() ?: return stoppedOut("open")
    val appQ = a.str("app")
    val url = a.str("url")
    val background = a.bool("background") == true
    // l10n:keep agent tool result
    if ((appQ == null) == (url == null)) return Out("open 需要 app 或 url，二选一。", error = true)
    if (env.isLocked()) return locked("open")
    if (url != null) {
        // l10n:keep agent tool result
        if (background) return Out("后台屏只能用 app 打开应用，不支持链接。", error = true)
        UrlPolicy.check(url)?.let {
            return Out(
                it,
                error = true,
                summary = ui.get("phone_log_open_url", "打开链接"),
                decision = "refused:url",
            )
        } // l10n:keep test default
        val target = env.system().urlTarget(url)
        target.error?.let {
            return Out(
                it,
                error = true,
                summary = ui.get("phone_log_open_url", "打开链接"),
                decision = "refused:url",
            )
        } // l10n:keep test default
        target.packageName?.let { pkg ->
            safety.checkOpen(pkg, target.appLabel)?.let {
                return Out(
                    it.message,
                    error = true,
                    app = target.appLabel ?: pkg,
                    summary = ui.get("phone_log_open_url", "打开链接"),
                    decision = "refused:${it.rule}",
                )
            } // l10n:keep test default
        }
        val r = env.system().openUrl(url, target)
        return afterSystem(
            r,
            ui.get("phone_log_open_url_scheme", "打开链接 %1\$s:", url.substringBefore(':')),
            target.appLabel ?: target.packageName,
        ) // l10n:keep test default
    }
    val app =
        when (val r = resolveApp(appQ!!)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
    safety.checkOpen(app.packageName, app.label)?.let {
        return Out(
            it.message,
            error = true,
            app = app.label,
            summary = ui.get("phone_log_open_app", "打开 %1\$s", app.packageName),
            decision = "refused:${it.rule}",
        ) // l10n:keep test default
    }
    if (!background) {
        return afterSystem(
            env.system().launch(app),
            ui.get("phone_log_open_app", "打开 %1\$s", app.packageName),
            app.label,
        ) // l10n:keep test default
    }
    val driver =
        env.driver(PhoneScreen.BACKGROUND)
            ?: return Out(
                env.backgroundUnavailable(),
                error = true,
                app = app.label,
                summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName),
                decision = "unavailable",
            ) // l10n:keep test default
    driver.availability().let {
        if (!it.ok)
            return Out(
                it.reason ?: "后台屏现在不可用。",
                error = true,
                summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName),
                decision = "unavailable",
            )
    } // l10n:keep test default
    val mainFront = runCatching {
        env.driver(PhoneScreen.MAIN)?.foregroundPackage()
    }
        .getOrNull()
    var decision = "allowed"
    if (mainFront == app.packageName) {
        val v =
            safety.confirmation(
                ui.get("phone_move_background", "这会把「%1\$s」从你的屏幕挪到后台屏，让 DSH 在那里操作。", app.label),
                app.packageName,
                app.label,
                "move-to-background",
            ) // l10n:keep test default
        when (val r = safety.resolve(v, scope)) {
            is SafetyGate.Result.No ->
                return Out(
                    r.message,
                    error = true,
                    app = app.label,
                    summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName),
                    decision = r.decision,
                ) // l10n:keep test default
            is SafetyGate.Result.Go -> decision = r.decision
        }
    }
    if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut("open")
    lastScreen = PhoneScreen.BACKGROUND
    say(
        ui.get("phone_opening_background", "DSH 正在后台屏打开「%1\$s」", app.label),
    ) // l10n:keep test default
    val res = driver.open(app.packageName)
    tasks.setApp(app.label)
    return Out(
        res.message,
        error = !res.ok,
        screen = res.screen?.let { safety.filterScreen(it) },
        app = app.label,
        summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName),
        decision = if (res.ok) decision else "failed",
    ) // l10n:keep test default
}

/**
 * A system action that brought up another app's UI: attach the new main screen when we can read it.
 */
internal fun PhoneCore.afterSystem(r: SysResult, summary: String, app: String?): Out {
    if (r.ok) tasks.setApp(r.appLabel ?: app)
    val screen = if (r.ok) mainScreenAfter(SETTLE_AFTER_LAUNCH_MS) else null
    return Out(
        r.message,
        error = !r.ok,
        screen = screen,
        app = r.appLabel ?: app,
        summary = summary,
        decision = if (r.ok) "ok" else "failed",
    )
}

internal fun PhoneCore.mainScreenAfter(settleMs: Long): ScreenSnapshot? {
    val d = env.driver(PhoneScreen.MAIN) ?: return null
    if (!d.availability().ok) return null
    return try {
        env.sleep(settleMs)
        safety.filterScreen(d.observe(detail = false, image = false))
    } catch (e: InterruptedException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

internal fun PhoneCore.intent(a: Args): Out {
    val spec =
        when (val p = IntentSpec.parse(a, env.zone())) {
            is Either.Left -> return Out(p.value, error = true)
            is Either.Right -> p.value
        }
    val kind = a.str("kind") ?: ""
    if (env.isLocked()) return locked("intent $kind")
    var shareTo: AppEntry? = null
    val appQuery =
        when (spec) {
            is IntentSpec.Share -> spec.app
            is IntentSpec.Settings -> spec.app
            else -> null
        }
    if (appQuery != null) {
        shareTo =
            when (val r = resolveApp(appQuery)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
        if (spec is IntentSpec.Share) {
            safety.checkOpen(shareTo.packageName, shareTo.label)?.let {
                return Out(
                    it.message,
                    error = true,
                    app = shareTo.label,
                    summary = "intent $kind",
                    decision = "refused:${it.rule}",
                )
            }
        }
    }
    val r = env.system().intent(spec, shareTo)
    return afterSystem(r, "intent $kind", r.appLabel ?: shareTo?.label)
}

internal fun PhoneCore.notify(a: Args): Out {
    // l10n:keep agent tool result
    val title = a.str("title") ?: return Out("notify 需要 title 和 text。", error = true)
    // l10n:keep agent tool result
    val text = a.str("text") ?: return Out("notify 需要 title 和 text。", error = true)
    val r = env.system().notify(title, text)
    return Out(
        r.message,
        error = !r.ok,
        summary = ui.get("phone_log_notify", "通知"),
        decision = if (r.ok) "ok" else "failed",
    ) // l10n:keep test default
}

internal fun PhoneCore.clipboard(a: Args): Out {
    val set = a.text("set")
    val get = a.bool("get") == true
    // l10n:keep agent tool result
    if (set != null && get) return Out("clipboard 的 set 和 get 一次只能用一个。", error = true)
    if (set != null) {
        val r = env.system().clipboardSet(set)
        return Out(
            r.message,
            error = !r.ok,
            summary = ui.get("phone_log_clipboard_write", "写剪贴板（%1\$d 字）", set.length),
            decision = if (r.ok) "ok" else "failed",
        ) // l10n:keep test default
    }
    // l10n:keep agent tool result
    if (!get) return Out("clipboard 需要 set（写入文字）或 get:true（读取）。", error = true)
    val r = env.system().clipboardGet()
    return Out(
        r.message,
        error = !r.ok,
        summary = ui.get("phone_log_clipboard_read", "读剪贴板"),
        decision = if (r.ok) "ok" else "failed",
    ) // l10n:keep test default
}
