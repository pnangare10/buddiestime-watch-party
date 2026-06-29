# Transparent Floating Chat Overlay — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the modal chat dialog in the Android app with a transparent, video-friendly overlay (ambient last-message + expandable last-5 scrollable history) drawn over the streaming WebView.

**Architecture:** Pure, unit-tested helpers (`ChatOverlayLogic.kt`) handle name extraction, history trimming, and self-echo de-duplication. A `ChatOverlayController` owns all overlay View wiring (ambient bubble, fade timer, expanded panel, input, keyboard, optimistic send). `MainActivity` instantiates the controller and routes existing `WatchPartyManager` callbacks to it. `WatchPartyManager.kt` is NOT modified.

**Tech Stack:** Kotlin, Android (minSdk 24 / targetSdk 34), AndroidX core-ktx (`NestedScrollView`, IME handling), Material Components, JUnit4 for unit tests.

## Global Constraints

- Do NOT modify `WatchPartyManager.kt` — use only `sendChat(text)`, `isConnected()`, and the existing `onChatMessage` / `onParticipantsChange` callbacks.
- `ChatMessage` data class (in `WatchPartyManager.kt`): `ChatMessage(from: String, name: String, text: String, ts: Long)`.
- minSdk 24, targetSdk 34. `MainActivity` already has `windowSoftInputMode="adjustResize"` and `configChanges="orientation|screenSize|keyboardHidden|screenLayout"` (Activity is NOT recreated on rotation — no savedInstanceState restoration needed).
- Constants: `AMBIENT_FADE_MS=5000`, `MAX_VISIBLE_MESSAGES=5`, `MAX_HISTORY=200`, `INPUT_MAX_LENGTH=500`, echo window `10000ms`.
- Text legibility over video: white text + dark shadow, transparent backgrounds. The system soft keyboard is the only thing allowed to obscure the video.
- Package: `com.buddiestime.watchparty`. All paths under `android/app/`.
- Build/test from the `android/` directory. Unit tests: `./gradlew :app:testDebugUnitTest`. Compile: `./gradlew :app:assembleDebug`.

---

### Task 1: Pure overlay logic + unit-test setup

Pure, Android-free helpers with real red-green unit tests. This is the only task with automated tests; later UI tasks are verified on-device (per spec).

**Files:**

- Modify: `android/app/build.gradle` (add JUnit test dependency)
- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayLogic.kt`
- Test: `android/app/src/test/kotlin/com/buddiestime/watchparty/ChatOverlayLogicTest.kt`

**Interfaces:**

- Produces:
  - `fun firstNameOf(name: String): String` — first whitespace-delimited token; `"Guest"` if blank/empty.
  - `fun trimHistory(history: MutableList<ChatMessage>, max: Int)` — drops oldest in place so `size <= max`.
  - `class OutgoingEchoTracker(windowMs: Long = 10_000L)` with `fun registerLocal(text: String, now: Long)` and `fun consumeEcho(text: String, now: Long): Boolean` (true exactly once per matching registered text within the window).

- [ ] **Step 1: Add the JUnit test dependency**

In `android/app/build.gradle`, inside the `dependencies { ... }` block (after the `kotlinx-coroutines-android` line), add:

```groovy
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/kotlin/com/buddiestime/watchparty/ChatOverlayLogicTest.kt`:

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOverlayLogicTest {

    @Test fun firstName_multiWord_returnsFirstToken() {
        assertEquals("Aman", firstNameOf("Aman Kumar"))
    }

    @Test fun firstName_singleWord_returnsItself() {
        assertEquals("Priya", firstNameOf("Priya"))
    }

    @Test fun firstName_extraWhitespace_trimsAndSplits() {
        assertEquals("Sam", firstNameOf("   Sam   Lee  "))
    }

    @Test fun firstName_blank_returnsGuest() {
        assertEquals("Guest", firstNameOf("   "))
        assertEquals("Guest", firstNameOf(""))
    }

    @Test fun firstName_emojiNoSpace_returnsEmoji() {
        assertEquals("🙂", firstNameOf("🙂"))
    }

    @Test fun trimHistory_overCap_dropsOldest() {
        val list = (1..5).map { ChatMessage("u$it", "U$it", "m$it", it.toLong()) }.toMutableList()
        trimHistory(list, 3)
        assertEquals(3, list.size)
        assertEquals("m3", list.first().text)
        assertEquals("m5", list.last().text)
    }

    @Test fun trimHistory_underCap_noChange() {
        val list = (1..2).map { ChatMessage("u$it", "U$it", "m$it", it.toLong()) }.toMutableList()
        trimHistory(list, 3)
        assertEquals(2, list.size)
    }

    @Test fun echo_registeredThenMatched_consumedOnce() {
        val t = OutgoingEchoTracker()
        t.registerLocal("hello", 1_000L)
        assertTrue(t.consumeEcho("hello", 1_200L))
        assertFalse(t.consumeEcho("hello", 1_300L))  // only once
    }

    @Test fun echo_neverRegistered_notConsumed() {
        val t = OutgoingEchoTracker()
        assertFalse(t.consumeEcho("hi", 1_000L))
    }

    @Test fun echo_outsideWindow_expires() {
        val t = OutgoingEchoTracker(windowMs = 5_000L)
        t.registerLocal("late", 1_000L)
        assertFalse(t.consumeEcho("late", 7_000L))  // 6s > 5s window
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "com.buddiestime.watchparty.ChatOverlayLogicTest"`
Expected: FAIL — `ChatOverlayLogic.kt` does not exist (`unresolved reference: firstNameOf`).

- [ ] **Step 4: Write the implementation**

Create `android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayLogic.kt`:

```kotlin
package com.buddiestime.watchparty

/** First whitespace-delimited token of a display name, or "Guest" when blank. */
fun firstNameOf(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: "Guest"

/** Drops oldest entries in place so history.size <= max. No-op if already within cap. */
fun trimHistory(history: MutableList<ChatMessage>, max: Int) {
    while (history.size > max) history.removeAt(0)
}

/**
 * Tracks locally-rendered outgoing messages so the server's echo of the user's own
 * message can be de-duplicated (we render optimistically, then swallow the echo).
 */
class OutgoingEchoTracker(private val windowMs: Long = 10_000L) {
    private data class Pending(val text: String, val at: Long)
    private val pending = mutableListOf<Pending>()

    fun registerLocal(text: String, now: Long) {
        pending.add(Pending(text.trim(), now))
    }

    /** Returns true (once) if an outgoing message with this text was registered within the window. */
    fun consumeEcho(text: String, now: Long): Boolean {
        pending.removeAll { now - it.at > windowMs }
        val idx = pending.indexOfFirst { it.text == text.trim() }
        return if (idx >= 0) { pending.removeAt(idx); true } else false
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "com.buddiestime.watchparty.ChatOverlayLogicTest"`
Expected: PASS — all 9 tests green.

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle \
        android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayLogic.kt \
        android/app/src/test/kotlin/com/buddiestime/watchparty/ChatOverlayLogicTest.kt
git commit -m "feat(chat): pure overlay logic (first-name, history trim, echo dedup) + unit tests"
```

---

### Task 2: Transparent overlay layout

Add the transparent overlay views to `activity_main.xml` as the LAST children of the root `FrameLayout` (top of z-order, above WebView and fullscreen container). No Kotlin yet — this task delivers a compiling layout.

**Files:**

- Modify: `android/app/src/main/res/layout/activity_main.xml` (insert overlay block before the closing `</FrameLayout>`, i.e. after `tvChatBadge`)

**Interfaces:**

- Produces these view ids consumed by Task 3: `@id/chatOverlay` (container), `@id/tvOverlayAmbient`, `@id/tvOverlayPeers`, `@id/svOverlay` (NestedScrollView), `@id/llOverlayMessages` (LinearLayout), `@id/rowOverlayInput`, `@id/etOverlayInput` (EditText).

- [ ] **Step 1: Add the overlay block to the layout**

In `android/app/src/main/res/layout/activity_main.xml`, immediately before the closing `</FrameLayout>` (line 88), insert:

```xml
    <!-- Transparent chat overlay (bottom-left). Last child = top of z-order. -->
    <LinearLayout
        android:id="@+id/chatOverlay"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|start"
        android:layout_marginStart="14dp"
        android:layout_marginBottom="16dp"
        android:layout_marginEnd="72dp"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/tvOverlayPeers"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingBottom="6dp"
            android:textColor="#FFFFFF"
            android:textSize="11sp"
            android:shadowColor="#000000"
            android:shadowDx="0"
            android:shadowDy="1"
            android:shadowRadius="3"
            android:visibility="gone" />

        <androidx.core.widget.NestedScrollView
            android:id="@+id/svOverlay"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxHeight="180dp"
            android:visibility="gone">

            <LinearLayout
                android:id="@+id/llOverlayMessages"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical" />
        </androidx.core.widget.NestedScrollView>

        <TextView
            android:id="@+id/tvOverlayAmbient"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:maxWidth="280dp"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:shadowColor="#000000"
            android:shadowDx="0"
            android:shadowDy="1"
            android:shadowRadius="4"
            android:visibility="gone" />

        <LinearLayout
            android:id="@+id/rowOverlayInput"
            android:layout_width="280dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:orientation="horizontal"
            android:visibility="gone">

            <EditText
                android:id="@+id/etOverlayInput"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@android:color/transparent"
                android:hint="Message…"
                android:textColorHint="#BBFFFFFF"
                android:textColor="#FFFFFF"
                android:textSize="14sp"
                android:shadowColor="#000000"
                android:shadowDx="0"
                android:shadowDy="1"
                android:shadowRadius="4"
                android:inputType="text"
                android:maxLength="500"
                android:imeOptions="actionSend"
                android:singleLine="true"
                android:importantForAutofill="no" />
        </LinearLayout>
    </LinearLayout>
```

- [ ] **Step 2: Verify the layout compiles**

Run (from `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no resource-linking errors; new ids resolve).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/layout/activity_main.xml
git commit -m "feat(chat): add transparent chat overlay layout (ambient + expanded panel)"
```

---

### Task 3: ChatOverlayController + wire into MainActivity

Replace the modal dialog with the overlay. This task swaps the chat UX atomically: ambient bubble with fade, expandable last-5 scrollable history, peers count, optimistic send-and-collapse, and `fabChat` toggle. Verified on-device (no unit test — pure View/IME behavior).

**Files:**

- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayController.kt`
- Modify: `android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt`

**Interfaces:**

- Consumes (from Task 1): `firstNameOf`, `trimHistory`, `OutgoingEchoTracker`. (from Task 2): the overlay view ids.
- Produces (consumed by Tasks 4–5): `ChatOverlayController` with public methods:
  - `fun onIncoming(m: ChatMessage)` — render an incoming message (de-dupes own echo).
  - `fun toggle()` — toggle ambient ⇄ expanded.
  - `fun setPeers(participants: List<Participant>)` — update peers count line.
  - `fun bringToFront()` — re-assert z-order (call on fullscreen enter/exit).
  - `fun reset()` — clear history + hide overlay (on leave party).
  - `val isExpanded: Boolean`.

- [ ] **Step 1: Write the controller**

Create `android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayController.kt`:

```kotlin
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
        peersView.text = if (participants.isEmpty()) "(no one else here yet)"
            else "${participants.size} in party"
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
            textSize = 13.5f
            setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        // keep the messages container bounded to MAX_HISTORY rows
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
```

- [ ] **Step 2: Wire the controller into MainActivity — add the field**

In `MainActivity.kt`, in the "Chat state" block (lines 73–80), REPLACE:

```kotlin
    // Chat state
    private val chatHistory = mutableListOf<ChatMessage>()
    private var participants: List<Participant> = emptyList()
    private var chatDialog: AlertDialog? = null
    private var chatMessagesContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var chatPeersView: TextView? = null
    private var unreadChatCount = 0
```

with:

```kotlin
    // Chat state
    private var participants: List<Participant> = emptyList()
    private var unreadChatCount = 0
    private lateinit var chatOverlay: ChatOverlayController
```

- [ ] **Step 3: Instantiate the controller in onCreate**

In `MainActivity.kt onCreate`, immediately after `fullscreenContainer = findViewById(R.id.fullscreenContainer)` (line 230), insert:

```kotlin
        chatOverlay = ChatOverlayController(
            overlayRoot = findViewById(R.id.chatOverlay),
            ambientView = findViewById(R.id.tvOverlayAmbient),
            peersView = findViewById(R.id.tvOverlayPeers),
            scrollView = findViewById(R.id.svOverlay),
            messagesContainer = findViewById(R.id.llOverlayMessages),
            inputRow = findViewById(R.id.rowOverlayInput),
            inputField = findViewById(R.id.etOverlayInput),
            ownDisplayName = { prefs.getString(KEY_NAME, "")?.trim().orEmpty() },
            onSend = { text ->
                val ok = manager?.isConnected() == true
                if (ok) manager?.sendChat(text)
                ok
            },
            onUnread = {
                unreadChatCount++
                tvChatBadge.text = unreadChatCount.toString()
                tvChatBadge.visibility = View.VISIBLE
            },
            onExpandedChanged = { expanded ->
                if (expanded) {
                    unreadChatCount = 0
                    tvChatBadge.visibility = View.GONE
                }
            }
        )
```

- [ ] **Step 4: Repoint fabChat to toggle the overlay**

In `MainActivity.kt onCreate`, REPLACE the `fabChat.setOnClickListener` block (lines 237–240):

```kotlin
        fabChat.setOnClickListener {
            Log.d(TAG, "fabChat click")
            openChatDialog()
        }
```

with:

```kotlin
        fabChat.setOnClickListener {
            Log.d(TAG, "fabChat click → toggle overlay")
            chatOverlay.toggle()
        }
```

- [ ] **Step 5: Route the manager callbacks to the controller**

In `MainActivity.kt connectToParty`, REPLACE the `onChatMessage` lambda (lines 423–432):

```kotlin
            onChatMessage = { m ->
                Log.d(TAG, "onChatMessage from ${m.name}(${m.from}): \"${m.text.take(80)}\"")
                chatHistory.add(m)
                appendChatRow(m)
                if (chatDialog?.isShowing != true) {
                    unreadChatCount++
                    tvChatBadge.text = unreadChatCount.toString()
                    tvChatBadge.visibility = View.VISIBLE
                }
            },
```

with:

```kotlin
            onChatMessage = { m ->
                Log.d(TAG, "onChatMessage from ${m.name}(${m.from}): \"${m.text.take(80)}\"")
                chatOverlay.onIncoming(m)
            },
```

And REPLACE the `onParticipantsChange` lambda (lines 433–437):

```kotlin
            onParticipantsChange = { list ->
                Log.d(TAG, "onParticipantsChange count=${list.size}")
                participants = list
                renderPeers()
            },
```

with:

```kotlin
            onParticipantsChange = { list ->
                Log.d(TAG, "onParticipantsChange count=${list.size}")
                participants = list
                chatOverlay.setPeers(list)
            },
```

- [ ] **Step 6: Show the overlay when role is assigned**

In `MainActivity.kt connectToParty`, in the `onRoleAssigned` lambda, after `fabChat.visibility = View.VISIBLE` (line 400), add:

```kotlin
                chatOverlay.show()
```

- [ ] **Step 7: Reset the overlay on leave**

In `MainActivity.kt leaveParty`, REPLACE these lines (471–475):

```kotlin
        chatHistory.clear()
        participants = emptyList()
        unreadChatCount = 0
        chatDialog?.dismiss()
        chatDialog = null
```

with:

```kotlin
        participants = emptyList()
        unreadChatCount = 0
        chatOverlay.reset()
```

- [ ] **Step 8: Delete the dead dialog code**

In `MainActivity.kt`, delete the entire `openChatDialog()` (lines 481–523), `appendChatRow()` (lines 525–542), and `renderPeers()` (lines 544–548) functions, plus the `// ── Chat dialog ──` comment header (line 479). The compiler will flag now-unused imports — remove `android.app.AlertDialog` ONLY IF unused (NOTE: `AlertDialog` is still used by `showJoinDialog`/`showLeaveDialog`, so keep it), and remove `android.widget.ScrollView`, `android.text.format.DateFormat`, `java.util.Date`, and the `MaterialButton` import if the build reports them unused.

- [ ] **Step 9: Build and verify it compiles**

Run (from `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If unused-import warnings became errors, remove exactly those imports and rebuild.

- [ ] **Step 10: On-device verification**

Install: `./gradlew :app:installDebug` (device/emulator with the app). Then verify:

1. Join a party (two clients). Send a message from client B → on client A the ambient bubble appears bottom-left as `FirstB: text`, white with shadow, transparent background, over the video.
2. The ambient bubble disappears ~5s after arriving.
3. Tap the chat FAB → expanded panel shows the last messages + "N in party" + transparent input; tapping the input raises the keyboard.
4. Type a message, hit Enter → it sends, keyboard hides, panel collapses, and your own message shows as the ambient bubble exactly once (no duplicate when the server echo returns).
5. Rapid messages: send 3 quickly from B → newest stays visible its full ~5s (no premature erase).

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/ChatOverlayController.kt \
        android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt
git commit -m "feat(chat): replace modal dialog with transparent overlay controller"
```

---

### Task 4: Keyboard positioning above the video

Confirm (and, if needed, reinforce) that the input + expanded panel ride above the soft keyboard rather than being clipped — including over fullscreen video. `adjustResize` is already set; this task adds an IME-insets fallback if on-device testing shows clipping.

**Files:**

- Modify (only if Step 1 verification fails): `android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt`

- [ ] **Step 1: On-device check with adjustResize alone**

With the Task 3 build installed, in a party: tap the chat FAB, focus the input, and observe. Expected: the input row sits directly above the keyboard and the typed text is visible above it. Test both windowed and fullscreen video.

- If the input is visible above the keyboard in both cases → adjustResize suffices; SKIP Steps 2–3 and go to Step 4.
- If the input is hidden/clipped behind the keyboard → continue to Step 2.

- [ ] **Step 2: Add an IME-insets listener (only if Step 1 showed clipping)**

In `MainActivity.kt`, add these imports near the other `androidx.core` imports:

```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
```

Then in `onCreate`, after the `chatOverlay = ChatOverlayController(...)` block, add:

```kotlin
        val overlayView = findViewById<View>(R.id.chatOverlay)
        ViewCompat.setOnApplyWindowInsetsListener(overlayView) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.translationY = -(maxOf(ime - nav, 0)).toFloat()
            insets
        }
```

- [ ] **Step 3: Rebuild and re-verify on-device**

Run (from `android/`): `./gradlew :app:installDebug`
Expected: focusing the input now lifts the overlay above the keyboard in both windowed and fullscreen video.

- [ ] **Step 4: Verify hardware-Enter send**

Connect a Bluetooth/USB keyboard (or use the emulator's hardware keyboard). Focus the input, type, press Enter. Expected: message sends and the panel collapses (the `OnEditorActionListener` handles `KEYCODE_ENTER`).

- [ ] **Step 5: Commit (only if code changed)**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt
git commit -m "fix(chat): lift chat overlay above soft keyboard via IME insets"
```

If no code changed (adjustResize sufficed), record the verification result and skip the commit.

---

### Task 5: Fullscreen z-order

Ensure the overlay (and FABs) stay visible above the fullscreen video container, since streaming sites hide the WebView and add a fullscreen view.

**Files:**

- Modify: `android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt`

- [ ] **Step 1: Bring the overlay to front on entering fullscreen**

In `MainActivity.kt setupWebView`, in the `webChromeClient`'s `onShowCustomView` (lines 313–320), after `hideSystemUi()` add:

```kotlin
                chatOverlay.bringToFront()
                fabChat.bringToFront()
                tvChatBadge.bringToFront()
```

- [ ] **Step 2: Bring the overlay to front on exiting fullscreen**

In `onHideCustomView` (lines 321–329), after `showSystemUi()` add:

```kotlin
                chatOverlay.bringToFront()
                fabChat.bringToFront()
                tvChatBadge.bringToFront()
```

- [ ] **Step 3: Build**

Run (from `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: On-device verification (critical)**

Install (`./gradlew :app:installDebug`), join a party, play a video, and make it go fullscreen (tap the player's fullscreen control). Then:

1. Receive a message from the other client → the ambient bubble is visible over the fullscreen video (not hidden behind it).
2. Tap the chat FAB while fullscreen → the expanded panel + input render on top of the fullscreen video.
3. Exit fullscreen → overlay still works and is correctly positioned.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt
git commit -m "fix(chat): keep chat overlay above fullscreen video container"
```

---

### Task 6: Remove the obsolete chat dialog layout

Delete the now-unused `dialog_chat.xml` and do a final full build.

**Files:**

- Delete: `android/app/src/main/res/layout/dialog_chat.xml`

- [ ] **Step 1: Confirm nothing references the layout**

Run (from repo root): `grep -rn "dialog_chat\|R.layout.dialog_chat\|llChatMessages\|svChat\|tvChatPeers\|etChatInput\|btnChatSend" android/app/src --include=*.kt --include=*.xml`
Expected: no matches (all references were removed in Task 3). If any remain, remove them before deleting the file.

- [ ] **Step 2: Delete the file**

```bash
git rm android/app/src/main/res/layout/dialog_chat.xml
```

- [ ] **Step 3: Final build + unit tests**

Run (from `android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL and all unit tests green.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(chat): remove obsolete modal chat dialog layout"
```

---

## Self-Review

**Spec coverage:**

- Ambient last-message bottom-left + 5s auto-fade → Task 3 (`showAmbient`, `fadeRunnable`, `AMBIENT_FADE_MS`). ✓
- Expanded last-5 + scroll for older → Task 2 (`svOverlay` maxHeight 180dp, scrollable) + Task 3 (`addRow`, `scrollToBottom`, MAX_HISTORY). ✓
- Transparent input above keyboard, keyboard the only obstruction → Task 2 (transparent EditText) + Task 4 (IME positioning). ✓
- Enter sends (IME action + hardware) + send-and-collapse → Task 3 (`setupSend`, `submit`, `collapseAndHideKeyboard`). ✓
- Optimistic render + echo dedup + keep-draft-if-disconnected → Task 1 (`OutgoingEchoTracker`) + Task 3 (`submit`, `onIncoming`). ✓
- First-name for all senders → Task 1 (`firstNameOf`) used in Task 3 rendering. ✓
- Fullscreen z-order → Task 5. ✓
- Peers "N in party" preserved → Task 3 (`setPeers`). ✓
- Badge: increment collapsed, clear on expand → Task 3 (`onUnread`, `onExpandedChanged`). ✓
- fabChat toggles → Task 3 (Step 4). ✓
- Rolling history cap → Task 1 (`trimHistory`) + Task 3 (`addToHistory`, `addRow`). ✓
- maxLength 500 → Task 2 (`etOverlayInput`). ✓
- Remove dialog → Task 3 (Step 8) + Task 6. ✓
- WatchPartyManager unchanged → confirmed; only `sendChat`/`isConnected`/callbacks used. ✓

**Placeholder scan:** No TBD/TODO; all code steps contain complete code; manual-verification steps state explicit expected behavior.

**Type consistency:** `ChatMessage(from, name, text, ts)` used consistently. Controller method names (`onIncoming`, `toggle`, `setPeers`, `bringToFront`, `reset`, `show`, `isExpanded`) match between definition (Task 3 Step 1) and call sites (Steps 3–7, Task 5). `firstNameOf`/`trimHistory`/`OutgoingEchoTracker` signatures match Task 1. `OutgoingEchoTracker` self-purges by time window, so `reset()` needs no explicit clear.
