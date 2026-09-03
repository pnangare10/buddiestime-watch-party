package com.buddiestime.watchparty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

private const val TAG = "HWP-OTA"

/**
 * Receives the outcome of a PackageInstaller session committed by UpdateManager.
 *
 * The important case is STATUS_PENDING_USER_ACTION: Android does not install silently for a
 * sideloaded app, so the system hands back an Intent that shows its own confirmation dialog.
 * Launching it is what makes the update actually happen — without this, the session sits
 * pending forever and the update appears to do nothing.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "pending user action with no confirmation intent")
                    return
                }
                // Started from a receiver, so it needs its own task.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "install succeeded")
                UpdateManager.clearForcedFlag()
                UpdateManager.onInstallResult(true, null)
            }

            else -> {
                val described = UpdateManager.describeStatus(status, message)
                Log.w(TAG, "install failed status=$status message=$message")
                UpdateManager.onInstallResult(false, described)
                Toast.makeText(context, described, Toast.LENGTH_LONG).show()
            }
        }
    }
}
