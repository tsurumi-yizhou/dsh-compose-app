// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: the screen text format instead of JSON; fingerprints re-checked against a fresh
// capture; password fields marked rather than failing the screen; collect, open, numbered
// screenshots, tap_xy; blocking calls with time budgets.

package dev.fughetta.dshcompose.phone.a11y

import android.app.KeyguardManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.phone.ActionResult
import dev.fughetta.dshcompose.domain.phone.Availability
import dev.fughetta.dshcompose.domain.phone.CollectResult
import dev.fughetta.dshcompose.domain.phone.PhoneAction
import dev.fughetta.dshcompose.domain.phone.PhoneScreen
import dev.fughetta.dshcompose.domain.phone.PhoneUnlocks
import dev.fughetta.dshcompose.domain.phone.SafetyRules
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import dev.fughetta.dshcompose.domain.phone.TargetInfo
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.PhoneDriver
import dev.fughetta.dshcompose.phone.PhoneRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The main screen, read and driven through [PhoneA11yService]. Calls block (bridge worker thread);
 * observe stays under ~5 s, actions under ~15 s, collect under ~100 s. Only the latest snapshot's
 * ids are valid; every action re-reads the tree and refuses when the element's fingerprint no
 * longer matches.
 */
internal class MainScreenDriver(
    internal val service: PhoneA11yService,
    internal val overlay: ControlOverlay,
) : PhoneDriver {
    override val screen = PhoneScreen.MAIN

    internal val ownPackage: String = service.packageName

    /** 解除限制, read at each check: the person may lift or restore a rule between calls. */
    internal val lifted: PhoneUnlocks
        get() = PhoneRegistry.unlocks()

    internal val clock
        get() = service.clock

    internal val actuator = Actuator(service, overlay)
    private val shooter = ScreenShooter(service, overlay)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    internal val displays = service.getSystemService(DisplayManager::class.java)
    internal val labels = ConcurrentHashMap<String, String>()

    /** Wall-clock form of a screenshot's system timestamp (only touched inside [lock]). */
    private val shotClock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lock = Any()
    internal var latest: Snapshot? = null

    /** Bumped by [abort] (stop, screen off): loops and waits started before it give up. */
    @Volatile internal var abortGen = 0L

    internal class Snapshot(
        val id: String,
        val screen: FormattedScreen,
        val packageName: String?,
        val appLabel: String?,
        val protectedPage: Boolean,
        val ownApp: Boolean,
        /** The front window hides its content from us (data-sensitive page): reported as secure. */
        val hidden: Boolean,
        /**
         * The window is there but nothing under it was readable (filtered subtree, page still
         * drawing).
         */
        val unreadable: Boolean,
        val observation: ScreenObservation,
        /** Notes already shown on this app's previous screen (not repeated). */
        val noteKeys: Set<String>,
    ) {
        @Volatile var image: ImageGeometry? = null
    }

    fun close() {
        actuator.close()
        shooter.close()
    }

    fun abort() {
        abortGen++
    }

    // ---- PhoneDriver
    // ------------------------------------------------------------------------------

    override fun availability(): Availability =
        when {
            PhoneA11yService.instance == null ->
                // l10n:keep agent screen result
                Availability(
                    false,
                    "DSH 的无障碍服务没有连接。请用户到 系统设置 → 无障碍 里重新打开「DSH 手机操控」。",
                    AppStrings.get(service, R.string.phone_availability_disconnected),
                )
            // l10n:keep agent screen result
            !power.isInteractive ->
                Availability(
                    false,
                    "屏幕已关闭。DSH 只在亮屏、解锁时操作手机，请用户点亮并解锁。",
                    AppStrings.get(service, R.string.phone_availability_screen_off),
                )
            // l10n:keep agent screen result
            keyguard.isKeyguardLocked ->
                Availability(
                    false,
                    "手机已锁屏。请用户解锁后再继续。",
                    AppStrings.get(service, R.string.phone_availability_locked),
                )
            else -> Availability(true)
        }

    override fun foregroundPackage(): String? = TreeCapture.foregroundPackage(service, screenBox())

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot =
        synchronized(lock) {
            val start = SystemClock.uptimeMillis()
            availability().let { if (!it.ok) return blank(it.reason!!) }
            val cap = capture()
            try {
                val snap = publish(cap, detail)
                val text = StringBuilder(snap.screen.text)
                var bytes: ByteArray? = null
                var mime: String? = null
                var secure = false
                if (image) {
                    val left = OBSERVE_MS - (SystemClock.uptimeMillis() - start)
                    when {
                        // l10n:keep agent screen result
                        snap.ownApp && !lifted.ownApp -> text.append("\n提示：DSH 自己的界面不截图。")
                        // l10n:keep agent screen result
                        ScreenFormat.index(cap.windows).any {
                            it.fullText.contains("«银行卡") || it.fullText.contains("«身份证")
                        } ->
                            // l10n:keep agent screen result
                            text.append("\n提示：本屏有银行卡号或身份证号，这次没有附截图。")
                        // l10n:keep agent screen result
                        left < 900 -> text.append("\n提示：读屏用时过长，这次没有附截图。")
                        else -> {
                            val window = if (cap.windows.size == 1) cap.windows[0] else null
                            val secrets = ScreenShooter.sensitiveBounds(cap.windows)
                            val shot =
                                shooter.shoot(
                                    window,
                                    cap.screen,
                                    snap.screen.elements,
                                    secrets,
                                    minOf(left - 300, 3_000),
                                )
                            secure = shot.secure
                            if (shot.bytes != null) {
                                bytes = shot.bytes
                                mime = shot.mime
                                snap.image = shot.geometry
                                // l10n:keep agent screen result
                                text.append(
                                    "\n截图上的数字是元素编号；tap_xy 用截图像素（${shot.geometry!!.width}×${shot.geometry.height}）。",
                                )
                                // l10n:keep agent screen result
                                shot.takenAtMillis?.let {
                                    text.append(
                                        "\n截图时间：${shotClock.format(Date(it))}（系统截图时间戳，设备时钟）。",
                                    )
                                }
                                // l10n:keep agent screen result
                            } else text.append("\n提示：${shot.error ?: "截图失败"}。")
                        }
                    }
                }
                ScreenSnapshot(
                    snap.id,
                    text.toString(),
                    snap.appLabel,
                    snap.packageName,
                    secure || snap.hidden,
                    snap.screen.poorTree,
                    bytes,
                    mime,
                )
            } finally {
                cap.recycle()
            }
        }

    override fun target(screenId: String, elementId: Int): TargetInfo? =
        synchronized(lock) {
            val snap = latest ?: return null
            if (snap.id != screenId) return null
            val el = snap.screen.element(elementId) ?: return null
            if (!availability().ok) return null
            val cap = capture()
            try {
                if (locate(cap, el) == null) return null
            } finally {
                cap.recycle()
            }
            info(screenId, el)
        }

    /** [el] as the core and the gate see it. */
    internal fun info(screenId: String, el: ScreenElement) =
        TargetInfo(
            screenId = screenId,
            elementId = el.id,
            role = el.role,
            label = el.label,
            packageName = el.packageName,
            appLabel = label(el.packageName),
            isPassword = el.password,
            isSecretField = el.secret,
            isEditable = el.editable,
            viewId = el.viewId,
        )

    override fun perform(action: PhoneAction): ActionResult {
        if (dev.fughetta.dshcompose.phone.ApkInstallGate.active)
            return ActionResult(false, "INSTALL_INPUT_BLOCKED")
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper())
            return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (overlay.paused) return ActionResult(false, "用户在悬浮条上按了暂停。等用户点「继续」后再操作，不要换别的方式绕过。")
            // l10n:keep agent screen result
            if (!awaitInputIdle()) return ActionResult(false, "用户正在悬浮条上输入插话，稍等再操作。")
            val gen = abortGen
            return when (action) {
                is PhoneAction.Key -> key(action, gen)
                is PhoneAction.Tap -> tap(action, gen)
                is PhoneAction.Type -> type(action, gen)
                is PhoneAction.Scroll -> scroll(action, gen)
                is PhoneAction.TapXY -> tapXY(action, gen)
            }
        }
    }

    override fun open(packageName: String): ActionResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper())
            return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (packageName == ownPackage && !lifted.ownApp)
                return ActionResult(false, "DSH 自己不需要打开。")
            val name = label(packageName) ?: packageName
            val intent =
                service.packageManager.getLaunchIntentForPackage(packageName)
                    // l10n:keep agent screen result
                    ?: return ActionResult(false, "没找到「$name」，或它没有可以打开的界面。可以先用 apps 查一下包名。")
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            val gen = abortGen
            try {
                service.startActivity(intent)
            } catch (e: Exception) {
                // l10n:keep agent screen result
                return ActionResult(false, "打开「$name」失败：${e.message ?: e.javaClass.simpleName}")
            }
            val deadline = SystemClock.uptimeMillis() + 6_000
            var front: String? = null
            while (SystemClock.uptimeMillis() < deadline && !cancelled(gen)) {
                front = foregroundPackage()
                if (front == packageName) break
                clock.pause(150) { cancelled(gen) }
            }
            if (front == packageName) clock.settle(clock.count, 800, 400, 3_000) { cancelled(gen) }
            val snap =
                if (front == packageName && availability().ok) {
                    val cap = settledCapture(null, gen)
                    try {
                        toSnapshot(publish(cap, false))
                    } finally {
                        cap.recycle()
                    }
                } else observeQuiet()
            // l10n:keep agent screen result
            return if (front == packageName) ActionResult(true, "已打开「$name」。", snap)
            // l10n:keep agent screen result
            else
                ActionResult(
                    false,
                    "已请求打开「$name」，但 6 秒内它没有到前台（现在是「${label(front) ?: front ?: "未知"}」）。",
                    snap,
                )
        }
    }

    override fun collect(
        screenId: String,
        listElementId: Int,
        maxPages: Int,
        until: String?,
    ): CollectResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper())
            return CollectResult(emptyList(), 0, "内部错误：不能在主线程执行", null)
        synchronized(lock) {
            availability().let {
                if (!it.ok) return CollectResult(emptyList(), 0, it.reason!!, null)
            }
            val snap = latest
            if (snap == null || snap.id != screenId) {
                // l10n:keep agent screen result
                return CollectResult(
                    emptyList(),
                    0,
                    "屏幕编号 $screenId 已过期，请先看新屏幕再 collect",
                    observeQuiet(),
                )
            }
            // l10n:keep agent screen result
            if (snap.ownApp && !lifted.ownApp)
                return CollectResult(emptyList(), 0, "这是 DSH 自己的界面，不读取", null)
            var el =
                snap.screen.element(listElementId)
                    // l10n:keep agent screen result
                    ?: return CollectResult(
                        emptyList(),
                        0,
                        "屏幕 $screenId 里没有编号 $listElementId",
                        null,
                    )
            if (el.kind != ElementKind.LIST) {
                el =
                    el.listId?.let { snap.screen.element(it) }
                        // l10n:keep agent screen result
                        ?: return CollectResult(
                            emptyList(),
                            0,
                            "[$listElementId] 不是列表，也不在列表里",
                            null,
                        )
            }
            val pages = maxPages.coerceIn(1, 30)
            val wanted = until?.trim()?.takeIf { it.isNotEmpty() }
            val gen = abortGen
            val deadline = SystemClock.uptimeMillis() + COLLECT_MS
            val items = CollectedItems()
            // l10n:keep agent screen result
            var stop = "未能读取列表"
            var read = 0
            var misses = 0
            val direction =
                if (el.horizontal) PhoneAction.Direction.RIGHT else PhoneAction.Direction.DOWN
            var lastBounds = el.bounds
            while (true) {
                val cap = capture()
                try {
                    // Nothing readable in front: this says "cannot read" instead of blaming the
                    // list.
                    if (unavailable(cap)) {
                        // l10n:keep agent screen result
                        stop = "这一屏读不到内容（可能受敏感视图策略限制）"
                        break
                    }
                    val list =
                        ScreenFormat.followList(
                            ScreenFormat.index(cap.windows),
                            el.fingerprint,
                            lastBounds,
                        )
                            // l10n:keep agent screen result
                            ?: run {
                                stop = "列表不见了（界面变了）"
                                null
                            }
                            ?: break
                    val page = items.readPage(list.itemTexts, wanted, cap.truncated || cap.partial)
                    if (list.itemTexts.isNotEmpty()) read++
                    if (page.stopReason != null) {
                        stop = page.stopReason
                        break
                    }
                    if (list.itemTexts.isEmpty()) {
                        // l10n:keep agent screen result
                        stop = "列表没有可读条目，无法确认是否到底"
                        break
                    }
                    lastBounds = list.bounds
                    // l10n:keep agent screen result
                    if (page.found) {
                        stop = "找到了「$wanted」"
                        break
                    }
                    // l10n:keep agent screen result
                    if (items.chars > COLLECT_CHARS) {
                        stop = "内容太多，先停在这里"
                        break
                    }
                    // l10n:keep agent screen result
                    if (read > 1 && page.added == 0) {
                        if (++misses >= 2) {
                            stop = "没有读到更多条目，可能已经到底"
                            break
                        }
                    } else misses = 0
                    // l10n:keep agent screen result
                    if (read >= pages) {
                        stop = "达到页数上限（$pages 页）"
                        break
                    }
                    // l10n:keep agent screen result
                    if (cancelled(gen)) {
                        stop = "已停止"
                        break
                    }
                    // l10n:keep agent screen result
                    if (overlay.paused) {
                        stop = "用户暂停了"
                        break
                    }
                    // l10n:keep agent screen result
                    if (SystemClock.uptimeMillis() > deadline) {
                        stop = "超时"
                        break
                    }
                    // l10n:keep agent screen result
                    val node =
                        cap.native(list.ref)
                            ?: run {
                                stop = "列表不见了（界面变了）"
                                null
                            }
                            ?: break
                    val before = clock.count
                    val out =
                        actuator.scroll(
                            node,
                            el.scroll,
                            el.horizontal,
                            list.bounds,
                            cap.screen,
                            direction,
                        )
                    // l10n:keep agent screen result
                    if (!out.accepted) {
                        stop = "系统没有接受滚动，无法确认是否到底"
                        break
                    }
                    clock.settle(before, 1_200, 350, 3_000) { cancelled(gen) }
                    if (misses > 0)
                        clock.pause(800) { cancelled(gen) } // lists that load more on demand
                } finally {
                    cap.recycle()
                }
            }
            return CollectResult(items.list(), read, stop, observeQuiet())
        }
    }

    // ---- actions
    // ----------------------------------------------------------------------------------

    internal class Resolved(
        val snap: Snapshot,
        val el: ScreenElement,
        val cap: Capture,
        val node: AccessibilityNodeInfo,
        val found: IndexedElement,
    )

    /** The element of the latest snapshot, re-verified against a fresh capture; or the refusal. */
    internal fun resolve(screenId: String, elementId: Int): Any {
        val snap = latest
        if (snap == null || snap.id != screenId) {
            val now = observeQuiet()
            // l10n:keep agent screen result
            val msg =
                if (snap == null) "还没有读过屏幕，先用 screen 看一下。"
                // l10n:keep agent screen result
                else "屏幕编号 $screenId 过期了（最新 ${now?.id ?: snap.id}），没有执行。按新屏幕的编号来。"
            return ActionResult(false, msg, now)
        }
        val el =
            snap.screen.element(elementId)
                // l10n:keep agent screen result
                ?: return ActionResult(
                    false,
                    "屏幕 $screenId 里没有编号 $elementId（共 ${snap.screen.elements.size} 项）。",
                )
        val cap = capture()
        val hit = locate(cap, el)
        val node = hit?.let { cap.native(it.ref) }
        if (hit == null || node == null) {
            val now =
                try {
                    publish(cap, false).let { toSnapshot(it) }
                } finally {
                    cap.recycle()
                }
            // l10n:keep agent screen result
            return ActionResult(
                false,
                "目标 [$elementId]「${short(el.label.ifEmpty { el.role })}」变了，没有执行；看新屏幕再选。",
                now,
            )
        }
        return Resolved(snap, el, cap, node, hit)
    }

    internal fun refuseWrite(r: Resolved): ActionResult? {
        val lifted = lifted
        return when {
            // l10n:keep agent screen result
            (r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp ->
                ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            r.snap.protectedPage && !lifted.systemPages ->
                ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击或输入。需要的话请用 handoff 交给用户。")
            // l10n:keep agent screen result
            (r.el.password || r.el.secret) && !lifted.secretFields ->
                ActionResult(false, "这是密码 / 验证码一类的输入框，DSH 不替用户点或填。请用 handoff 让用户自己来。")
            else -> null
        }
    }

    companion object {
        internal const val TAG = "Accessibility"

        /**
         * A `type` whose read-back differs from what was typed. An ordinary input says what it
         * holds now; a password or code field ([SafetyRules.isSecretTarget], as for the log's ••••)
         * never does: the tool result goes to the model.
         */
        internal fun readBackMismatch(got: String?, target: TargetInfo): String {
            // l10n:keep agent screen result
            val now =
                if (SafetyRules.isSecretTarget(target)) "这是密码 / 验证码框，框里的内容不回传"
                // l10n:keep agent screen result
                else
                    "框里现在是" +
                        ScreenMask.mask(got.orEmpty()).let {
                            if (it.isEmpty()) "空" else "「${ScreenFormat.clip(it, 40)}」"
                        }
            // l10n:keep agent screen result
            return "已输入，但回读不一致：$now。没有重试，也没有提交；请看新屏幕决定下一步。"
        }

        /** Process-wide: a reconnected service must never hand out an id the model still holds. */
        internal val counter = java.util.concurrent.atomic.AtomicInteger()
        private const val OBSERVE_MS = 4_800L
        internal const val CAPTURE_MS = 2_000L
        private const val COLLECT_MS = 100_000L
        private const val COLLECT_CHARS = 60_000
    }
}
