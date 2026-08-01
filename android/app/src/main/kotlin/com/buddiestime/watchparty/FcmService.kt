package com.buddiestime.watchparty

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import org.json.JSONObject

private const val TAG = "HWP-FCM"
private const val CHANNEL_ID = "nudge"
private const val PREFS = "hwp_prefs"

class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.d(TAG, "onNewToken: $token")
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = DeviceIdentity(prefs).localDeviceId() ?: run {
            Log.w(TAG, "onNewToken: no local deviceId yet — dropping token, next getRoom/pairing flow will register it")
            return
        }
        PairingApi(Config.baseHttpUrl()).updateProfile(deviceId, JSONObject().put("fcmToken", token)) { ok ->
            Log.d(TAG, "fcmToken update ok=$ok")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val body = message.notification?.body ?: message.data["body"] ?: return
        Log.d(TAG, "onMessageReceived body=\"$body\"")
        ensureChannel()
        val openIntent = Intent(this, RoomsHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Watch Party 💗")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification)
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Watch party nudges", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
