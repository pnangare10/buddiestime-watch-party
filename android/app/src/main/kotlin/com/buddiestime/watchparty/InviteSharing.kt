package com.buddiestime.watchparty

import android.app.Activity
import android.content.Intent

/**
 * Mints a fresh invite token for [roomId] and opens the Android share sheet with the link.
 * Minting a new invite invalidates any previously unredeemed one for the room (server-side
 * behavior in pairing.js mintInvite). [onDone] is called exactly once: with `null` after the
 * share sheet has been launched, or with the failure reason if minting failed.
 */
object InviteSharing {
    fun mintAndShare(
        activity: Activity,
        api: PairingApi,
        roomId: String,
        deviceId: String,
        pin: String?,
        onDone: (error: String?) -> Unit,
    ) {
        api.mintInvite(roomId, deviceId, pin) { token, error ->
            activity.runOnUiThread {
                if (token == null) {
                    onDone(error)
                } else {
                    val link = "${Config.baseHttpUrl()}/pair/$roomId/$token"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join our watch party 💗 $link")
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "Send the invite"))
                    onDone(null)
                }
            }
        }
    }
}
