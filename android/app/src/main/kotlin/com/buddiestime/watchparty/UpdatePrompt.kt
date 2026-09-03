package com.buddiestime.watchparty

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.Toast

private const val TAG = "HWP-OTA"

/**
 * The user-facing half of OTA updates: one call from an Activity's onCreate.
 *
 * Optional updates are a dismissible dialog. Forced updates (the server's `min-supported`
 * exceeds the installed build) are non-cancelable, because the point of forcing is protocol
 * drift — two clients speaking different versions of the WebSocket protocol is precisely when
 * letting someone keep using the old build makes things worse.
 *
 * The wall is not absolute. After repeated install failures it degrades to a dismissible
 * warning, so a broken release cannot lock both phones out of the app with no action that can
 * succeed.
 */
object UpdatePrompt {

    fun checkAndOffer(activity: Activity, baseHttpUrl: String) {
        UpdateManager.check(activity, baseHttpUrl) { info ->
            if (info == null) return@check
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                show(activity, info)
            }
        }
    }

    private fun show(activity: Activity, info: UpdateInfo) {
        val installed = UpdateManager.installedVersionCode(activity)
        val forced = info.forces(installed) && !UpdateManager.escapeHatchUnlocked()

        val message = buildString {
            append("Version ${info.versionName} is available.")
            if (info.notes.isNotBlank()) append("\n\n${info.notes}")
            if (forced) append("\n\nThis update is required to keep watching together.")
            if (UpdateManager.escapeHatchUnlocked()) {
                append("\n\nThis update has failed ${UpdateManager.failureCount} times. ")
                append("You can keep using the app, but sync may not work.")
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (forced) "Update required" else "Update available")
            .setMessage(message)
            .setCancelable(!forced)
            .setPositiveButton("Update") { _, _ -> startUpdate(activity, info) }

        if (!forced) builder.setNegativeButton("Later", null)

        UpdateManager.onInstallResultListener = { ok, error ->
            activity.runOnUiThread {
                if (!ok && !activity.isFinishing) {
                    Toast.makeText(activity, error ?: "Update failed", Toast.LENGTH_LONG).show()
                    // Re-offer, so a forced update stays in front of the user — and so the
                    // escape hatch can unlock once failures accumulate.
                    show(activity, info)
                }
            }
        }

        builder.show()
    }

    private fun startUpdate(activity: Activity, info: UpdateInfo) {
        // Re-checked every time on purpose: the install permission is revocable.
        if (!UpdateManager.canInstall(activity)) {
            Log.d(TAG, "install permission missing — sending user to settings")
            Toast.makeText(
                activity,
                "Allow Fluffles to install apps, then tap Update again.",
                Toast.LENGTH_LONG,
            ).show()
            try {
                activity.startActivity(UpdateManager.unknownSourcesSettingsIntent(activity))
            } catch (e: Exception) {
                Log.w(TAG, "could not open unknown-sources settings: ${e.message}")
            }
            return
        }

        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        UpdateManager.downloadAndInstall(activity, info) { error ->
            activity.runOnUiThread {
                if (!activity.isFinishing) Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
            }
            UpdateManager.onInstallResult(false, error)
        }
    }
}
