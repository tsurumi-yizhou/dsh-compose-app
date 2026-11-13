package dev.fughetta.dshcompose.runtime

// Some phone makers cut an app's network as soon as it leaves the screen, foreground service or
// not (vivo until 「允许后台高耗电」 is on). The engine runs as this app, so a turn left running in
// the background stalls on failed requests and picks up again only when the app comes back. This
// decides when to say so: one quiet line in the chat on the way back, leading to 后台保活.
import android.content.Context
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The decisions, without storage or the platform (unit-tested). */
object BackgroundNetPolicy {
    /** Per reason: the first time and once more, until the app is let run in the background. */
    const val MAX_SHOWS = 2

    /** Makers that hold background apps back out of the box. */
    val strictVendors =
        setOf(
            VendorSettings.VIVO,
            VendorSettings.XIAOMI,
            VendorSettings.HUAWEI,
            VendorSettings.HONOR,
            VendorSettings.OPPO,
        )

    /**
     * What a return to the app after [since] calls for. Only while the app is not exempt from
     * battery optimization ([exempt] false; null means unknown, so nothing is said).
     */
    fun onReturn(
        since: Long,
        exempt: Boolean?,
        shown: Map<BackgroundNetReason, Int>,
        blockedAt: Long? = null,
        transportFailed: Boolean = false,
    ): BackgroundNetReason? {
        if (exempt != false) return null
        val reason =
            when {
                blockedAt != null && blockedAt >= since -> BackgroundNetReason.Blocked
                transportFailed -> BackgroundNetReason.Failures
                else -> return null
            }
        return reason.takeIf { (shown[it] ?: 0) < MAX_SHOWS }
    }

    /** The one-time intro after a finished turn. */
    fun introDue(
        introduced: Boolean,
        vendor: String?,
        exempt: Boolean?,
        shown: Map<BackgroundNetReason, Int>,
    ) = !introduced && vendor in strictVendors && exempt == false && shown.values.all { it == 0 }
}

/**
 * The line to show now, or null. The owner feeds it app visibility and on-device turn ends; the
 * person dismisses it. [retries] names the open on-device conversation's transport retries, so a
 * new one between leaving and coming back tells of failed requests.
 */
internal class BackgroundNetController(
    context: Context,
    private val scope: CoroutineScope,
    private val vendor: String?,
    private val exempt: suspend () -> Boolean?,
    private val blockedAt: () -> Long?,
    private val retries: () -> Set<String>,
) {
    private val prefs = context.localPreferences("background_net")
    private val shown =
        BackgroundNetReason.entries.associateWithTo(mutableMapOf()) {
            prefs.getInt("shown.${it.name}", 0)
        }
    private var introduced = prefs.getBoolean("intro", false)
    private var since: Long? = null
    private var retriesBefore = emptySet<String>()
    private var visible = true
    private val mutable = MutableStateFlow<BackgroundNetReason?>(null)
    val state = mutable.asStateFlow()

    /** The app left the screen ([value] false) or came back. */
    fun visible(value: Boolean) {
        if (value == visible) return
        visible = value
        if (!value) {
            since = System.currentTimeMillis()
            retriesBefore = retries()
            return
        }
        val left = since ?: return
        since = null
        val failed = (retries() - retriesBefore).isNotEmpty()
        scope.launch {
            val reason =
                BackgroundNetPolicy.onReturn(left, checkedExempt(), shown, blockedAt(), failed)
            if (reason != null && visible) show(reason)
        }
    }

    /** A turn on the on-device engine finished. */
    fun turnEnded() {
        if (introduced || !visible || mutable.value != null) return
        scope.launch {
            val due = BackgroundNetPolicy.introDue(introduced, vendor, checkedExempt(), shown)
            if (due && visible) show(BackgroundNetReason.Intro)
        }
    }

    /** Tapped away, or 去放行 was tapped. */
    fun dismiss() {
        mutable.value = null
    }

    private suspend fun checkedExempt(): Boolean? {
        val value = exempt()
        if (value == true && shown.values.any { it > 0 }) {
            // Let run in the background: a later revoke may bring the line back.
            shown.replaceAll { _, _ -> 0 }
            save()
        }
        return value
    }

    private fun show(reason: BackgroundNetReason) {
        shown[reason] = (shown[reason] ?: 0) + 1
        // Any line about background running counts as the introduction.
        introduced = true
        save()
        mutable.value = reason
    }

    private fun save() {
        prefs
            .edit()
            .apply { shown.forEach { (reason, n) -> putInt("shown.${reason.name}", n) } }
            .putBoolean("intro", introduced)
            .apply()
    }
}
