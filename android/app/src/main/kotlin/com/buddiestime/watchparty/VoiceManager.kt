package com.buddiestime.watchparty

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "HWP-VOICE"

/**
 * Wraps a LiveKit [Room] for voice chat. Caller creates one, then:
 *   - [joinVoice] with a server-minted token + url
 *   - [setMicOn] true/false to publish/unpublish the local mic
 *   - [leaveVoice] to tear down
 *
 * Callbacks surface state on the Android main thread.
 */
class VoiceManager(
    private val context: Context,
    private val onStatusChange: (VoiceStatus) -> Unit,
    private val onSpeakersChange: (Set<String>) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class VoiceStatus { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private var room: Room? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null

    @Volatile var micOn: Boolean = false
        private set
    @Volatile var status: VoiceStatus = VoiceStatus.IDLE
        private set

    fun joinVoice(url: String, token: String) {
        Log.d(TAG, "joinVoice() url=$url tokenLen=${token.length}")
        if (room != null) {
            Log.w(TAG, "  room already exists → leaving first")
            leaveVoice()
        }
        setStatus(VoiceStatus.CONNECTING)
        val r = LiveKit.create(context.applicationContext)
        room = r

        // Default AudioSwitchHandler requests audio focus with MODE_IN_COMMUNICATION +
        // AUDIOFOCUS_GAIN (permanent). MODE_IN_COMMUNICATION puts the device in "phone call"
        // audio-policy state, which makes Android duck any concurrently playing STREAM_MUSIC —
        // here, the Hotstar video's audio — for as long as the mic is enabled. This app wants
        // voice chat *alongside* the video, not a phone call that suppresses it, so relax both:
        // MODE_NORMAL avoids the phone-call ducking policy, and TRANSIENT_MAY_DUCK avoids holding
        // a permanent/exclusive focus claim.
        (r.audioHandler as? AudioSwitchHandler)?.let { handler ->
            handler.audioMode = AudioManager.MODE_NORMAL
            handler.focusMode = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            Log.d(TAG, "  audio handler configured: audioMode=${handler.audioMode} focusMode=${handler.focusMode}")
        } ?: Log.w(TAG, "  room.audioHandler is not an AudioSwitchHandler — skipping duck fix")

        eventJob = scope.launch {
            r.events.collect { ev ->
                when (ev) {
                    is RoomEvent.Disconnected -> {
                        Log.d(TAG, "RoomEvent.Disconnected reason=${ev.reason}")
                        setStatus(VoiceStatus.DISCONNECTED)
                        micOn = false
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        val ids = ev.speakers.map { it.identity?.value ?: "" }.filter { it.isNotEmpty() }.toSet()
                        Log.d(TAG, "RoomEvent.ActiveSpeakersChanged ids=$ids")
                        onSpeakersChange(ids)
                    }
                    else -> Log.v(TAG, "room event: ${ev::class.simpleName}")
                }
            }
        }

        scope.launch {
            try {
                Log.d(TAG, "  → room.connect()")
                r.connect(url, token)
                Log.d(TAG, "  → connected")
                setStatus(VoiceStatus.CONNECTED)
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed: ${e.message}", e)
                onError("voice-connect-failed: ${e.message ?: "unknown"}")
                setStatus(VoiceStatus.IDLE)
                cleanup()
            }
        }
    }

    fun setMicOn(on: Boolean) {
        Log.d(TAG, "setMicOn($on) — current=$micOn status=$status")
        val r = room ?: run { Log.w(TAG, "  no room"); return }
        if (status != VoiceStatus.CONNECTED) { Log.w(TAG, "  not connected, ignore"); return }
        if (on == micOn) { Log.d(TAG, "  already in desired state"); return }
        scope.launch {
            try {
                r.localParticipant.setMicrophoneEnabled(on)
                micOn = on
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                Log.d(TAG, "  mic now ${if (on) "ON" else "OFF"} — system AudioManager.mode=${am?.mode} isMusicActive=${am?.isMusicActive}")
            } catch (e: Throwable) {
                Log.e(TAG, "setMicOn($on) failed: ${e.message}", e)
                onError("mic-toggle-failed: ${e.message ?: "unknown"}")
            }
        }
    }

    fun leaveVoice() {
        Log.d(TAG, "leaveVoice()")
        val r = room
        if (r != null) {
            scope.launch {
                try { r.disconnect() } catch (e: Throwable) { Log.w(TAG, "disconnect threw: ${e.message}") }
            }
        }
        cleanup()
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup()")
        eventJob?.cancel(); eventJob = null
        room = null
        micOn = false
        onSpeakersChange(emptySet())
    }

    private fun setStatus(s: VoiceStatus) {
        Log.d(TAG, "status $status → $s")
        status = s
        onStatusChange(s)
    }

    fun dispose() {
        Log.d(TAG, "dispose()")
        leaveVoice()
        scope.cancel()
    }
}
