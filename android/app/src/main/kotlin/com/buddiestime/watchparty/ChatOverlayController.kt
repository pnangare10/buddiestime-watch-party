package com.buddiestime.watchparty

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView

private const val AMBIENT_FADE_MS = 5_000L
private const val MAX_HISTORY = 200

/**
 * Owns the transparent chat overlay: an ambient last-message bubble that auto-fades,
 * and an expandable last-5 scrollable history with a transparent input.
 * Pure messaging stays in WatchPartyManager — this only renders + composes.
 */
class ChatOverlayController(
    private val overlayRoot: LinearLayout,
    private val ambientView: TextView,
    private val peersView: TextView,
    private val scrollView: NestedScrollView,
    private val messagesContainer: LinearLayout,
    private val inputRow: View,
    private val inputField: EditText,
    private val ownDisplayName: () -> String,
    /** Returns true if the message was accepted for sending (socket open). */
    private val onSend: (String) -> Boolean,
    /** Called when a new (non-echo) message arrives while collapsed. */
    private val onUnread: () -> Unit,
    /** Called whenever expanded state changes. */
    private val onExpandedChanged: (Boolean) -> Unit,
) {
    private val history = mutableListOf<ChatMessage>()
    private val echoTracker = OutgoingEchoTracker()
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable { ambientView.visibility = View.GONE }

    var isExpanded = false
        private set

    init {
        collapseToAmbient(notify = false)
        setupSend()
    }

    fun onIncoming(m: ChatMessage) {
        val now = System.currentTimeMillis()
        val isOwnEcho = m.name.trim() == ownDisplayName().trim() &&
            echoTracker.consumeEcho(m.text, now)
        if (isOwnEcho) return  // already rendered optimistically on send
        addToHistory(m)
        if (isExpanded) {
            scrollToBottom()
        } else {
            showAmbient(m)
            onUnread()
        }
    }

    fun toggle() {
        if (isExpanded) collapseAndHideKeyboard() else expand()
    }

    fun setPeers(participants: List<Participant>) {
        peersView.text = when {
            participants.isEmpty() -> "waiting for your favorite person…"
            participants.size == 2 -> "just the two of us 💗"
            else -> "${participants.size} in party"
        }
    }

    fun bringToFront() {
        overlayRoot.bringToFront()
        (overlayRoot.parent as? View)?.requestLayout()
    }

    fun reset() {
        history.clear()
        messagesContainer.removeAllViews()
        fadeHandler.removeCallbacks(fadeRunnable)
        collapseToAmbient(notify = false)
        overlayRoot.visibility = View.GONE
    }

    fun show() { overlayRoot.visibility = View.VISIBLE }

    // ── internals ────────────────────────────────────────────────────────────

    private fun setupSend() {
        inputField.setOnEditorActionListener { _, actionId, event ->
            val isSendAction = actionId == EditorInfo.IME_ACTION_SEND
            val isHardwareEnter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            if (isSendAction || isHardwareEnter) { submit(); true } else false
        }
    }

    private fun submit() {
        val text = inputField.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val accepted = onSend(text)
        if (!accepted) {
            inputField.error = "Not connected"   // keep draft, do not collapse
            return
        }
        val msg = ChatMessage(from = "self", name = ownDisplayName(), text = text,
            ts = System.currentTimeMillis())
        echoTracker.registerLocal(text, msg.ts)
        addToHistory(msg)
        inputField.setText("")
        collapseAndHideKeyboard()
        showAmbient(msg)
    }

    private fun addToHistory(m: ChatMessage) {
        history.add(m)
        trimHistory(history, MAX_HISTORY)
        addRow(m)
    }

    private fun addRow(m: ChatMessage) {
        val row = TextView(messagesContainer.context).apply {
            text = "${firstNameOf(m.name)}: ${m.text}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
            setBackgroundResource(R.drawable.bg_chat_bubble)
            val d = resources.displayMetrics.density
            val padH = (12 * d).toInt()
            val padV = (7 * d).toInt()
            setPadding(padH, padV, padH, padV)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * d).toInt()
            layoutParams = lp
        }
        if (messagesContainer.childCount >= MAX_HISTORY) messagesContainer.removeViewAt(0)
        messagesContainer.addView(row)
        if (isExpanded) scrollToBottom()
    }

    private fun showAmbient(m: ChatMessage) {
        if (isExpanded) return
        ambientView.text = "${firstNameOf(m.name)}: ${m.text}"
        ambientView.visibility = View.VISIBLE
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, AMBIENT_FADE_MS)
    }

    private fun expand() {
        isExpanded = true
        fadeHandler.removeCallbacks(fadeRunnable)
        ambientView.visibility = View.GONE
        peersView.visibility = View.VISIBLE
        scrollView.visibility = View.VISIBLE
        inputRow.visibility = View.VISIBLE
        onExpandedChanged(true)
        scrollToBottom()
        inputField.requestFocus()
        imm().showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseToAmbient(notify: Boolean) {
        isExpanded = false
        peersView.visibility = View.GONE
        scrollView.visibility = View.GONE
        inputRow.visibility = View.GONE
        ambientView.visibility = View.GONE
        if (notify) onExpandedChanged(false)
    }

    private fun collapseAndHideKeyboard() {
        imm().hideSoftInputFromWindow(inputField.windowToken, 0)
        collapseToAmbient(notify = true)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun imm(): InputMethodManager =
        inputField.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
