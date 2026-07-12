package com.buddiestime.watchparty

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "HWP-HOME"
private const val PREFS = "hwp_prefs"
private const val SPLASH_DURATION_MS = 3800L  // love-note auto-dismiss; tap skips early

class RoomsHomeActivity : AppCompatActivity() {
    private lateinit var store: RecentRoomsStore
    private lateinit var adapter: RecentAdapter
    private lateinit var tvEmpty: TextView
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    private var all: List<RecentRoom> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlufflesTheme.apply(this)
        setContentView(R.layout.activity_rooms_home)
        if (savedInstanceState == null) {
            Log.d(TAG, "cold start → showing love-note splash")
            showLoveNoteSplash()
        } else {
            Log.d(TAG, "recreation (savedInstanceState present) → skipping splash")
        }
        store = RecentRoomsStore(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        tvEmpty = findViewById(R.id.tvEmpty)

        val rv = findViewById<RecyclerView>(R.id.rvRecent)
        adapter = RecentAdapter { room -> joinRoom(room.roomId, room.platform, room.videoUrl) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<TextView>(R.id.tvGreeting).text = "Hey ${Personalization.HER_NAME} 💗"
        findViewById<TextView>(R.id.tvGreetingSub).text = greetingSubline()

        findViewById<View>(R.id.btnCreate).setOnClickListener {
            Log.d(TAG, "hero card tapped → ServiceSelector")
            startActivity(Intent(this, ServiceSelectorActivity::class.java))
        }
        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)
        findViewById<MaterialButton>(R.id.btnJoin).setOnClickListener { doJoinFromField(etSearch.text?.toString()) }
        etSearch.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { doJoinFromField(etSearch.text?.toString()); true } else false
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun greetingSubline(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val line = when (hour) {
            in 5..11 -> "a whole day of us ahead ☀️"
            in 12..16 -> "sneaky afternoon episode? 👀"
            in 17..21 -> "perfect time for our movie night ✨"
            else -> "one more episode won't hurt 🌙"
        }
        Log.d(TAG, "greetingSubline: hour=$hour → \"$line\"")
        return line
    }

    // ── Love-note splash (cold start only) ──────────────────────────────────
    private fun showLoveNoteSplash() {
        val stub = findViewById<ViewStub>(R.id.stubSplash)
        if (stub == null) { Log.w(TAG, "splash: stub missing — skipping"); return }
        val overlay = stub.inflate()
        val line = FlirtyLines.pick()
        overlay.findViewById<TextView>(R.id.tvFlirtyLine).text = line
        overlay.findViewById<TextView>(R.id.tvSplashSignature).text = "— ${Personalization.HIS_NAME} 💌"

        // Heart pulse — skipped when the user has animations turned off
        val animScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var pulse: ObjectAnimator? = null
        if (animScale > 0f) {
            val heart = overlay.findViewById<TextView>(R.id.tvSplashHeart)
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                heart,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            ).apply {
                duration = 550
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            Log.d(TAG, "splash: animations disabled (scale=$animScale) — static heart")
        }

        var dismissed = false
        fun dismiss(reason: String) {
            if (dismissed) { Log.d(TAG, "splash: dismiss($reason) ignored — already dismissed"); return }
            dismissed = true
            Log.d(TAG, "splash: dismissing ($reason)")
            pulse?.cancel()
            overlay.animate().alpha(0f).setDuration(350).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }

        overlay.setOnClickListener { dismiss("tap-skip") }
        overlay.postDelayed({ dismiss("auto") }, SPLASH_DURATION_MS)
        Log.d(TAG, "splash: shown line=\"$line\" (auto-dismiss in ${SPLASH_DURATION_MS}ms)")
    }

    override fun onResume() {
        super.onResume()
        all = store.all()
        adapter.submit(all)
        tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        refreshStatuses()
    }

    private fun filter(q: String) {
        val f = if (q.isBlank()) all else all.filter { it.roomId.contains(q.trim(), ignoreCase = true) }
        adapter.submit(f)
    }

    private fun doJoinFromField(raw: String?) {
        val code = raw?.trim().orEmpty()
        if (code.isEmpty()) { Toast.makeText(this, "Enter a room code", Toast.LENGTH_SHORT).show(); return }
        val known = all.firstOrNull { it.roomId.equals(code, ignoreCase = true) }
        joinRoom(code, known?.platform ?: "hotstar", known?.videoUrl)
    }

    private fun joinRoom(roomId: String, platform: String, videoUrl: String? = null) {
        Log.d(TAG, "joinRoom roomId=$roomId platform=$platform videoUrl=$videoUrl")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", platform)
            putExtra("roomId", roomId)
            putExtra("join", true)
            if (!videoUrl.isNullOrBlank()) putExtra("hwp_url", videoUrl)
        })
    }

    private fun refreshStatuses() {
        val ids = all.map { it.roomId }
        if (ids.isEmpty()) return
        val url = Config.baseHttpUrl() + "/api/rooms/status?ids=" + ids.joinToString(",")
        Log.d(TAG, "refreshStatuses url=$url")
        http.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { Log.w(TAG, "status fetch failed: ${e.message}") }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: return
                val statuses = try { parseRoomStatusList(body) } catch (e: Exception) { Log.w(TAG, "parse: ${e.message}"); return }
                runOnUiThread { adapter.applyStatuses(statuses.associateBy { it.roomId }) }
            }
        })
    }

    // ── RecyclerView adapter ────────────────────────────────────────────────
    class RecentAdapter(val onClick: (RecentRoom) -> Unit) : RecyclerView.Adapter<RecentAdapter.VH>() {
        private var items: List<RecentRoom> = emptyList()
        private var statuses: Map<String, RoomStatus> = emptyMap()
        fun submit(list: List<RecentRoom>) { items = list; notifyDataSetChanged() }
        fun applyStatuses(m: Map<String, RoomStatus>) { statuses = m; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dotActive)
            val id: TextView = v.findViewById(R.id.tvRoomId)
            val chip: TextView = v.findViewById(R.id.tvServiceChip)
            val sub: TextView = v.findViewById(R.id.tvRoomSub)
            val count: TextView = v.findViewById(R.id.tvRoomCount)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent_room, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            val st = statuses[r.roomId]
            val ctx = h.itemView.context
            h.id.text = r.roomId

            val brand = androidx.core.content.ContextCompat.getColor(ctx, brandColorRes(r.platform))
            h.chip.text = prettyPlatform(r.platform)
            h.chip.setTextColor(brand)
            // 15%-alpha brand wash behind full-brand text (mutate: drawable state is shared across rows)
            h.chip.background.mutate().setTint(Color.argb(0x26, Color.red(brand), Color.green(brand), Color.blue(brand)))

            h.sub.text = android.text.format.DateUtils.getRelativeTimeSpanString(
                r.lastJoined, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS)

            val active = st?.active == true
            val mint = androidx.core.content.ContextCompat.getColor(ctx, R.color.mint_ok)
            val dim = androidx.core.content.ContextCompat.getColor(ctx, R.color.plum_surface_hi)
            h.dot.background.mutate().setTint(if (active) mint else dim)
            h.count.text = if (active) "watching now" else "sleeping"
            h.count.setTextColor(if (active) mint
                else androidx.core.content.ContextCompat.getColor(ctx, R.color.lavender_mist))
            h.itemView.setOnClickListener { onClick(r) }
        }

        private fun brandColorRes(platform: String): Int = when (platform.lowercase()) {
            "hotstar" -> R.color.brand_hotstar
            "netflix" -> R.color.brand_netflix
            "primevideo" -> R.color.brand_prime
            "youtube" -> R.color.brand_youtube
            else -> R.color.accent_blush
        }

        private fun prettyPlatform(platform: String): String = when (platform.lowercase()) {
            "hotstar" -> "Hotstar"
            "netflix" -> "Netflix"
            "primevideo" -> "Prime Video"
            "youtube" -> "YouTube"
            else -> platform.replaceFirstChar { it.uppercase() }
        }
    }
}
