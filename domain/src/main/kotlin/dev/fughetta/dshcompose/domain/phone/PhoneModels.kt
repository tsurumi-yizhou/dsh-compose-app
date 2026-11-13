package dev.fughetta.dshcompose.domain.phone

/** Which screen a snapshot or action belongs to; the prefix of every screen id. */
enum class PhoneScreen(val prefix: Char) {
    /** What the person sees, read and driven through the accessibility service. */
    MAIN('s'),

    /** 不打扰模式: a virtual display the person doesn't see. */
    BACKGROUND('b');

    companion object {
        /**
         * `"s7"` → MAIN, `"b3"` → BACKGROUND, anything else → null. Never falls back across
         * screens.
         */
        fun ofScreenId(id: String): PhoneScreen? = entries.firstOrNull {
            id.length > 1 && id[0] == it.prefix
        }
    }
}

/**
 * One observation of a screen, rendered for a text model: one line per element, Chinese role words,
 * element ids valid only for this snapshot's [id].
 */
data class ScreenSnapshot(
    /** e.g. `s7`: prefix + a counter that increases with every snapshot of that screen. */
    val id: String,
    /** The compact text form, header line included. Never contains password contents. */
    val text: String,
    /** Foreground app label and package, when known. */
    val appLabel: String?,
    val packageName: String?,
    /** The window refused capture (FLAG_SECURE) or the tree came back empty for a secure window. */
    val secure: Boolean = false,
    /** Fewer than five text nodes: games, canvases, some mini-programs. */
    val poorTree: Boolean = false,
    /** Optional numbered screenshot (same element ids drawn on it), PNG or JPEG bytes. */
    val image: ByteArray? = null,
    val imageMime: String? = null,
)

/** What an element looks like to the safety rules, resolved from a snapshot id + element id. */
data class TargetInfo(
    val screenId: String,
    val elementId: Int,
    /** Chinese role word as rendered (按钮, 输入框, …). */
    val role: String,
    /** Visible text / content description / hint, already length-capped. */
    val label: String,
    val packageName: String?,
    val appLabel: String?,
    val isPassword: Boolean,
    /** Hint mentions 密码 / 验证码 / 支付密码 / CVV / 安全码. */
    val isSecretField: Boolean,
    val isEditable: Boolean,
    /**
     * Resource id (`com.tencent.mm:id/btn_send`), when known. Added by the core so the gate's check
     * `SensitiveWords.confirmHit(label, viewId)` is the same one the screen reader uses for `⚠`: an
     * icon-only send button whose id says "send" is marked AND confirmed. When a driver leaves it
     * null, only the label counts.
     */
    val viewId: String? = null,
)

sealed interface PhoneAction {
    val screenId: String

    data class Tap(override val screenId: String, val elementId: Int, val long: Boolean = false) :
        PhoneAction

    data class Type(
        override val screenId: String,
        val elementId: Int,
        val text: String,
        /** Press the IME action / Enter after typing. */
        val submit: Boolean = false,
    ) : PhoneAction

    data class Scroll(
        override val screenId: String,
        val elementId: Int?,
        val direction: Direction,
    ) : PhoneAction

    data class Key(override val screenId: String, val key: GlobalKey) : PhoneAction

    /**
     * Flash only: a point on a numbered screenshot ([screenId] is the observation it came from).
     * Always confirmed.
     */
    data class TapXY(override val screenId: String, val x: Int, val y: Int) : PhoneAction

    enum class Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT,
    }

    enum class GlobalKey {
        BACK,
        HOME,
        RECENTS,
        NOTIFICATIONS,
    }
}

data class ActionResult(
    val ok: Boolean,
    /** One short Chinese sentence for the model: what happened, or why not. */
    val message: String,
    /** The screen after the action settled (new lines marked `+`), when it could be read. */
    val screen: ScreenSnapshot? = null,
)

data class CollectResult(
    /** De-duplicated items, one per line, in on-screen order. */
    val items: List<String>,
    val pages: Int,
    /** Stopped because: end of list / `until` matched / page limit / list lost. */
    val stopReason: String,
    val screen: ScreenSnapshot?,
)

/** Whether a driver can work right now, and if not, a sentence the model can relay. */
data class Availability(val ok: Boolean, val reason: String? = null, val userReason: String? = null)

data class ConfirmRequest(
    /** The full plain sentence shown to the person, e.g. 在「微信」给 张三 发送："晚上见". */
    val sentence: String,
    val appLabel: String?,
    val packageName: String?,
    /** First action in this app during this task: approval covers the rest of the task. */
    val firstInApp: Boolean,
    val timeoutMs: Long = 30_000,
    /**
     * 本任务免确认: offer [Decision.ALLOW_ALL_FOR_TASK] as the main choice (see ConfirmMode); a gate that
     * ignores it still answers correctly, just asks more.
     */
    val offerTaskWaiver: Boolean = false,
)

/**
 * [ALLOW_ALL_FOR_TASK]: nothing else in this task is asked (only offered with
 * [ConfirmRequest.offerTaskWaiver]).
 */
enum class Decision {
    ALLOW_ONCE,
    ALLOW_APP_FOR_TASK,
    ALLOW_ALL_FOR_TASK,
    DENY,
    STOP,
    UNAVAILABLE,
}

enum class HandoffResult {
    CONTINUE,
    STOP,
    TIMEOUT,
}

/**
 * Why a `handoff` came: the hard rule that refused the task's last write, when there was one. The
 * card names it in its title (「密码需要你来输入」) so the person knows it is a rule, not a fault; the
 * model's own sentence stays the body. Null: the model handed off on its own.
 */
enum class HandoffCause(val rule: String) {
    /** A password, verification-code or payment-code input. */
    SECRET("secret-field"),

    /** System settings, permissions, installers, security centres, SystemUI. */
    SYSTEM("system-page"),

    /** An app on the person's 不碰 list. */
    DENY_LIST("deny-list");

    companion object {
        /** From an action-log decision (`refused:<rule>`); null for anything else. */
        fun ofDecision(decision: String?): HandoffCause? {
            if (decision == null || !decision.startsWith("refused:")) return null
            val rule = decision.removePrefix("refused:")
            return entries.firstOrNull { it.rule == rule }
        }
    }
}
