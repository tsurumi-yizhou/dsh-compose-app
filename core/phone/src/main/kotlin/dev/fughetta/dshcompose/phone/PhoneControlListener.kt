package dev.fughetta.dshcompose.phone

/** Host operations used by the optional main-screen feature. */
interface PhoneControlListener {
    val tasks: PhoneTasks
    val visible: Boolean
    val liveTaskApp: String?

    fun isEnabled(): Boolean

    fun finishTask(expectedTaskId: Long? = null): Boolean

    fun reopen(sessionId: String?, host: String?): Boolean

    fun openLink(url: String)

    fun onPauseChanged(paused: Boolean)

    fun onStopRequested(reason: String)

    fun onInterject(text: String)

    fun onAccessibilityChanged(connected: Boolean)
}
