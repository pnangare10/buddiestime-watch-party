package com.buddiestime.watchparty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

private const val TAG = "HWP-WELCOME"
private const val PREFS = "hwp_prefs"

/**
 * First-run onboarding for whichever device sets the room up first ("owner"):
 * own profile → partner draft → room name (reprompt on collision) → invite link
 * → share sheet → RoomsHomeActivity.
 */
class WelcomeSetupActivity : AppCompatActivity() {
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var profileStore: ProfileStore
    private lateinit var api: PairingApi

    private lateinit var etYourName: TextInputEditText
    private lateinit var etYourPetName: TextInputEditText
    private lateinit var etYourBirthday: TextInputEditText
    private lateinit var tilYourBirthday: TextInputLayout
    private lateinit var etPartnerName: TextInputEditText
    private lateinit var etPartnerPetName: TextInputEditText
    private lateinit var tilRoomName: TextInputLayout
    private lateinit var etRoomName: TextInputEditText
    private lateinit var etPin: TextInputEditText
    private lateinit var btnCreateRoom: MaterialButton
    private lateinit var progress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        setContentView(R.layout.activity_welcome_setup)

        // RoomsHomeActivity finishes and lands here whenever the device isn't paired yet, so
        // without this an unpaired phone — the state a fresh install is in — would never
        // check for updates at all.
        UpdatePrompt.checkAndOffer(this, Config.baseHttpUrl())

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceIdentity = DeviceIdentity(prefs)
        profileStore = ProfileStore(prefs)
        api = PairingApi(Config.baseHttpUrl())

        etYourName = findViewById(R.id.etYourName)
        etYourPetName = findViewById(R.id.etYourPetName)
        etYourBirthday = findViewById(R.id.etYourBirthday)
        tilYourBirthday = findViewById(R.id.tilYourBirthday)
        BirthdayInput.attach(etYourBirthday) { tilYourBirthday.error = null }
        etPartnerName = findViewById(R.id.etPartnerName)
        etPartnerPetName = findViewById(R.id.etPartnerPetName)
        tilRoomName = findViewById(R.id.tilRoomName)
        etRoomName = findViewById(R.id.etRoomName)
        etPin = findViewById(R.id.etPin)
        btnCreateRoom = findViewById(R.id.btnCreateRoom)
        progress = findViewById(R.id.progress)

        Log.d(TAG, "onCreate — hasDevice=${deviceIdentity.hasDevice()} hasRoom=${deviceIdentity.hasRoom()}")
        ensureDevice()

        btnCreateRoom.setOnClickListener { onCreateRoomClicked() }
    }

    private fun ensureDevice() {
        if (deviceIdentity.hasDevice()) {
            Log.d(TAG, "ensureDevice: already have deviceId=${deviceIdentity.localDeviceId()}")
            return
        }
        Log.d(TAG, "ensureDevice: no local deviceId — requesting one")
        api.createDevice { deviceId ->
            runOnUiThread {
                if (deviceId == null) {
                    Log.w(TAG, "ensureDevice: createDevice failed")
                    Toast.makeText(this, "Couldn't reach the server — check your connection and try again", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Log.d(TAG, "ensureDevice: created deviceId=$deviceId")
                deviceIdentity.store(deviceId)
            }
        }
    }

    private fun onCreateRoomClicked() {
        val deviceId = deviceIdentity.localDeviceId()
        if (deviceId == null) {
            Log.w(TAG, "onCreateRoomClicked: no deviceId yet — retrying ensureDevice")
            Toast.makeText(this, "Still setting up — try again in a moment", Toast.LENGTH_SHORT).show()
            ensureDevice()
            return
        }

        val yourName = etYourName.text?.toString()?.trim().orEmpty().capitalizeFirst()
        val roomName = etRoomName.text?.toString()?.trim().orEmpty()
        if (yourName.isEmpty()) {
            Toast.makeText(this, "Enter your name first 💕", Toast.LENGTH_SHORT).show()
            return
        }
        if (roomName.isEmpty()) {
            tilRoomName.error = "Pick a name for your room"
            return
        }
        tilRoomName.error = null

        // Birthday is optional, but a half-typed one is not — dropping it silently would
        // leave someone believing they'd saved a date the room never got.
        val birthday = etYourBirthday.text?.toString()?.trim().orEmpty()
        if (birthday.isNotEmpty() && !BirthdayInput.isValid(birthday)) {
            tilYourBirthday.error = "Use ${BirthdayInput.PATTERN}"
            return
        }
        tilYourBirthday.error = null

        val ownerProfile = JSONObject().apply {
            put("displayName", yourName)
            etYourPetName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("petName", it.capitalizeFirst()) }
            birthday.takeIf { it.isNotEmpty() }?.let { put("birthday", it) }
        }
        val partnerDraft = JSONObject().apply {
            etPartnerName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("displayName", it.capitalizeFirst()) }
            etPartnerPetName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("petName", it.capitalizeFirst()) }
        }
        val pin = etPin.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        Log.d(TAG, "onCreateRoomClicked: deviceId=$deviceId roomName=\"$roomName\" owner=$ownerProfile partnerDraft=$partnerDraft pinSet=${pin != null}")
        setBusy(true)
        api.createRoom(deviceId, roomName, ownerProfile, partnerDraft) { roomId, error ->
            runOnUiThread {
                setBusy(false)
                if (roomId == null) {
                    Log.w(TAG, "createRoom failed: $error")
                    if (error == "room-name-taken") {
                        tilRoomName.error = "That name's taken — try another"
                    } else {
                        Toast.makeText(this, "Couldn't create the room — try again", Toast.LENGTH_LONG).show()
                    }
                    return@runOnUiThread
                }
                Log.d(TAG, "createRoom → roomId=$roomId")
                deviceIdentity.storeRoomId(roomId)
                profileStore.storeSelf(parseProfile(ownerProfile))
                mintInviteAndShare(roomId, deviceId, pin)
            }
        }
    }

    private fun mintInviteAndShare(roomId: String, deviceId: String, pin: String?) {
        setBusy(true)
        InviteSharing.mintAndShare(this, api, roomId, deviceId, pin) { error ->
            runOnUiThread {
                setBusy(false)
                if (error != null) {
                    Log.w(TAG, "mintInvite failed: $error")
                    Toast.makeText(this, "Room created, but the invite link failed — you can re-invite from Settings", Toast.LENGTH_LONG).show()
                }
                goToHome()
            }
        }
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
        btnCreateRoom.isEnabled = !busy
    }
}
