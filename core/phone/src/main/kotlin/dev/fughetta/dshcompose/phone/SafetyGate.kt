package dev.fughetta.dshcompose.phone

import dev.fughetta.dshcompose.domain.phone.ConfirmMode
import dev.fughetta.dshcompose.domain.phone.ConfirmRequest
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.domain.phone.PhonePolicies
import dev.fughetta.dshcompose.domain.phone.SafetyRules
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import dev.fughetta.dshcompose.domain.phone.TargetInfo
import dev.fughetta.dshcompose.domain.phone.WriteKind

/**
 * Applies the safety rules to one action and asks the person when a rule says so, through [gate]
 * (`PhoneRegistry.gate()`: the overlay, else the heads-up notification). Plain Kotlin; the calling
 * worker passes its [SerialWorker.Scope] so waiting for the person isn't charged to the action's
 * time budget.
 */
class SafetyGate(
    private val policies: () -> PhonePolicies,
    private val tasks: PhoneTasks,
    private val gate: () -> UserGate?,
    private val ownPackage: String = SafetyRules.OWN_PACKAGE,
    private val uiText: PhoneUiText = PhoneUiText.DEFAULT,
) {
    sealed interface Verdict {
        object Allow : Verdict

        class Refuse(val message: String, val rule: String) : Verdict

        class Confirm(val request: ConfirmRequest, val packageName: String?, val reason: String) :
            Verdict
    }

    /** The outcome of asking (or of not needing to ask). */
    sealed interface Result {
        /** [decision] null when no question was needed. */
        class Go(val decision: String) : Result

        class No(val message: String, val decision: String) : Result
    }

    // l10n:keep agent safety result
    fun labelOf(pkg: String?, appLabel: String?) = appLabel ?: pkg ?: "当前应用"

    /** Reads (screen, collect, the screen attached to results): own app and deny list refuse. */
    fun checkRead(pkg: String?, appLabel: String?): Verdict.Refuse? {
        val p = policies()
        if (pkg == ownPackage && !p.unlocks.ownApp) return Verdict.Refuse(OWN_APP, "own-app")
        if (pkg != null && pkg in p.denyPackages)
            return Verdict.Refuse(denied(labelOf(pkg, appLabel)), "deny-list")
        return null
    }

    /** Opening an app (main or background screen). */
    fun checkOpen(pkg: String, appLabel: String?): Verdict.Refuse? {
        val p = policies()
        // l10n:keep agent safety result
        if (pkg == ownPackage && !p.unlocks.ownApp)
            return Verdict.Refuse("DSH 自己不需要用 open 打开。", "own-app")
        if (pkg in p.denyPackages)
            return Verdict.Refuse(denied(labelOf(pkg, appLabel)), "deny-list")
        return null
    }

    /**
     * One write on a screen. [target] is the resolved element (null for tap_xy / key / scroll
     * without an element); [pkg] is the app in front of that screen.
     */
    fun checkWrite(
        kind: WriteKind,
        target: TargetInfo?,
        pkg: String?,
        appLabel: String?,
        typed: String? = null,
        submit: Boolean = false,
        point: Pair<Int, Int>? = null,
    ): Verdict {
        val app = labelOf(pkg, appLabel)
        if (kind == WriteKind.KEY)
            return Verdict.Allow // back / home / recents / shade: always a way out
        val p = policies()
        val lifted = p.unlocks
        if (pkg == ownPackage && !lifted.ownApp) return Verdict.Refuse(OWN_APP, "own-app")
        if (pkg != null && pkg in p.denyPackages) return Verdict.Refuse(denied(app), "deny-list")
        if (kind == WriteKind.SCROLL) return Verdict.Allow
        if (SafetyRules.isSystemPackage(pkg) && !lifted.systemPages) {
            // l10n:keep agent safety result
            return Verdict.Refuse("系统设置 / 权限 / 安装页（「$app」）只看不动。请 handoff 让用户自己完成。", "system-page")
        }
        val secret = SafetyRules.isSecretTarget(target)
        if (secret && !lifted.secretFields) {
            // l10n:keep agent safety result
            return Verdict.Refuse("密码 / 验证码 / 支付框不代填也不代点。请 handoff 让用户输入。", "secret-field")
        }
        // Everything above is a hard rule (or one the person lifted); everything below only asks.
        if (waived(p)) return Verdict.Allow
        val everyWrite = pkg != null && pkg in p.confirmEveryWritePackages
        val firstInApp = !everyWrite && (pkg == null || !tasks.isApproved(pkg))
        val word =
            if (kind == WriteKind.TAP || kind == WriteKind.LONG_TAP)
                SafetyRules.sensitiveWord(target?.label, target?.viewId)
            else null
        val actionApp =
            appLabel ?: pkg ?: uiText.get("phone_current_app", "当前应用") // l10n:keep test default
        val action =
            describe(
                kind,
                target,
                actionApp,
                if (secret) SafetyRules.MASK else typed,
                submit,
                point,
            )
        val reason =
            when {
                kind == WriteKind.TAP_XY -> "tap_xy"
                kind == WriteKind.TYPE && submit -> "submit"
                word != null -> "sensitive:$word"
                everyWrite -> "every-write"
                firstInApp -> "first-in-app"
                else -> return Verdict.Allow
            }
        val sentence =
            if (reason == "first-in-app")
                uiText.get(
                    "phone_confirm_first_app",
                    "允许 DSH 在本任务中操作「%1\$s」？第一步：%2\$s",
                    actionApp,
                    action,
                )
            else action // l10n:keep test default
        return Verdict.Confirm(
            ConfirmRequest(
                sentence = sentence,
                appLabel = appLabel ?: pkg,
                packageName = pkg,
                firstInApp = firstInApp,
                offerTaskWaiver = p.confirmMode == ConfirmMode.TASK,
            ),
            pkg,
            reason,
        )
    }

    /** Nothing more to ask: 始终免确认, or 本任务免确认 already granted for this task. */
    fun waived(p: PhonePolicies = policies()): Boolean =
        p.confirmMode == ConfirmMode.ALWAYS || (p.confirmMode == ConfirmMode.TASK && tasks.isWaived)

    /**
     * A plain confirmation the core needs outside [checkWrite] (moving an app to the background
     * screen).
     */
    fun confirmation(sentence: String, pkg: String?, appLabel: String?, reason: String): Verdict {
        val p = policies()
        if (waived(p)) return Verdict.Allow
        val request =
            ConfirmRequest(
                sentence = sentence,
                appLabel = appLabel ?: pkg,
                packageName = pkg,
                firstInApp = false,
                offerTaskWaiver = p.confirmMode == ConfirmMode.TASK,
            )
        return Verdict.Confirm(request, pkg, reason)
    }

    /** Resolves a verdict, asking the person if needed. InterruptedException means stop. */
    fun resolve(verdict: Verdict, scope: SerialWorker.Scope): Result =
        when (verdict) {
            Verdict.Allow -> Result.Go("allowed")
            is Verdict.Refuse -> Result.No(verdict.message, "refused:${verdict.rule}")
            is Verdict.Confirm -> ask(verdict, scope)
        }

    private fun ask(v: Verdict.Confirm, scope: SerialWorker.Scope): Result {
        val g =
            gate()
                ?: return Result.No(
                    // l10n:keep agent safety result
                    "这一步需要用户确认，但现在没办法问用户：请用户允许 DSH 发通知，或开启 DSH 的无障碍（悬浮条确认）。",
                    "refused:no-gate",
                )
        val owner = tasks.activeOwner() ?: return stopped()
        val decision = scope.userWait { g.confirm(v.request) }
        // A real tap is not authority over a task that replaced the one being asked about.
        if (!tasks.isCurrent(owner)) return stopped()
        return when (decision) {
            Decision.ALLOW_ONCE ->
                if (tasks.confirmIfCurrent(owner)) Result.Go("confirmed") else stopped()
            Decision.ALLOW_APP_FOR_TASK -> {
                val pkg = v.packageName
                val approved = pkg?.takeUnless { it in policies().confirmEveryWritePackages }
                if (tasks.confirmIfCurrent(owner, pkg = approved)) Result.Go("confirmed-app")
                else stopped()
            }
            Decision.ALLOW_ALL_FOR_TASK -> {
                if (tasks.confirmIfCurrent(owner, waiveTask = true)) Result.Go("confirmed-task")
                else stopped()
            }
            // l10n:keep agent safety result
            Decision.DENY -> Result.No("用户拒绝了这一步（或 30 秒内没有选择）。不要重试同一个动作；换个做法，或问用户想怎么做。", "denied")
            Decision.STOP -> {
                // l10n:keep agent safety result
                tasks.stopIfCurrent(owner, "用户在确认时选择了停止")
                Result.No(PhoneTasks.STOPPED_MESSAGE + "。", "stopped")
            }
            Decision.UNAVAILABLE ->
                Result.No(
                    // l10n:keep agent safety result
                    "这一步需要用户确认，但确认界面现在不可用。请用户打开 DSH Compose 检查通知与手机操控设置；不要自动重试操作。",
                    "refused:no-gate",
                )
        }
    }

    private fun stopped() = Result.No(PhoneTasks.STOPPED_MESSAGE + "。", "stopped")

    /**
     * The screen the model gets back: replaced by a one-line refusal for DSH itself, deny-listed
     * apps and secure windows. Images go with the text they belong to; a screen with a bank card or
     * ID number (masked in the text) keeps its text but loses the image.
     */
    fun filterScreen(s: ScreenSnapshot): ScreenSnapshot {
        val p = policies()
        val refusal =
            when {
                s.packageName == ownPackage && !p.unlocks.ownApp -> OWN_APP
                s.packageName != null && s.packageName in p.denyPackages ->
                    denied(labelOf(s.packageName, s.appLabel))
                // l10n:keep agent safety result
                s.secure -> "安全窗口（禁止截屏 / 读取）。要操作请 handoff。"
                // l10n:keep agent safety result
                s.image != null && ("«银行卡" in s.text || "«身份证" in s.text) ->
                    // l10n:keep agent safety result
                    return s.copy(
                        text = s.text + "\n提示：本屏有银行卡号或身份证号，这次没有附截图。",
                        image = null,
                        imageMime = null,
                    )
                else -> return s
            }
        val app = s.appLabel?.let { " · $it" } ?: ""
        // l10n:keep agent safety result
        return s.copy(text = "屏幕 ${s.id}$app\n$refusal", image = null, imageMime = null)
    }

    private fun describe(
        kind: WriteKind,
        t: TargetInfo?,
        app: String,
        typed: String?,
        submit: Boolean,
        point: Pair<Int, Int>?,
    ): String {
        val label =
            t?.label?.takeIf { it.isNotBlank() }?.let { SafetyRules.quoteText(it, 30) }
                ?: t?.role?.let(uiText::role)
                ?: uiText.get("phone_confirm_element", "元素") // l10n:keep test default
        return when (kind) {
            WriteKind.TAP ->
                uiText.get(
                    "phone_confirm_tap",
                    "在「%1\$s」点「%2\$s」",
                    app,
                    label,
                ) // l10n:keep test default
            WriteKind.LONG_TAP ->
                uiText.get(
                    "phone_confirm_long_tap",
                    "在「%1\$s」长按「%2\$s」",
                    app,
                    label,
                ) // l10n:keep test default
            WriteKind.TYPE ->
                uiText.get(
                    "phone_confirm_type",
                    "在「%1\$s」的「%2\$s」输入：\"%3\$s\"",
                    app,
                    label,
                    if (typed == SafetyRules.MASK) typed else SafetyRules.quoteText(typed ?: ""),
                ) +
                    if (submit) uiText.get("phone_confirm_submit", "，然后发送 / 提交")
                    else "" // l10n:keep test default
            WriteKind.TAP_XY ->
                uiText.get(
                    "phone_confirm_tap_xy",
                    "在「%1\$s」点截图上的位置 (%2\$s, %3\$s)",
                    app,
                    point?.first.toString(),
                    point?.second.toString(),
                ) // l10n:keep test default
            WriteKind.SCROLL ->
                uiText.get("phone_confirm_scroll", "在「%1\$s」滚动", app) // l10n:keep test default
            WriteKind.KEY -> uiText.get("phone_confirm_key", "按系统键") // l10n:keep test default
        }
    }

    companion object {
        // l10n:keep agent safety result
        const val OWN_APP = "这是 DSH 自己的界面，不读取也不操作。"

        fun denied(app: String) =
            // l10n:keep agent safety result
            "「$app」默认不碰（银行、券商、支付类）。要用就 handoff 给用户，或让用户在 设置 → 手机操控 → 应用名单 里解除。"
    }
}
