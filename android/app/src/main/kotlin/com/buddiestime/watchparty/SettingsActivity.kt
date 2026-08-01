package com.buddiestime.watchparty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

private const val TAG = "HWP-SETTINGS"
private const val PREFS = "hwp_prefs"
private const val RC_NOTIF = 2

class SettingsActivity : AppCompatActivity() {
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var profileStore: ProfileStore
    private lateinit var api: PairingApi
    private var currentRoom: RoomView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceIdentity = DeviceIdentity(prefs)
        profileStore = ProfileStore(prefs)
        api = PairingApi(Config.baseHttpUrl())

        FlufflesTheme.apply(this, profileStore.cachedTheme())
        setContentView(R.layout.activity_settings)

        wireTheme()
        wireQuietHours()
        wireProfile()
        wireMessagePools()
        wireNotifications()

        findViewById<MaterialButton>(R.id.btnRepair).setOnClickListener { repair() }

        loadRoom()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationStatus()
    }

    private fun loadRoom() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "loadRoom roomId=$roomId")
        api.getRoom(roomId, deviceId) { room, error ->
            runOnUiThread {
                if (room == null) {
                    Log.w(TAG, "loadRoom failed: $error")
                    Toast.makeText(this, "Couldn't load room settings — try again", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                currentRoom = room
                populatePairingStatus(room)
                populateTheme(room)
                populateMessagePools(room)
            }
        }
    }

    // ── Pairing status ───────────────────────────────────────────────────────
    private fun populatePairingStatus(room: RoomView) {
        findViewById<TextView>(R.id.tvRoomName).text = "Room: ${room.roomName}"
        findViewById<TextView>(R.id.tvPartnerStatus).text =
            if (room.partnerDeviceId != null) "Partner: paired 💗" else "Partner: waiting for them to join"
    }

    private fun repair() {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "repair roomId=$roomId")
        api.mintInvite(roomId, deviceId, null) { token, error ->
            runOnUiThread {
                if (token == null) {
                    Log.w(TAG, "repair failed: $error")
                    Toast.makeText(this, "Couldn't create a new invite — try again", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val link = "${Config.baseHttpUrl()}/pair/$roomId/$token"
                Log.d(TAG, "repair → link=$link")
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join our watch party 💗 $link")
                }
                startActivity(Intent.createChooser(shareIntent, "Send the invite"))
            }
        }
    }

    // ── Theme ─────────────────────────────────────────────────────────────────
    private fun wireTheme() {
        val cbAuto = findViewById<CheckBox>(R.id.cbAutoTheme)
        val rg = findViewById<RadioGroup>(R.id.rgThemePicker)
        cbAuto.setOnCheckedChangeListener { _, checked ->
            rg.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked) setTheme(mode = "auto", value = null)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioBlush -> "blush"
                R.id.radioLavender -> "lavender"
                R.id.radioPeach -> "peach"
                else -> return@setOnCheckedChangeListener
            }
            setTheme(mode = "manual", value = value)
        }
    }

    private fun populateTheme(room: RoomView) {
        val cbAuto = findViewById<CheckBox>(R.id.cbAutoTheme)
        val rg = findViewById<RadioGroup>(R.id.rgThemePicker)
        val isAuto = room.theme.mode == "auto"
        cbAuto.isChecked = isAuto
        rg.visibility = if (isAuto) View.GONE else View.VISIBLE
        val radioId = when (room.theme.value) {
            "lavender" -> R.id.radioLavender
            "peach" -> R.id.radioPeach
            else -> R.id.radioBlush
        }
        findViewById<RadioButton>(radioId).isChecked = true
    }

    private fun setTheme(mode: String, value: String?) {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "setTheme mode=$mode value=$value")
        api.setTheme(roomId, deviceId, mode, value) { ok ->
            Log.d(TAG, "setTheme result ok=$ok")
        }
    }

    // ── Quiet hours ───────────────────────────────────────────────────────────
    private fun wireQuietHours() {
        findViewById<MaterialButton>(R.id.btnSaveQuietHours).setOnClickListener {
            val start = findViewById<TextInputEditText>(R.id.etQuietStart).text?.toString()?.toIntOrNull()
            val end = findViewById<TextInputEditText>(R.id.etQuietEnd).text?.toString()?.toIntOrNull()
            if (start == null || end == null || start !in 0..23 || end !in 0..23) {
                Toast.makeText(this, "Enter hours between 0 and 23", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val deviceId = deviceIdentity.localDeviceId() ?: return@setOnClickListener
            val quietHours = JSONObject().put("startHour", start).put("endHour", end)
            Log.d(TAG, "saveQuietHours start=$start end=$end")
            api.updateProfile(deviceId, JSONObject().put("quietHours", quietHours)) { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Quiet hours saved" else "Couldn't save — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Own profile ───────────────────────────────────────────────────────────
    private fun wireProfile() {
        val self = profileStore.selfProfile()
        self?.let {
            findViewById<TextInputEditText>(R.id.etProfileName).setText(it.displayName)
            findViewById<TextInputEditText>(R.id.etProfilePetName).setText(it.petName)
            findViewById<TextInputEditText>(R.id.etProfileTimezone).setText(it.timezone)
            findViewById<TextInputEditText>(R.id.etProfileBirthday).setText(it.birthday)
        }
        findViewById<MaterialButton>(R.id.btnSaveProfile).setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        val deviceId = deviceIdentity.localDeviceId() ?: return
        val name = findViewById<TextInputEditText>(R.id.etProfileName).text?.toString()?.trim().orEmpty().capitalizeFirst()
        if (name.isEmpty()) {
            Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = JSONObject().apply {
            put("displayName", name)
            findViewById<TextInputEditText>(R.id.etProfilePetName).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("petName", it.capitalizeFirst()) }
            findViewById<TextInputEditText>(R.id.etProfileTimezone).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("timezone", it) }
            findViewById<TextInputEditText>(R.id.etProfileBirthday).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("birthday", it) }
        }
        Log.d(TAG, "saveProfile profile=$profile")
        api.updateProfile(deviceId, JSONObject().put("profile", profile)) { ok ->
            runOnUiThread {
                if (ok) profileStore.storeSelf(parseProfile(profile))
                Toast.makeText(this, if (ok) "Profile saved" else "Couldn't save — try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Message pools (welcome / nudge) ─────────────────────────────────────
    private fun wireMessagePools() {
        findViewById<MaterialButton>(R.id.btnAddWelcomeMessage).setOnClickListener { addMessage("welcome") }
        findViewById<MaterialButton>(R.id.btnAddNudgeMessage).setOnClickListener { addMessage("nudge") }
    }

    private fun addMessage(pool: String) {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        val field = if (pool == "welcome") R.id.etNewWelcomeMessage else R.id.etNewNudgeMessage
        val et = findViewById<TextInputEditText>(field)
        val text = et.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        Log.d(TAG, "addMessage pool=$pool text=\"$text\"")
        api.addMessage(roomId, deviceId, pool, text) { ok ->
            runOnUiThread {
                if (ok) { et.setText(""); loadRoom() }
                else Toast.makeText(this, "Couldn't add — try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeMessage(pool: String, id: String) {
        val roomId = deviceIdentity.localRoomId() ?: return
        val deviceId = deviceIdentity.localDeviceId() ?: return
        Log.d(TAG, "removeMessage pool=$pool id=$id")
        api.removeMessage(roomId, deviceId, pool, id) { ok ->
            runOnUiThread {
                if (ok) loadRoom()
                else Toast.makeText(this, "Couldn't remove — try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populateMessagePools(room: RoomView) {
        val deviceId = deviceIdentity.localDeviceId()
        populateMessageList(R.id.llWelcomeMessages, room.welcomeMessages, "welcome", deviceId)
        populateMessageList(R.id.llNudgeMessages, room.nudgeMessages, "nudge", deviceId)
    }

    private fun populateMessageList(containerId: Int, messages: List<MessageEntry>, pool: String, deviceId: String?) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        for (m in messages) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4
                }
            }
            val label = if (m.authorDeviceId == deviceId) "you" else "partner"
            val tv = TextView(this).apply {
                text = "\"${m.text}\" — $label"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.cream))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = TextView(this).apply {
                text = "✕"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.lavender_mist))
                setPadding(24, 8, 8, 8)
                setOnClickListener { removeMessage(pool, m.id) }
            }
            row.addView(tv)
            row.addView(remove)
            container.addView(row)
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────
    private fun wireNotifications() {
        findViewById<MaterialButton>(R.id.btnRequestNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF)
            }
        }
    }

    private fun updateNotificationStatus() {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "updateNotificationStatus granted=$granted")
        findViewById<TextView>(R.id.tvNotifStatus).text = if (granted) "Enabled ✅" else "Disabled — nudges won't show"
        findViewById<MaterialButton>(R.id.btnRequestNotif).visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_NOTIF) updateNotificationStatus()
    }
}
