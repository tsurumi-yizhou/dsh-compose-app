// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: the screen text format instead of JSON; fingerprints re-checked against a fresh
// capture; password fields marked rather than failing the screen; collect, open, numbered
// screenshots, tap_xy; blocking calls with time budgets.

package dev.fughetta.dshcompose.phone.a11y

import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dev.fughetta.dshcompose.domain.phone.ActionResult
import dev.fughetta.dshcompose.domain.phone.PhoneScreen
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Companion.CAPTURE_MS
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Companion.TAG
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Companion.counter
import dev.fughetta.dshcompose.phone.a11y.MainScreenDriver.Snapshot

internal fun MainScreenDriver.after(
    before: Long,
    previous: Snapshot?,
    gen: Long,
    message: String,
    scrolled: Boolean = false,
): ActionResult {
    clock.settle(before, 1_500, 300, 3_000) { cancelled(gen) }
    val cap =
        try {
            settledCapture(previous, gen)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "observation after an accepted action failed", e)
            // l10n:keep agent screen result
            return ActionResult(true, "$message（动作已提交，但没能读到新屏幕，无法验证结果。）")
        }
    try {
        val snap = publish(cap, false)
        val old = previous?.observation
        val observation = snap.observation
        val comparable = old != null && observation.comparableTo(old)
        val changed = old != null && observation.changedFrom(old)
        val keyboard = old?.let { observation.keyboardChange(it) }
        // Missing content still needs an explicit warning, even when app/window/IME changed.
        val note =
            ScreenVerdict.note(
                comparable = comparable,
                changed = changed,
                keyboard = keyboard,
                scrolled = scrolled,
            )
        val canReuse =
            comparable &&
                observation.sameContext(old) &&
                observation.keyboardShown == old.keyboardShown
        val text =
            ScreenDigest.text(
                previous?.id,
                previous?.screen,
                snap.id,
                snap.screen,
                canReuse = canReuse,
                observationChanged = changed,
            )
        return ActionResult(true, message + note, toSnapshot(snap, text))
    } finally {
        cap.recycle()
    }
}

/**
 * A capture after an action. Right after a transition the new window may have no root yet or an
 * empty tree (Chrome, Settings sub-pages): retry a few times while that lasts.
 */
internal fun MainScreenDriver.settledCapture(previous: Snapshot?, gen: Long): Capture {
    var cap = capture()
    var tries = 0
    while (tries++ < 4 && !cancelled(gen) && looksUnfinished(cap, previous)) {
        cap.recycle()
        clock.pause(400) { cancelled(gen) }
        cap = capture()
    }
    return cap
}

internal fun MainScreenDriver.looksUnfinished(cap: Capture, previous: Snapshot?): Boolean =
    cap.hiddenWindowTitle == null &&
        (cap.windows.isEmpty() ||
            (ScreenFormat.textCount(cap.windows) < ScreenFormat.POOR_TREE &&
                previous?.screen?.poorTree != true))

internal fun MainScreenDriver.settledSnapshot(before: Long, gen: Long): ScreenSnapshot? {
    clock.settle(before, 800, 250, 2_000) { cancelled(gen) }
    return observeQuiet()
}

/** Polls the field until it shows [expected] (or 1.5 s pass); returns what it shows. */
internal fun MainScreenDriver.readBack(
    node: AccessibilityNodeInfo,
    expected: String,
    gen: Long,
): String? {
    val deadline = SystemClock.uptimeMillis() + 1_500
    var got: String? = null
    while (true) {
        node.refresh()
        got = if (node.isShowingHintTextCompat()) "" else node.text?.toString()
        if (sameText(got, expected) || SystemClock.uptimeMillis() > deadline || cancelled(gen))
            return got
        clock.pause(100) { cancelled(gen) }
    }
}

private fun AccessibilityNodeInfo.isShowingHintTextCompat() =
    android.os.Build.VERSION.SDK_INT >= 26 && isShowingHintText

/** Equal, or equal once the field's own formatting (spaces, dashes) is ignored. */
internal fun MainScreenDriver.sameText(got: String?, expected: String): Boolean {
    if (got == expected) return true
    if (got == null) return false
    fun norm(s: String) = s.filterNot { it.isWhitespace() || it == '-' || it == '​' }
    return norm(got) == norm(expected)
}

internal fun MainScreenDriver.awaitInputIdle(): Boolean {
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (overlay.editing) {
        if (SystemClock.uptimeMillis() > deadline) return false
        clock.pause(150)
    }
    return true
}

// ---- snapshots
// --------------------------------------------------------------------------------

internal fun MainScreenDriver.capture(): Capture =
    TreeCapture.capture(service, screenBox(), ownPackage, CAPTURE_MS)

internal fun MainScreenDriver.unavailable(cap: Capture) =
    WindowContent.unavailable(
        cap.hiddenWindowTitle != null,
        cap.unreadableWindowTitle != null,
        cap.windows,
    )

internal fun MainScreenDriver.observation(cap: Capture) =
    ScreenObservation(
        cap.packageName,
        cap.windows,
        cap.screen,
        cap.keyboardShown,
        unavailable(cap),
        cap.truncated || cap.partial,
        ScreenFormat.observationKeys(cap.windows),
    )

internal fun MainScreenDriver.locate(cap: Capture, el: ScreenElement): IndexedElement? =
    ScreenFormat.index(cap.windows).firstOrNull { it.fingerprint == el.fingerprint }

/** Formats [cap] as the next snapshot (`s<n>`) and makes it the latest. */
internal fun MainScreenDriver.publish(cap: Capture, detail: Boolean): Snapshot {
    val id = "${PhoneScreen.MAIN.prefix}${counter.incrementAndGet()}"
    val pkg = cap.packageName
    val appLabel = label(pkg)
    val titles =
        cap.windows.mapNotNull { it.title } +
            ProtectedPages.titleTexts(cap.windows.firstOrNull()?.root, cap.screen.height)
    val protectedPage = ProtectedPages.isProtected(pkg, titles)
    // A note already shown on the previous screen of this app is not repeated: the model
    // still has it, and a warning on every screen reads as nagging.
    val seen = if (latest?.packageName == pkg) latest?.noteKeys ?: emptySet() else emptySet()
    val notes = mutableListOf<Pair<String, String>>()
    fun note(key: String, text: String) {
        if (key !in seen) notes += key to text
    }
    val lifted = lifted
    // l10n:keep agent screen result
    if (cap.ownAppInFront && !lifted.ownApp) note("ownApp", "提示：这是 DSH 自己的界面，DSH 不操作它。")
    // l10n:keep agent screen result
    if (protectedPage && !lifted.systemPages)
        note("protected", "提示：这是系统权限 / 安全相关页面，DSH 只看不操作；需要的话用 handoff 交给用户。")
    // l10n:keep agent screen result
    if (cap.truncated) note("truncated", "提示：页面元素太多，只读了一部分。")
    // l10n:keep agent screen result
    if (cap.partial) note("partial", "提示：部分窗口读不到，当前观测不完整。")
    if (cap.hiddenWindowTitle != null) {
        val name = cap.hiddenWindowTitle.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
        // l10n:keep agent screen result
        note("hidden", "提示：当前无法读取前台页面${name}（可能受敏感视图策略限制）。读不到也点不了，需要就 handoff。")
        // l10n:keep agent screen result
    } else if (cap.unreadableWindowTitle != null) {
        val name = cap.unreadableWindowTitle.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
        // l10n:keep agent screen result
        note(
            "unreadable",
            "提示：当前无法读取这一屏${name}的有效内容，可能受敏感视图策略限制（Android 14 起密码、支付、验证页会被系统过滤）。读不到也点不了；能看图的模型可以 screen 加 image，否则请 handoff。",
        )
        // l10n:keep agent screen result
    } else if (cap.windows.isEmpty()) note("empty", "提示：读不到前台界面（可能是锁屏、安全页面或系统界面）。")
    val previous = latest?.takeIf { it.packageName == pkg }?.screen?.keys
    fun format(notes: List<Pair<String, String>>) =
        ScreenFormat.format(
            ScreenInput(
                screenId = id,
                appLabel = appLabel,
                packageName = pkg,
                screenWidth = cap.screen.width,
                screenHeight = cap.screen.height,
                keyboardShown = cap.keyboardShown,
                windows = cap.windows,
                detail = detail,
                previousKeys = previous,
                notes = notes.map { it.second },
                unreadable = cap.unreadableWindowTitle != null,
            ),
        )
    var formatted = format(notes)
    var noteKeys = notes.map { it.first }.toSet()
    // Such inputs still read 「不可代填」 (their fingerprints must not change with the setting):
    // say once, on the screens that have one, that the person allowed filling them.
    if (
        lifted.secretFields &&
            "secretFields" !in seen &&
            formatted.elements.any { it.password || it.secret }
    ) {
        // l10n:keep agent screen result
        notes += "secretFields" to "提示：用户已允许 DSH 填写密码和验证码，标着「不可代填」的输入框也可以输入。"
        noteKeys = noteKeys + "secretFields"
        formatted = format(notes)
    }
    val snap =
        Snapshot(
            id,
            formatted,
            pkg,
            appLabel,
            protectedPage,
            cap.ownAppInFront,
            cap.hiddenWindowTitle != null,
            cap.unreadableWindowTitle != null,
            observation(cap),
            noteKeys,
        )
    latest = snap
    return snap
}

internal fun MainScreenDriver.toSnapshot(s: Snapshot, text: String = s.screen.text) =
    ScreenSnapshot(
        s.id,
        text,
        s.appLabel,
        s.packageName,
        secure = s.hidden,
        poorTree = s.screen.poorTree,
    )

/** A fresh text snapshot for an action result; null when the screen can't be read now. */
internal fun MainScreenDriver.observeQuiet(): ScreenSnapshot? {
    if (!availability().ok) return null
    return try {
        val cap = capture()
        try {
            toSnapshot(publish(cap, false))
        } finally {
            cap.recycle()
        }
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "observe failed", e)
        null
    }
}

// l10n:keep agent screen result
internal fun MainScreenDriver.blank(reason: String) =
    ScreenSnapshot("${PhoneScreen.MAIN.prefix}0", "屏幕读不到：$reason", null, null)

internal fun MainScreenDriver.screenBox(): Box {
    val p = Point()
    @Suppress("DEPRECATION") displays.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
    return Box(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
}

internal fun MainScreenDriver.label(pkg: String?): String? {
    if (pkg == null) return null
    labels[pkg]?.let {
        return it
    }
    val name =
        try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            return null
        }
    labels[pkg] = name
    return name
}

internal fun MainScreenDriver.cancelled(gen: Long) =
    abortGen != gen || Thread.currentThread().isInterrupted

internal fun MainScreenDriver.short(s: String, max: Int = 24) = ScreenFormat.clip(s, max)
