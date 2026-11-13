package dev.fughetta.dshcompose.nativemedia

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File
import java.io.FileInputStream

/**
 * One plugin clip at a time, on the main looper (MediaPlayer wants every call and callback on one
 * thread). A generation number guards every callback: after [stop] / [release] nothing that was
 * already queued — a late onPrepared above all — can start sound again.
 *
 * Audio focus: transient, others duck; losing it ends the clip (the playback event callback
 * "interrupted").
 */
class ClipPlayer(context: Context, private val onEvent: (String, String?) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var generation = 0
    private var focus: AudioFocusRequest? = null

    /** Preparing, playing or paused. */
    val active
        get() = player != null

    var playing = false
        private set

    /** Starts [file] (already checked by [ClipPath]); false when it could not even be opened. */
    fun play(file: File): Boolean {
        release()
        val gen = ++generation
        val p = MediaPlayer()
        player = p
        return try {
            p.setAudioAttributes(ATTRIBUTES)
            p.setOnPreparedListener {
                if (gen != generation) return@setOnPreparedListener
                if (!requestFocus()) {
                    finish(gen) { onEvent("audio.stopped", "focus") }
                    return@setOnPreparedListener
                }
                try {
                    it.start()
                    playing = true
                } catch (_: RuntimeException) {
                    finish(gen) { onEvent("audio.error", "start") }
                }
            }
            p.setOnCompletionListener {
                if (gen == generation) finish(gen) { onEvent("audio.ended", null) }
            }
            p.setOnErrorListener { _, _, _ ->
                if (gen == generation) finish(gen) { onEvent("audio.error", "player") }
                true // handled: never also a completion
            }
            FileInputStream(file).use { p.setDataSource(it.fd) }
            p.prepareAsync()
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    fun pause(): Boolean {
        val p = player ?: return false
        if (!playing) return true
        return runCatching { p.pause() }.onSuccess { playing = false }.isSuccess
    }

    fun resume(): Boolean {
        val p = player ?: return false
        if (playing) return true
        if (!requestFocus()) return false
        return runCatching { p.start() }.onSuccess { playing = true }.isSuccess
    }

    /** Stops and frees the player now; true when it is gone (or there was none). */
    fun release(): Boolean {
        generation++
        playing = false
        val p = player ?: return true
        player = null
        abandonFocus()
        return runCatching { p.release() }.isSuccess
    }

    private inline fun finish(gen: Int, then: () -> Unit) {
        if (gen != generation) return
        release()
        then()
    }

    private fun requestFocus(): Boolean {
        val am = audio ?: return true
        // A focus event can outlive abandon/re-request. Bind both the listener identity
        // and its generation to this clip, rather than rebinding an old event to a new one.
        val gen = generation
        val request =
            focus
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(ATTRIBUTES)
                    .setOnAudioFocusChangeListener { change ->
                        if (
                            change == AudioManager.AUDIOFOCUS_LOSS ||
                                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            if (player != null)
                                finish(gen) { onEvent("audio.stopped", "interrupted") }
                        }
                    }
                    .build()
                    .also { focus = it }
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        runCatching { audio?.abandonAudioFocusRequest(request) }
    }

    private companion object {
        val ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
