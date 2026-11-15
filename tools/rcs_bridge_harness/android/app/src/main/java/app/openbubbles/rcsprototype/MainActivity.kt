// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.security.MessageDigest
import java.util.Date

class MainActivity : Activity() {
    private val controller get() = (application as BridgeApplication).controller
    private val observer: () -> Unit = { render() }
    private val notifications by lazy { BridgeNotifications(this) }
    private lateinit var status: TextView
    private lateinit var challenge: TextView
    private lateinit var openMessagesButton: Button
    private lateinit var setup: LinearLayout
    private lateinit var accountRow: LinearLayout
    private lateinit var setupHint: TextView
    private lateinit var importButton: Button
    private lateinit var pairButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var forgetButton: Button
    private lateinit var simSpinner: Spinner
    private lateinit var title: TextView
    private lateinit var refreshButton: Button
    private lateinit var backButton: Button
    private lateinit var scroll: ScrollView
    private lateinit var items: LinearLayout
    private lateinit var composer: LinearLayout
    private lateinit var draft: EditText
    private lateinit var sendButton: Button
    private lateinit var sendNotice: TextView
    private lateinit var backgroundHint: TextView
    private lateinit var notificationButton: Button
    private var sims = listOf<JSONObject>()
    private var simSignature = ""
    private var renderedContent = ""
    private var renderedConversationId: String? = null
    private var renderedOpenRevision = -1L
    private var initialScrollConversation: String? = null
    private data class InboxPosition(val anchor: String? = null, val offset: Int = 0, val scrollY: Int = 0)
    private var inboxPosition = InboxPosition()
    private var inboxRestorePending = false
    private var backCallback: OnBackInvokedCallback? = null
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inboxPosition = InboxPosition(
            savedInstanceState?.getString("inbox_anchor")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            savedInstanceState?.getInt("inbox_offset") ?: 0,
            (savedInstanceState?.getInt("inbox_scroll") ?: 0).coerceAtLeast(0),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = column().apply { setBackgroundColor(BACKGROUND); setPadding(dp(16), dp(12), dp(16), dp(8)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                dp(16) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom)
            insets
        }
        val heading = row()
        heading.addView(label("RCS Bridge Test", 22, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(button("Settings") { showSettings() }, LinearLayout.LayoutParams(dp(112), dp(48)))
        root.addView(heading)
        status = label("Opening local session…", 14).apply { setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)
        challenge = label("", 24, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF35303F.toInt())
            visibility = View.GONE
        }
        root.addView(challenge)
        openMessagesButton = button("Open Google Messages") {
            val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.messaging")
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Open Google Messages from your home screen.", Toast.LENGTH_LONG).show()
        }.apply { visibility = View.GONE }
        root.addView(openMessagesButton)

        setup = column()
        setup.addView(label("Keep Google Messages as your default app, with RCS connected. This test uses its paired-device connection.", 14))
        setupHint = label("Use the supplied computer helper to create a local sign-in JSON file. Import it here; never send it in chat or upload it to GitHub.", 13)
        setupHint.setPadding(0, dp(8), 0, dp(4))
        setup.addView(setupHint)
        val pairRow = row()
        importButton = button("Import sign-in file") { selectSignInFile() }
        pairButton = button("Pair Google Messages") { requestAlertsIfNeeded(); controller.pair() }
        pairRow.addView(importButton, weightedButton())
        pairRow.addView(pairButton, weightedButton())
        setup.addView(pairRow)
        root.addView(setup)

        accountRow = row()
        connectButton = button("Connect") { requestAlertsIfNeeded(); controller.connect() }
        disconnectButton = button("Disconnect") { controller.disconnect() }
        forgetButton = button("Forget pairing") { confirmForget() }
        accountRow.addView(connectButton, weightedButton())
        accountRow.addView(disconnectButton, weightedButton())
        accountRow.addView(forgetButton, weightedButton())
        root.addView(accountRow)

        simSpinner = Spinner(this).apply {
            contentDescription = "SIM used to send the RCS test"
            minimumHeight = dp(48)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (rendering) return
                    controller.selectedSimId = if (position > 0 && position <= sims.size) sims[position - 1].optString("id") else ""
                    updateSendButton()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) { controller.selectedSimId = ""; updateSendButton() }
            }
        }
        root.addView(simSpinner, LinearLayout.LayoutParams(-1, dp(48)))

        val navigation = row()
        backButton = button("Chats") { controller.showConversations() }
        navigation.addView(backButton, LinearLayout.LayoutParams(dp(78), dp(48)))
        title = label("Recent conversations", 16, true).apply { maxLines = 2 }
        navigation.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        refreshButton = button("Refresh") { controller.refresh() }
        navigation.addView(refreshButton, LinearLayout.LayoutParams(dp(92), dp(48)))
        root.addView(navigation)

        items = column()
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // A stable focus target prevents rebuilding inbox rows from asking
            // ScrollView to reveal an arbitrary newly focused conversation.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(items)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        sendNotice = label("", 13).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(sendNotice)
        composer = row()
        draft = EditText(this).apply {
            id = android.R.id.edit
            hint = "RCS test message"
            setTextColor(FOREGROUND)
            setHintTextColor(SECONDARY)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(4000))
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!rendering) { controller.draft = s?.toString().orEmpty(); updateSendButton() }
                }
                override fun afterTextChanged(s: Editable?) { }
            })
        }
        sendButton = button("Send RCS") { controller.send() }
        composer.addView(draft, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(104), dp(52)))
        root.addView(composer)
        backgroundHint = label("", 12).apply {
            setTextColor(SECONDARY); setPadding(0, dp(6), 0, 0)
        }
        root.addView(backgroundHint)
        notificationButton = button("Enable message notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("alertsAsked", false)) {
                requestAlertsIfNeeded()
            } else {
                if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
            }
        }
        root.addView(notificationButton)
        setContentView(root)
        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.activityStarted()
        controller.observe(observer)
    }
    override fun onStop() {
        controller.removeObserver(observer)
        controller.activityStopped()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureInboxPosition()
        // Only a one-way row key and numeric viewport offsets leave memory.
        outState.putString("inbox_anchor", inboxPosition.anchor)
        outState.putInt("inbox_offset", inboxPosition.offset)
        outState.putInt("inbox_scroll", inboxPosition.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
        super.onDestroy()
    }

    private fun updateBackNavigation() {
        if (Build.VERSION.SDK_INT < 33) return
        if (controller.conversation != null && backCallback == null) {
            val callback = OnBackInvokedCallback { controller.showConversations() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (controller.conversation == null) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    private fun showSettings() {
        val options = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        options.addView(Switch(this).apply {
            id = android.R.id.checkbox
            text = "Show message previews"
            setTextColor(FOREGROUND)
            minHeight = dp(48)
            isChecked = notifications.previewsEnabled
            setOnCheckedChangeListener { _, enabled -> notifications.previewsEnabled = enabled }
        })
        options.addView(label("Include message text in notifications. Android controls what appears on the lock screen.", 14))
        AlertDialog.Builder(this).setTitle("Settings").setView(options)
            .setPositiveButton("Done", null)
            .setNeutralButton("Diagnostics") { _, _ -> copyDiagnostics() }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(value: Intent?) {
        if (value?.action != BridgeNotifications.OPEN_CONVERSATION) return
        value.getStringExtra(BridgeNotifications.CONVERSATION_ID)?.let { controller.openNotificationConversation(it) }
        value.removeExtra(BridgeNotifications.CONVERSATION_ID)
        value.action = null
    }

    private fun requestAlertsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("alertsAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("alertsAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 82)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 82) render()
    }

    override fun onBackPressed() {
        if (controller.conversation != null) controller.showConversations() else super.onBackPressed()
    }

    private fun render() {
        // The controller may already point to a chat while the visible children
        // are still inbox rows. Capture using the view we actually rendered.
        captureInboxPosition()
        rendering = true
        val state = controller.snapshot
        val paired = state.optBoolean("paired")
        val ready = state.optBoolean("ready")
        status.text = controller.status
        val alertsAllowed = notifications.allowed()
        notificationButton.visibility = if (alertsAllowed) View.GONE else View.VISIBLE
        backgroundHint.text = when {
            controller.backgroundActive && alertsAllowed -> "Background connection active. You can leave the app; Disconnect stops it."
            controller.backgroundActive -> "Background connection active. Allow notifications to receive message alerts."
            else -> "Connect to run in background. After a restart or Android Stop, reopen and Connect."
        }
        challenge.text = if (controller.challenge.isNotEmpty()) "Match this symbol in Google Messages:\n${controller.challenge}" else ""
        challenge.visibility = if (controller.challenge.isNotEmpty() && !ready) View.VISIBLE else View.GONE
        openMessagesButton.visibility = challenge.visibility
        setup.visibility = if (!paired) View.VISIBLE else View.GONE
        setupHint.text = if (controller.cookiesImported) "Sign-in file imported locally. Its contents are never displayed." else "Create a local sign-in JSON file with the supplied computer helper. Never send it in chat or upload it to GitHub."
        importButton.isEnabled = controller.available && !controller.busy && !paired
        pairButton.isEnabled = controller.available && !controller.busy && controller.cookiesImported
        connectButton.isEnabled = controller.available && paired && !ready && !controller.busy
        disconnectButton.isEnabled = controller.available && (controller.backgroundRequested || controller.backgroundActive || ready || state.optString("state") in setOf("pairing", "connecting", "awaiting_confirmation"))
        forgetButton.isEnabled = true
        accountRow.visibility = if (controller.conversation == null) View.VISIBLE else View.GONE
        refreshButton.isEnabled = ready && !controller.busy && !controller.refreshing
        refreshButton.text = if (controller.refreshing) "Loading…" else "Refresh"
        backButton.visibility = if (controller.conversation == null) View.GONE else View.VISIBLE
        backButton.isEnabled = !controller.busy
        updateBackNavigation()

        val available = state.optJSONArray("sims")
        val signature = available?.toString().orEmpty()
        if (signature != simSignature || simSpinner.adapter == null) {
            simSignature = signature
            sims = (0 until (available?.length() ?: 0)).mapNotNull { available?.optJSONObject(it) }
                .filter { it.optBoolean("active") && it.optBoolean("rcsEnabled") }
            val labels = listOf("Choose SIM for this test") + sims.map {
                val name = it.optString("label").ifBlank { "SIM" }
                val number = it.optString("number")
                if (number.isBlank() || name.contains(number)) name else "$name • $number"
            }
            simSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val selected = sims.indexOfFirst { it.optString("id") == controller.selectedSimId }
            simSpinner.setSelection(selected + 1, false)
            if (selected < 0) controller.selectedSimId = ""
        }
        simSpinner.isEnabled = paired && sims.isNotEmpty() && !controller.busy
        title.text = controller.conversation?.optString("name")?.ifBlank { "Conversation" } ?: "Recent conversations"
        val visibleData = if (controller.conversation == null) controller.conversations else controller.messages
        val conversationId = controller.conversation?.optString("id")
        val openRevision = controller.conversationOpenRevision
        if (renderedOpenRevision != openRevision) {
            renderedOpenRevision = openRevision
            initialScrollConversation = conversationId
        }
        val contentKey = "$openRevision|${conversationId.orEmpty()}|$visibleData"
        if (contentKey != renderedContent) {
            renderedContent = contentKey
            val previousScroll = scroll.scrollY
            renderedConversationId = conversationId
            inboxRestorePending = conversationId == null
            items.removeAllViews()
            if (controller.conversation == null) showConversationItems() else showMessageItems()
            if (conversationId == null) scroll.requestFocus()
            // Opening a chat first renders an empty placeholder. Wait for its
            // actual history before scrolling to the newest message once.
            val firstHistory = conversationId != null && initialScrollConversation == conversationId && controller.messages.length() > 0
            // Pre-draw runs after measuring the new history, even when a
            // notification resumes the same Activity from the background.
            scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    if (renderedContent == contentKey && controller.conversationOpenRevision == openRevision) {
                        if (conversationId == null) {
                            restoreInboxPosition()
                            inboxRestorePending = false
                            captureInboxPosition()
                        } else if (firstHistory) {
                            scroll.scrollTo(0, items.height)
                            initialScrollConversation = null
                        } else scroll.scrollTo(0, previousScroll)
                    }
                    return true
                }
            })
        }
        composer.visibility = if (controller.conversation != null) View.VISIBLE else View.GONE
        if (draft.text.toString() != controller.draft) {
            draft.setText(controller.draft)
            draft.setSelection(draft.text.length)
        }
        val canCompose = ready && controller.conversation?.optBoolean("rcs") == true && controller.conversation?.optBoolean("readOnly") != true
        draft.isEnabled = canCompose && !controller.busy && !controller.hasUncertainSend
        sendNotice.text = when {
            controller.stopNotice.isNotEmpty() -> controller.stopNotice
            controller.hasUncertainSend -> "Sending is paused: a previous request is unconfirmed or its route did not match. Refresh and check Google Messages. No automatic retry."
            controller.sendNotice.isNotEmpty() -> controller.sendNotice
            controller.conversation != null && !canCompose -> "This test sends only in existing RCS conversations. Select an RCS chat in Google Messages first."
            sims.isEmpty() && ready -> "No active RCS-enabled SIM was reported. Check RCS status in Google Messages, then refresh."
            else -> ""
        }
        sendNotice.visibility = if (sendNotice.text.isNotEmpty()) View.VISIBLE else View.GONE
        rendering = false
        updateSendButton()
    }

    private fun captureInboxPosition() {
        if (!::scroll.isInitialized || renderedContent.isEmpty() || renderedConversationId != null || inboxRestorePending) return
        for (index in 0 until items.childCount) {
            val row = items.getChildAt(index)
            val id = (row.tag as? String)?.takeIf { it.isNotEmpty() } ?: continue
            if (row.bottom > scroll.scrollY) {
                inboxPosition = InboxPosition(inboxRowKey(id), row.top - scroll.scrollY, scroll.scrollY)
                return
            }
        }
        // An empty/loading placeholder must not erase the last useful position.
    }

    private fun restoreInboxPosition() {
        val anchorRow = (0 until items.childCount).asSequence().map { items.getChildAt(it) }
            .firstOrNull { row -> (row.tag as? String)?.let { inboxRowKey(it) } == inboxPosition.anchor && row.tag != null }
        val target = anchorRow?.let { it.top - inboxPosition.offset } ?: inboxPosition.scrollY
        // ScrollView clamps if an anchor was removed or the inbox became shorter.
        scroll.scrollTo(0, target.coerceAtLeast(0))
    }

    private fun inboxRowKey(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun updateSendButton() {
        if (!::sendButton.isInitialized) return
        val sim = sims.firstOrNull { it.optString("id") == controller.selectedSimId }
        sendButton.isEnabled = controller.snapshot.optBoolean("ready") && !controller.busy && !controller.hasUncertainSend &&
            controller.conversation?.optBoolean("rcs") == true && controller.conversation?.optBoolean("readOnly") != true &&
            sim != null && controller.draft.isNotBlank()
    }

    private fun showConversationItems() {
        val chats = controller.conversations
        if (chats.length() == 0) {
            items.addView(label("Connect, then tap Refresh to load recent Google Messages conversations. Start a test conversation in Google Messages first if needed.", 15).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) })
            return
        }
        for (index in 0 until chats.length()) {
            val chat = chats.optJSONObject(index) ?: continue
            val card = column().apply {
                tag = chat.optString("id")
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(CARD)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!controller.busy) controller.openConversation(chat) }
            }
            card.addView(label(chat.optString("name").ifBlank { "Conversation" }, 17, true))
            card.addView(label(if (chat.optBoolean("rcs")) "RCS" else "SMS/MMS • view only", 12).apply { setTextColor(ACCENT) })
            card.addView(label(chat.optString("preview"), 14).apply { maxLines = 2; setTextColor(SECONDARY) })
            items.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showMessageItems() {
        val data = controller.messages
        if (data.length() == 0) {
            items.addView(label("No messages loaded yet. Tap Refresh after connecting.", 15))
            return
        }
        for (index in 0 until data.length()) {
            val message = data.optJSONObject(index) ?: continue
            val outgoing = message.optBoolean("outgoing")
            val card = column().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(if (outgoing) OUTGOING else CARD)
            }
            card.addView(label(message.optString("text").ifBlank { if (message.optBoolean("hasMedia")) "[Media message — open Google Messages to view]" else "[No text]" }, 16))
            val timestamp = message.optLong("timestampMs")
            val whenSent = if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)) else ""
            val protocol = message.optString("protocol", "unknown")
            val delivery = message.optString("status", "unknown")
            val simId = message.optString("simId")
            val allSims = controller.snapshot.optJSONArray("sims")
            val actualSim = (0 until (allSims?.length() ?: 0)).mapNotNull { allSims?.optJSONObject(it) }
                .firstOrNull { simId.isNotEmpty() && it.optString("id") == simId }
            val simLabel = if (outgoing) actualSim?.optString("label")?.ifBlank { "SIM not reported" } ?: "SIM not reported" else ""
            card.addView(label(listOf(whenSent, protocol, delivery, simLabel).filter { it.isNotEmpty() }.joinToString(" • "), 12).apply {
                setTextColor(SECONDARY); setPadding(0, dp(6), 0, 0)
            })
            val params = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4); bottomMargin = dp(4)
                if (outgoing) leftMargin = dp(28) else rightMargin = dp(28)
            }
            items.addView(card, params)
        }
    }

    private fun selectSignInFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        try { startActivityForResult(intent, IMPORT_FILE) } catch (_: Exception) { controller.importFailed() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(4096)
                val output = ByteArrayOutputStream()
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 64 * 1024)
                    output.write(buffer, 0, count)
                }
                buffer.fill(0)
                output.toByteArray()
            } ?: error("File unavailable")
            try { controller.importCookies(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        } catch (_: Exception) { controller.importFailed() }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("Forget this pairing?")
            .setMessage("Disconnect and delete the encrypted login from RCS Bridge Test. Your Google Messages conversations and the working SMS Alpha are not deleted. You can also remove this linked device in Google Messages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget pairing") { _, _ -> controller.forget() }
            .show()
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("RCS test status", controller.diagnostics()))
        Toast.makeText(this, "Copied status only. No account details or message contents.", Toast.LENGTH_LONG).show()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(FOREGROUND)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 13f; setOnClickListener { action() }
    }
    private fun weightedButton() = LinearLayout.LayoutParams(0, dp(52), 1f)
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val IMPORT_FILE = 7
        private val BACKGROUND = Color.rgb(23, 21, 27)
        private val FOREGROUND = Color.rgb(244, 239, 249)
        private val SECONDARY = Color.rgb(204, 196, 211)
        private val ACCENT = Color.rgb(205, 182, 246)
        private val CARD = Color.rgb(43, 39, 48)
        private val OUTGOING = Color.rgb(75, 51, 105)
    }
}
