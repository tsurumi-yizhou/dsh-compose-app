package dev.fughetta.dshcompose.nativemedia

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.fughetta.dshcompose.sessions.foreground

data class MediaLease(val owner: String, val generation: Long, val lease: String)

data class NativeMediaEvent(
    val owner: MediaLease,
    val kind: String,
    val reason: String? = null,
)

class NativeMediaException(val code: String) : Exception(code)

/** Main-thread capability owner. Playback and overlays end with the visible Activity. */
class NativeMedia(private val context: Context, private val onEvent: (NativeMediaEvent) -> Unit) :
    AutoCloseable, DefaultLifecycleObserver {
    private class Owner(val key: MediaLease, val title: String)

    private var activity: ComponentActivity? = null
    private var owner: Owner? = null
    private var player: ClipPlayer? = null
    private val overlays = MediaOverlays(onCaptionClosed = { emit("overlay.closed", "user") })
    val features = setOf("audio", "overlay")

    fun attach(host: ComponentActivity?) {
        if (activity === host) return
        revoke("detached")
        activity?.lifecycle?.removeObserver(this)
        activity = host
        overlays.attach(host)
        host?.lifecycle?.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        revoke("background")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        attach(null)
    }

    private fun foreground() =
        activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    fun activate(key: MediaLease, title: String, top: Float? = null) {
        if (key.owner.isBlank() || key.lease.isEmpty() || key.lease.length > 256)
            throw NativeMediaException("bad-request")
        if (!foreground()) throw NativeMediaException("not-foreground")
        if (!disposeLocal()) throw NativeMediaException("close")
        owner = Owner(key, title.take(24).ifBlank { "plugin" })
        top?.let(overlays::setTop)
    }

    private fun current(key: MediaLease): Owner =
        owner?.takeIf { it.key == key } ?: throw NativeMediaException("stale-owner")

    private fun requireForeground() {
        if (!foreground()) throw NativeMediaException("not-foreground")
    }

    private fun requireDone(done: Boolean, code: String) {
        if (!done) throw NativeMediaException(code)
    }

    fun play(key: MediaLease, privatePath: String, contentType: String) {
        val own = current(key)
        requireForeground()
        val file =
            ClipPath.allowed(context.cacheDir, own.key.owner, privatePath, contentType)
                ?: throw NativeMediaException("media-invalid")
        val clip = player ?: ClipPlayer(context, ::emit).also { player = it }
        requireDone(clip.play(file), "media-invalid")
    }

    fun pause(key: MediaLease) {
        current(key)
        requireDone(player?.pause() == true, "audio")
    }

    fun resume(key: MediaLease) {
        current(key)
        requireForeground()
        requireDone(player?.resume() == true, "audio")
    }

    fun stop(key: MediaLease) {
        current(key)
        requireDone(player?.release() != false, "close")
    }

    fun showOverlay(key: MediaLease, text: String, update: Boolean = false) {
        current(key)
        if (text.codePointCount(0, text.length) > 240 || text.toByteArray().size > 1024)
            throw NativeMediaException("bad-request")
        val clean = text.replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ").trim()
        if (update) requireDone(overlays.updateCaption(clean), "unsupported")
        else {
            requireForeground()
            requireDone(overlays.showCaption(clean), "unsupported")
        }
    }

    fun hideOverlay(key: MediaLease) {
        current(key)
        overlays.hideCaption()
    }

    fun disposeOwner(key: MediaLease) {
        if (owner?.key != key) return
        requireDone(disposeLocal(), "close")
    }

    private fun disposeLocal(): Boolean {
        owner = null
        val playerClosed = player?.release() != false
        overlays.hideAll()
        return playerClosed
    }

    private fun revoke(reason: String) {
        val own = owner ?: return
        disposeLocal()
        onEvent(NativeMediaEvent(own.key, "owner.revoked", reason))
    }

    private fun emit(kind: String, reason: String? = null) {
        owner?.let { onEvent(NativeMediaEvent(it.key, kind, reason)) }
    }

    override fun close() {
        attach(null)
        disposeLocal()
    }
}
