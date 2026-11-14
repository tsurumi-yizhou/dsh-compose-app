package dev.fughetta.dshcompose.phone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether one of this app's activities is resumed (the clipboard and background-start rules care).
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger()
    private val resumedActivities =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Activity, Boolean>())
    @Volatile private var registered = false

    val visible: Boolean
        get() = resumed.get() > 0

    fun isResumed(activity: Activity): Boolean =
        synchronized(resumedActivities) { activity in resumedActivities }

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(resumedActivities) {
            if (resumedActivities.add(activity)) resumed.incrementAndGet()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        synchronized(resumedActivities) {
            if (resumedActivities.remove(activity)) resumed.decrementAndGet()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
}
