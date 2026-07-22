package com.buddiestime.watchparty

import android.content.Context
import android.util.Log
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "HWP-VOICE"

/**
 * Thin wrapper around the LiveKit Android client for the watch-party voice
 * room. One instance per MainActivity session; disposed on leave/destroy.
 *
 * Deliberately does not subscribe to Room's event stream (active-speaker
 * highlighting, remote-disconnect notification) — callers only get status
 * updates from the actions they take here (join/mic-toggle/dispose), not
 * from server-driven room events. onSpeakersChange is kept as a parameter
 * for API compatibility with existing call sites but is never invoked.
 */
class VoiceManager(
    private val context: Context,
    private val onStatusChange: (VoiceStatus) -> Unit,
    private val onSpeakersChange: (List<String>) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class VoiceStatus { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null

    fun joinVoice(url: String, token: String) {
        Log.d(TAG, "joinVoice url=$url")
        onStatusChange(VoiceStatus.CONNECTING)
        scope.launch {
            try {
                val connected = LiveKit.create(context.applicationContext, RoomOptions())
                room = connected
                connected.connect(url, token, ConnectOptions())
                Log.d(TAG, "joinVoice connected")
                onStatusChange(VoiceStatus.CONNECTED)
            } catch (e: Exception) {
                Log.w(TAG, "joinVoice failed: ${e.message}", e)
                onError(e.message ?: "voice connect failed")
                onStatusChange(VoiceStatus.DISCONNECTED)
            }
        }
    }

    fun setMicOn(on: Boolean) {
        Log.d(TAG, "setMicOn($on)")
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(on)
            } catch (e: Exception) {
                Log.w(TAG, "setMicOn failed: ${e.message}", e)
                onError(e.message ?: "mic toggle failed")
            }
        }
    }

    fun dispose() {
        Log.d(TAG, "dispose()")
        room?.disconnect()
        room = null
        onStatusChange(VoiceStatus.DISCONNECTED)
        scope.cancel()
    }
}
