package dev.fughetta.dshcompose.phone

import dev.fughetta.dshcompose.domain.phone.ActionResult
import dev.fughetta.dshcompose.domain.phone.Availability
import dev.fughetta.dshcompose.domain.phone.CollectResult
import dev.fughetta.dshcompose.domain.phone.ConfirmRequest
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.domain.phone.HandoffCause
import dev.fughetta.dshcompose.domain.phone.HandoffResult
import dev.fughetta.dshcompose.domain.phone.PhoneAction
import dev.fughetta.dshcompose.domain.phone.PhoneScreen
import dev.fughetta.dshcompose.domain.phone.PhoneUnlocks
import dev.fughetta.dshcompose.domain.phone.ScreenSnapshot
import dev.fughetta.dshcompose.domain.phone.TargetInfo

// The contract between the phone-control modules.
// Each module owns its side:
//
//   PhoneBridge (MCP over a unix socket) ── SafetyGate ──┬── main screen:  PhoneA11yService
// (accessibility)
//                                                        └── background:   BackgroundDisplay
// (virtual display
//                                                                          via Shizuku or self-ADB)
//   UserGate: who asks the person (overlay on the main screen, heads-up notification otherwise).
//
// Everything here is plain Kotlin with blocking calls: the bridge runs every
// action on its own single worker thread (this bridge path uses blocking calls), and each
// call has a timeout of its own.

/**
 * A screen the agent can read and drive. Implemented by the accessibility service (MAIN) and by the
 * virtual-display module (BACKGROUND). All calls block and must return within their own timeout
 * (observe ≤ 5 s, actions ≤ 15 s incl. settling).
 */
interface PhoneDriver {
    val screen: PhoneScreen

    fun availability(): Availability

    fun observe(detail: Boolean, image: Boolean): ScreenSnapshot

    fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult

    /**
     * Resolves an element of a recent snapshot for the safety rules. Null when the snapshot is
     * stale or the element no longer matches its fingerprint (window, bounds, text); the bridge
     * then refuses and re-observes.
     */
    fun target(screenId: String, elementId: Int): TargetInfo?

    fun perform(action: PhoneAction): ActionResult

    /** Launches [packageName] on this screen (BACKGROUND moves the task to the virtual display). */
    fun open(packageName: String): ActionResult

    /**
     * Package of the app in front on this screen, if known (for per-app approval and deny lists).
     */
    fun foregroundPackage(): String?
}

/**
 * Asks the person. The main-screen implementation is the accessibility overlay (ControlOverlay);
 * without it, a heads-up notification. Confirm buttons must not be reachable by injected input.
 */
class UserGate(
    val confirm: (ConfirmRequest) -> Decision,
    private val handoffWithCause: (String, Long, HandoffCause?) -> HandoffResult,
    val status: (String?) -> Unit,
    private val cleanup: ((() -> Unit) -> Unit) -> Unit = { current -> current { status(null) } },
    val showPrompt: (PromptPresentation) -> (() -> Unit)? = { null },
) {
    fun handoff(reason: String, timeoutMs: Long, cause: HandoffCause? = null): HandoffResult =
        handoffWithCause(reason, timeoutMs, cause)

    /** Cleanup is applied only while the originating task still owns the surface. */
    fun close(ifCurrent: (() -> Unit) -> Unit = { it() }) = cleanup(ifCurrent)
}

/**
 * Where the modules meet at run time. Services register themselves when they connect and clear on
 * disconnect; the bridge reads these on every call.
 */
object PhoneRegistry {
    @Volatile var mainDriver: PhoneDriver? = null
    @Volatile var backgroundDriver: PhoneDriver? = null

    /** Overlay gate when the accessibility service runs, else the notification gate. */
    @Volatile var overlayGate: UserGate? = null
    @Volatile var notificationGate: UserGate? = null

    /** Process-wide arbiter for native and engine questions; presenters do not call one another. */
    @Volatile var promptGate: UserGate? = null

    /** The process owner receives overlay actions and accessibility lifecycle changes. */
    @Volatile var controlListener: PhoneControl? = null

    /**
     * The hard rules the person lifted (解除限制), for the drivers' own second line of refusals and the
     * overlay's 「已解除限制」 tag. Nothing lifted until the core says otherwise.
     */
    @Volatile var unlocks: () -> PhoneUnlocks = { PhoneUnlocks() }

    fun driver(screen: PhoneScreen): PhoneDriver? =
        when (screen) {
            PhoneScreen.MAIN -> mainDriver
            PhoneScreen.BACKGROUND -> backgroundDriver
        }

    fun gate(): UserGate? = overlayGate ?: notificationGate
}

/** Optional native UI copy for pure phone-control code. */
class PhoneUiText(val resolve: (String, List<Any>) -> String?) {

    fun get(key: String, fallback: String, vararg args: Any): String =
        resolve(key, args.toList()) ?: String.format(java.util.Locale.ROOT, fallback, *args)

    fun role(value: String): String =
        when (value) {
            "按钮" -> get("phone_role_button", value) // l10n:keep model role key
            "输入框" -> get("phone_role_input", value) // l10n:keep model role key
            "列表" -> get("phone_role_list", value) // l10n:keep model role key
            "开关" -> get("phone_role_switch", value) // l10n:keep model role key
            "勾选框" -> get("phone_role_checkbox", value) // l10n:keep model role key
            "单选" -> get("phone_role_radio", value) // l10n:keep model role key
            "滑块" -> get("phone_role_slider", value) // l10n:keep model role key
            "下拉框" -> get("phone_role_dropdown", value) // l10n:keep model role key
            "链接" -> get("phone_role_link", value) // l10n:keep model role key
            "标签" -> get("phone_role_tab", value) // l10n:keep model role key
            "图片" -> get("phone_role_image", value) // l10n:keep model role key
            "文本" -> get("phone_role_text", value) // l10n:keep model role key
            else -> value
        }

    companion object {
        val DEFAULT = PhoneUiText { _, _ -> null }
    }
}
