package dev.fughetta.dshcompose.phoneui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.fughetta.dshcompose.phone.PhoneControl

/** Keeps native phone confirmations attached to the current Activity through recreation. */
class PhoneActivityBinding(private val activity: ComponentActivity) :
    DefaultLifecycleObserver, AutoCloseable {
    private val prompts = PhoneControl.get(activity.applicationContext).prompts
    private val owner = Any()
    private var closed = false

    init {
        activity.lifecycle.addObserver(this)
        prompts.attachActivity(owner, activity)
        activity.intent?.let(::handleIntent)
    }

    fun handleIntent(intent: Intent): Boolean = !closed && prompts.handleOpenIntent(intent)

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (closed) return
        closed = true
        prompts.detachOwner(owner)
        activity.lifecycle.removeObserver(this)
    }
}
