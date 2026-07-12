package com.buddiestime.watchparty

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        store = RecentRoomsStore(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        tvEmpty = findViewById(R.id.tvEmpty)

        val rv = findViewById<RecyclerView>(R.id.rvRecent)
        adapter = RecentAdapter { room -> joinRoom(room.roomId, room.platform, room.videoUrl) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener {
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
            val sub: TextView = v.findViewById(R.id.tvRoomSub)
            val count: TextView = v.findViewById(R.id.tvRoomCount)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent_room, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            val st = statuses[r.roomId]
            h.id.text = r.roomId
            h.sub.text = r.platform.replaceFirstChar { it.uppercase() }
            val active = st?.active == true
            h.dot.setBackgroundColor(if (active) Color.parseColor("#1a9e6e") else Color.parseColor("#555555"))
            h.count.text = if (active) "${st?.count ?: 0} watching" else "inactive"
            h.itemView.setOnClickListener { onClick(r) }
        }
    }
}
