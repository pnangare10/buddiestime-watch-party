package com.buddiestime.watchparty

import android.content.Context
import android.util.Log
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG = "HWP-VOICE"

/**
 * Thin wrapper around the LiveKit Android client for the watch-party voice
 * room. One instance per MainActivity session; disposed on leave/destroy.
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
                connected.events.collect { event ->
                    when (event) {
                        is RoomEvent.Disconnected -> {
                            Log.d(TAG, "RoomEvent.Disconnected")
                            onStatusChange(VoiceStatus.DISCONNECTED)
                        }
                        is RoomEvent.ActiveSpeakersChanged -> {
                            val ids = event.speakers.map { it.identity?.toString() ?: it.sid.toString() }
                            Log.d(TAG, "RoomEvent.ActiveSpeakersChanged ids=$ids")
                            onSpeakersChange(ids)
                        }
                        else -> {}
                    }
                }
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
        scope.cancel()
    }
}
