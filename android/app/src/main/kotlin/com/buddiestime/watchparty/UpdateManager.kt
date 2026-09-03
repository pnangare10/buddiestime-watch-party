package com.buddiestime.watchparty

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "HWP-OTA"

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val minSupported: Long,
    val notes: String,
) {
    /** True when the server says this build is too old to keep running. */
    fun forces(installed: Long) = minSupported > installed
}

/**
 * Checks the server for a newer build, downloads it, and hands it to the system installer.
 *
 * Two deliberate departures from the original design, both to remove failure modes:
 *
 * - **PackageInstaller session API, not an install Intent.** ACTION_VIEW/ACTION_INSTALL_PACKAGE
 *   reports no result, so the app cannot tell "user cancelled" from "signature mismatch" from
 *   "downgrade blocked" — which is exactly what the forced-update escape hatch needs to know.
 * - **No FileProvider and no external storage.** The session API streams bytes straight from
 *   our own private file, so there is no content:// authority to misconfigure (this repo's
 *   namespace and applicationId differ, which makes that an easy mistake) and no window where
 *   another app could swap the downloaded APK before install.
 *
 * Android verifies the signature at install time, so an APK signed with a different key simply
 * fails to install. That is the integrity guarantee — not anything checked here.
 */
object UpdateManager {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Set once a forced update is pending, so other entry points can suppress auto-join. */
    @Volatile
    var forcedUpdatePending: Boolean = false
        private set

    fun installedVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        // BuildConfig.VERSION_CODE is not available here: AGP 8 doesn't generate BuildConfig
        // unless buildFeatures.buildConfig is enabled. PackageManager is the robust source.
        Log.w(TAG, "installedVersionCode: ${e.message}")
        0L
    }

    /**
     * Asks the server what the latest build is. Never reports an error to the caller: a failed
     * update check is indistinguishable from "no update", and must never block app start.
     */
    fun check(context: Context, baseHttpUrl: String, cb: (UpdateInfo?) -> Unit) {
        val req = Request.Builder().url(baseHttpUrl.trimEnd('/') + "/api/app-version").get().build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.d(TAG, "check: unreachable (${e.message}) — treating as no update")
                cb(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val info = try {
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!json.optBoolean("ok")) {
                        Log.d(TAG, "check: ${json.optString("reason", "not-ok")}")
                        null
                    } else {
                        UpdateInfo(
                            versionCode = json.optLong("versionCode"),
                            versionName = json.optString("versionName"),
                            url = json.optString("url"),
                            minSupported = json.optLong("minSupported", 0L),
                            notes = json.optString("notes"),
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "check: malformed manifest — ${e.message}")
                    null
                }
                val installed = installedVersionCode(context)
                if (info == null || info.versionCode <= installed || info.url.isEmpty()) {
                    Log.d(TAG, "check: up to date (installed=$installed)")
                    cb(null)
                    return
                }
                forcedUpdatePending = info.forces(installed)
                Log.d(TAG, "check: update ${info.versionCode} available (forced=$forcedUpdatePending)")
                cb(info)
            }
        })
    }

    /**
     * True when the system will let us install. This is re-checked before every attempt on
     * purpose: the permission is not a one-time grant — Android 11+ revokes it under app
     * hibernation, so a device that could update months ago may not be able to today.
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads the APK into app-private storage and commits an install session. Progress and
     * the final outcome arrive via UpdateInstallReceiver; `onError` fires only for failures
     * that happen before the system takes over.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo, onError: (String) -> Unit) {
        Thread {
            val target = File(context.cacheDir, "update-${info.versionCode}.apk")
            try {
                // A previous run may have left a truncated file behind; a partial APK would
                // otherwise be treated as cached and fail to install forever.
                if (target.exists() && !isProbablyComplete(target)) {
                    Log.d(TAG, "discarding incomplete cached APK")
                    target.delete()
                }
                if (!target.exists()) {
                    val req = Request.Builder().url(info.url).get().build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw IllegalStateException("download HTTP ${res.code}")
                        val body = res.body ?: throw IllegalStateException("empty download body")
                        target.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                }
                commitSession(context, target)
            } catch (e: Exception) {
                Log.w(TAG, "downloadAndInstall failed: ${e.message}")
                target.delete()
                onError(e.message ?: "update-failed")
            }
        }.start()
    }

    /** A ZIP (and so an APK) ends with the end-of-central-directory signature. */
    private fun isProbablyComplete(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { true }
    } catch (e: Exception) {
        false
    }

    private fun commitSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("fluffles.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
            Log.d(TAG, "install session $sessionId committed")
        }
    }

    fun clearForcedFlag() {
        forcedUpdatePending = false
        failedAttempts = 0
    }

    @Volatile
    private var failedAttempts = 0

    /** Set by whichever screen is showing the update prompt, so it can react to the outcome. */
    @Volatile
    var onInstallResultListener: ((Boolean, String?) -> Unit)? = null

    fun onInstallResult(ok: Boolean, error: String?) {
        failedAttempts = if (ok) 0 else failedAttempts + 1
        onInstallResultListener?.invoke(ok, error)
    }

    /**
     * After repeated install failures a forced update stops being a wall and becomes a
     * dismissible warning. Otherwise a bad release — a missing asset, a key mismatch, a full
     * disk — locks the user out of the app with no action that can succeed, and the only
     * recovery is someone editing the GitHub release from a laptop.
     */
    fun escapeHatchUnlocked(): Boolean = failedAttempts >= 3

    val failureCount: Int get() = failedAttempts

    /** PackageManager status codes are opaque ints; this is for logs and user-facing text. */
    fun describeStatus(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Update conflicts with the installed app"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build isn't compatible with your device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded update was invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
        // A version downgrade and a signature mismatch both arrive as STATUS_FAILURE_INVALID
        // with detail in the message; INSTALL_FAILED_* constants are hidden API, so the
        // message is the only public source for the distinction.
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "The system blocked this install"
        else -> message ?: "Update failed"
    }
}
