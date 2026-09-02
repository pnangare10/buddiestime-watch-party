package com.buddiestime.watchparty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

private const val TAG = "HWP-REDEEM"
private const val PREFS = "hwp_prefs"

/**
 * Deep-link target for https://<server>/pair/{roomId}/{token} — the partner-side
 * half of pairing. Redeems the invite, prompting for a PIN if the room has one,
 * then routes into RoomsHomeActivity fully configured with no further prompts.
 */
class PairingRedeemActivity : AppCompatActivity() {
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var profileStore: ProfileStore
    private lateinit var api: PairingApi

    private lateinit var tvStatus: TextView
    private lateinit var progress: View
    private lateinit var tilPin: TextInputLayout
    private lateinit var etPin: TextInputEditText
    private lateinit var btnSubmitPin: MaterialButton

    private var roomId: String? = null
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        setContentView(R.layout.activity_pairing_redeem)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceIdentity = DeviceIdentity(prefs)
        profileStore = ProfileStore(prefs)
        api = PairingApi(Config.baseHttpUrl())

        tvStatus = findViewById(R.id.tvStatus)
        progress = findViewById(R.id.progress)
        tilPin = findViewById(R.id.tilPin)
        etPin = findViewById(R.id.etPin)
        btnSubmitPin = findViewById(R.id.btnSubmitPin)

        val data = intent.data
        // Path is /pair/{roomId}/{token}
        val segments = data?.pathSegments ?: emptyList()
        roomId = segments.getOrNull(1)
        token = segments.getOrNull(2)
        Log.d(TAG, "onCreate — data=$data roomId=$roomId token=$token")

        if (roomId.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "onCreate: missing roomId/token in link — aborting")
            showError("This invite link looks broken. Ask your partner to send a new one.")
            return
        }

        btnSubmitPin.setOnClickListener { attemptRedeem(etPin.text?.toString()?.trim()) }

        ensureDeviceThenRedeem()
    }

    private fun ensureDeviceThenRedeem() {
        if (deviceIdentity.hasDevice()) {
            Log.d(TAG, "ensureDeviceThenRedeem: already have deviceId=${deviceIdentity.localDeviceId()}")
            attemptRedeem(null)
            return
        }
        Log.d(TAG, "ensureDeviceThenRedeem: no local deviceId — requesting one")
        api.createDevice { deviceId ->
            runOnUiThread {
                if (deviceId == null) {
                    Log.w(TAG, "ensureDeviceThenRedeem: createDevice failed")
                    showError("Couldn't reach the server — check your connection and try again.")
                    return@runOnUiThread
                }
                deviceIdentity.store(deviceId)
                attemptRedeem(null)
            }
        }
    }

    private fun attemptRedeem(pin: String?) {
        val rId = roomId ?: return
        val tok = token ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "attemptRedeem roomId=$rId deviceId=$deviceId pinProvided=${pin != null}")
        setBusy(true)
        api.joinRoom(rId, deviceId, tok, pin) { herProfile, hisProfile, error ->
            runOnUiThread {
                setBusy(false)
                if (error == null && herProfile != null) {
                    Log.d(TAG, "attemptRedeem success — self=$herProfile partner=$hisProfile")
                    deviceIdentity.storeRoomId(rId)
                    profileStore.storeSelf(herProfile)
                    hisProfile?.let { profileStore.storePartner(it) }
                    // Register the push token now that a deviceId and room exist. On a
                    // fresh install onNewToken has usually already fired and been dropped,
                    // so without this the device stays unreachable by nudges.
                    FcmTokenRegistrar.register(deviceId, api)
                    goToHome()
                    return@runOnUiThread
                }
                Log.w(TAG, "attemptRedeem failed reason=$error")
                when (error) {
                    "pin-mismatch" -> {
                        if (tilPin.visibility != View.VISIBLE) {
                            showPinPrompt("This room needs a PIN — ask your partner for it.")
                        } else {
                            showPinPrompt("Wrong PIN — try again.")
                        }
                    }
                    "already-used" -> showError("This invite link has already been used. Ask your partner to send a new one.")
                    "device-already-in-room" -> showError("This device is already paired with a different room.")
                    else -> showError("Couldn't join — check your connection and try again.")
                }
            }
        }
    }

    private fun showPinPrompt(message: String) {
        tvStatus.text = message
        tilPin.visibility = View.VISIBLE
        btnSubmitPin.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        tvStatus.text = message
        tilPin.visibility = View.GONE
        btnSubmitPin.visibility = View.GONE
    }

    private fun goToHome() {
        requestNotificationPermissionIfNeeded()
        startActivity(Intent(this, RoomsHomeActivity::class.java))
        finish()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "requestNotificationPermissionIfNeeded: granted=$granted")
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnSubmitPin.isEnabled = !busy
    }
}
