package com.buddiestime.watchparty

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

/**
 * Pushes the current FCM token to the server whenever a deviceId exists.
 *
 * FcmService.onNewToken used to be the only path that ever registered a token, and it
 * drops the token outright when no local deviceId is stored yet. That callback fires
 * once per install — typically before the user has finished pairing — so whether the
 * server ever learned the token came down to whether Firebase happened to mint it
 * before or after setup completed. When it lost that race the token stayed null
 * forever, and nudges failed silently: the sender still saw a success.
 *
 * Calling this on app start and right after pairing closes that gap. onNewToken remains
 * the refresh path for when Firebase rotates a token later.
 */
object FcmTokenRegistrar {
    private const val TAG = "HWP-FCM-REG"

    fun register(deviceId: String, api: PairingApi) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNullOrEmpty()) {
                    Log.w(TAG, "register: Firebase returned an empty token — skipping")
                    return@addOnSuccessListener
                }
                api.updateProfile(deviceId, JSONObject().put("fcmToken", token)) { ok ->
                    Log.d(TAG, "register: fcmToken update ok=$ok")
                }
            }
            .addOnFailureListener { e ->
                // Never fatal: a missing token costs nudges, not the app.
                Log.w(TAG, "register: could not fetch FCM token — ${e.message}")
            }
    }
}
