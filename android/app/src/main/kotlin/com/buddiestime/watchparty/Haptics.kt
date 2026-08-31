package com.buddiestime.watchparty

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private const val TAG = "HWP-HAPTICS"

/** A peer's chat message just landed — short enough not to intrude on a film. */
private const val MESSAGE_BUZZ_MS = 160L

/**
 * Thin wrapper over the three eras of the Android vibrator API: VibratorManager
 * (31+), VibrationEffect (26+), and the raw millisecond call below that.
 * Every entry point is a no-op on hardware without a vibrator, so callers never
 * have to check.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    /** One short pulse. Used for an incoming chat message from the other person. */
    fun messageBuzz() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            Log.d(TAG, "messageBuzz skipped — device has no vibrator")
            return
        }
        Log.d(TAG, "messageBuzz ${MESSAGE_BUZZ_MS}ms")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(
                        MESSAGE_BUZZ_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(MESSAGE_BUZZ_MS)
            }
        } catch (e: Exception) {
            // A vibration is never worth taking the party down for.
            Log.w(TAG, "vibrate failed: ${e.message}")
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        Log.w(TAG, "vibrator unavailable: ${e.message}")
        null
    }
}
