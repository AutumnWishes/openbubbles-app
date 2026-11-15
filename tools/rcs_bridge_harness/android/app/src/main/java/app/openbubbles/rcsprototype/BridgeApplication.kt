// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.openbubbles.rcs.bridge.Bridge
import app.openbubbles.rcs.bridge.Client
import app.openbubbles.rcs.bridge.Listener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class BridgeApplication : Application() {
    internal lateinit var controller: BridgeController
        private set

    override fun onCreate() {
        super.onCreate()
        go.Seq.setContext(this)
        controller = BridgeController(this)
    }
}

/** Process-owned so a pairing challenge survives switching to Google Messages. */
internal class BridgeController(private val app: Application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val controls = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val vault = SessionVault(app)
    private val authLock = Any()
    private val listeners = mutableSetOf<() -> Unit>()
    private val gate = OperationGate()
    private val backgroundPrefs = app.getSharedPreferences("background_connection", Application.MODE_PRIVATE)
    private val notifications = BridgeNotifications(app)
    private var incomingPolicy = IncomingNotificationPolicy()
    private var visibleActivities = 0
    private var initialized = false
    var backgroundActive = false
        private set
    val backgroundRequested: Boolean get() = backgroundPrefs.getBoolean("requested", false)
    @Volatile private var client: Client? = null
    private var connectionEnabled = false
    private var importedCookies: String? = null
    private val historyReads = HistoryReadGate()
    private val refreshEvents = RefreshEventPolicy()
    private val notificationActions = NotificationActionQueue<NotificationActionRequest>()
    private var refreshTimer: Runnable? = null
    val refreshing: Boolean get() = historyReads.inFlight
    private var selectedConversationId: String? = null
    var conversationOpenRevision = 0L
        private set

    var snapshot = JSONObject().put("state", "disconnected").put("paired", false).put("ready", false)
        private set
    var conversations = JSONArray()
        private set
    var conversation: JSONObject? = null
        private set
    var messages = JSONArray()
        private set
    var busy = true
        private set
    var status = "Opening local session…"
        private set
    var errorCode = ""
        private set
    var challenge = ""
        private set
    var cookiesImported = false
        private set
    var selectedSimId = ""
    var draft = ""
    var sendNotice = ""
        private set
    var stopNotice = ""
        private set
    var hasUncertainSend = false
        private set
    private var lastSendStatus = "none"
    private var lastSendProtocol = "unconfirmed"
    val available: Boolean get() = client != null

    init {
        notifications.recoverInterruptedActions()
        initialize()
    }

    fun observe(callback: () -> Unit) { listeners.add(callback); callback() }
    fun removeObserver(callback: () -> Unit) { listeners.remove(callback) }

    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun activityStarted() {
        visibleActivities++
        if (backgroundRequested && !backgroundActive) ensureBackground()
        selectedConversationId?.let { notifications.clearConversation(it) }
        if (snapshot.optBoolean("ready")) scheduleRefresh()
    }

    fun activityStopped() { visibleActivities = (visibleActivities - 1).coerceAtLeast(0) }

    private fun ensureBackground(): Boolean {
        if (backgroundActive) return true
        if (!backgroundPrefs.edit().putBoolean("requested", true).commit()) return false
        return try {
            val intent = Intent(app, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            true
        } catch (_: RuntimeException) {
            backgroundPrefs.edit().putBoolean("requested", false).commit()
            errorCode = "BACKGROUND_START_FAILED"
            status = "Could not start background connection. Keep the app visible and tap Connect again."
            notifyChanged()
            false
        }
    }

    private fun stopBackground() {
        backgroundPrefs.edit().putBoolean("requested", false).commit()
        backgroundActive = false
        app.stopService(Intent(app, BridgeService::class.java))
        notifications.clearAll()
    }

    fun backgroundStarted() {
        backgroundActive = true
        resumeBackground()
        notifyChanged()
    }

    fun backgroundStopped() {
        backgroundActive = false
        // If Android destroys only the service, stop the unprotected connection.
        // Keep the user's request so a sticky restart can restore it.
        if (connectionEnabled) disconnect(clearBackground = false)
        notifyChanged()
    }

    private fun resumeBackground() {
        if (!initialized || busy || !backgroundActive || !backgroundRequested || connectionEnabled) return
        if (snapshot.optBoolean("paired")) connect() else stopBackground()
    }

    fun openNotificationConversation(id: String) {
        if (id.isBlank() || id.length > 512 || id.any { it == '\r' || it == '\n' || it == '\u0000' }) return
        // No composing until a fresh lookup returns actual conversation metadata.
        openConversation(JSONObject().put("id", id).put("name", "Conversation").put("readOnly", true))
        notifications.clearConversation(id)
        scheduleRefresh()
    }

    private fun initialize() {
        val token = gate.token()
        worker.execute {
            try {
                val saved = synchronized(authLock) { vault.load().orEmpty() }
                val created = Bridge.newClient(saved, object : Listener {
                    override fun onSession(session: String): Boolean = synchronized(authLock) {
                        if (!gate.sameSession(token)) return@synchronized false
                        try {
                            if (session.isEmpty()) vault.clear() else vault.save(session)
                            true
                        } catch (_: Exception) {
                            main.post {
                                if (gate.sameSession(token)) {
                                    errorCode = "SESSION_STORAGE_FAILED"
                                    status = "Cannot securely save this pairing. Sending is blocked."
                                    notifyChanged()
                                }
                            }
                            false
                        }
                    }

                    override fun onEvent(event: String) {
                        if (!gate.sameSession(token)) return
                        val eventToken = gate.token()
                        main.post {
                            if (gate.sameSession(token) && gate.accepts(eventToken) && connectionEnabled) handleEvent(event)
                        }
                    }
                })
                if (!gate.sameSession(token)) { created.disconnect(); return@execute }
                val initial = JSONObject(created.snapshot())
                main.post {
                    if (gate.accepts(token)) {
                        client = created
                        applySnapshot(initial)
                        initialized = true
                        busy = false
                        status = if (initial.optBoolean("paired")) "Saved pairing is ready. Tap Connect." else "Import your local sign-in file to begin."
                        notifyChanged()
                        resumeBackground()
                        pumpNotificationActions()
                    } else {
                        controls.execute { created.disconnect() }
                    }
                }
            } catch (_: Exception) {
                main.post {
                    if (gate.accepts(token)) {
                        busy = false
                        initialized = true
                        stopBackground()
                        errorCode = "LOCAL_SESSION_UNAVAILABLE"
                        status = "Could not open the saved pairing. Use Forget pairing to start again."
                        notifyChanged()
                        pumpNotificationActions()
                    }
                }
            }
        }
    }

    fun importCookies(json: String) {
        if (busy || snapshot.optBoolean("paired")) return
        try {
            require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            val input = JSONObject(json)
            val required = listOf("SID", "HSID", "SSID", "APISID", "SAPISID", "OSID")
            val allowed = required + "__Secure-1PSIDTS"
            val normalized = JSONObject()
            for (name in required) require(input.opt(name) is String && input.getString(name).isNotBlank())
            for (name in allowed) if (input.has(name)) {
                require(input.opt(name) is String)
                val value = input.getString(name)
                require(value.isNotBlank() && value.length <= 8192 && value.none { it.code < 0x20 || it.code == 0x7f })
                normalized.put(name, value)
            }
            importedCookies = normalized.toString()
            cookiesImported = true
            errorCode = ""
            status = "Sign-in file imported locally. Tap Pair Google Messages."
        } catch (_: Exception) {
            importedCookies = null
            cookiesImported = false
            errorCode = "INVALID_SIGN_IN_FILE"
            status = "This is not a valid sign-in JSON file. Create it with the supplied local helper."
        }
        notifyChanged()
    }

    fun importFailed() {
        importedCookies = null
        cookiesImported = false
        errorCode = "SIGN_IN_FILE_READ_FAILED"
        status = "Could not read that file. Select the JSON file created by the local helper."
        notifyChanged()
    }

    fun pair() {
        if (busy || client == null) return
        val cookies = importedCookies ?: return
        if (!ensureBackground()) return
        importedCookies = null
        cookiesImported = false
        connectionEnabled = true
        runOperation("Pairing failed. Import a fresh sign-in file to try again.") { active ->
            active.pair(cookies)
        }
    }

    fun connect() {
        if (busy || client == null) return
        if (!ensureBackground()) return
        connectionEnabled = true
        runOperation("Could not connect. Check that Google Messages and this phone are online.") { it.connect() }
    }

    fun showConversations() {
        conversationOpenRevision++
        selectedConversationId = null
        conversation = null
        messages = JSONArray()
        draft = ""
        sendNotice = ""
        notifyChanged()
        refresh()
    }

    fun openConversation(item: JSONObject) {
        conversationOpenRevision++
        selectedConversationId = item.getString("id")
        notifications.clearConversation(item.getString("id"))
        conversation = item
        messages = JSONArray()
        draft = ""
        sendNotice = ""
        notifyChanged()
        refresh()
    }

    /** Read-only history loading must never lock the composer or other controls. */
    fun refresh() {
        if (!snapshot.optBoolean("ready")) return
        val active = client ?: return
        val request = historyReads.begin(busy) ?: return
        val id = selectedConversationId
        val openRevision = conversationOpenRevision
        val token = gate.token()
        val readErrorStatus = status.takeIf { errorCode == "REFRESH_FAILED" || errorCode == "LOAD_FAILED" }
        notifyChanged()
        worker.execute {
            val value = try { JSONObject(if (id == null) active.conversations() else active.messages(id)) }
                catch (_: Exception) { null }
            main.post {
                if (!gate.accepts(token) || client !== active || !historyReads.finish(request)) return@post
                if (selectedConversationId == id && conversationOpenRevision == openRevision) {
                    if (value != null) {
                        if (id == null) conversations = value.optJSONArray("conversations") ?: JSONArray()
                        else {
                            conversation = value.optJSONObject("conversation") ?: conversation
                            messages = value.optJSONArray("messages") ?: JSONArray()
                        }
                    } else if (errorCode.isEmpty()) {
                        errorCode = "REFRESH_FAILED"
                        status = "Could not refresh this conversation. Your draft is kept; try Refresh again."
                    }
                }
                // Read the current journal/state on this UI turn, not before newer events.
                try { applySnapshot(JSONObject(active.snapshot())) } catch (_: Exception) { }
                if (selectedConversationId == id && conversationOpenRevision == openRevision && value != null &&
                    (errorCode == "REFRESH_FAILED" || errorCode == "LOAD_FAILED")) {
                    errorCode = ""
                    // Replace only the old read-failure text, keeping any newer
                    // connection or send status that arrived during this read.
                    if (status == readErrorStatus && !busy && snapshot.optBoolean("ready")) {
                        status = "Connected. Choose a SIM and an existing RCS conversation."
                    }
                }
                notifyChanged()
                drainRefresh()
            }
        }
    }

    fun send() {
        val target = conversation ?: return
        val id = selectedConversationId ?: return
        val text = draft.trim()
        if (busy || hasUncertainSend || !snapshot.optBoolean("ready") || !target.optBoolean("rcs") || target.optBoolean("readOnly") || selectedSimId.isEmpty() || text.isEmpty()) return
        val simId = selectedSimId
        val requestId = UUID.randomUUID().toString()
        val token = gate.token()
        sendNotice = "Submitting one RCS request…"
        runOperation("Send result could not be confirmed. Check Google Messages before sending again.", sending = true) { active ->
            val result = JSONObject(active.send(id, simId, text, requestId))
            main.post {
                if (!gate.accepts(token) || client !== active) return@post
                applySendResult(result)
                if (selectedConversationId == id && result.optString("status") != "rejected") draft = ""
                notifyChanged()
            }
        }
    }

    internal fun handleNotificationAction(request: NotificationActionRequest) {
        when (notificationActions.offer(request.key, request)) {
            NotificationActionQueue.Offer.DUPLICATE -> return
            NotificationActionQueue.Offer.FULL -> {
                notifications.actionFinished(request.conversationId, request.token, request.reply, false)
                return
            }
            NotificationActionQueue.Offer.ACCEPTED -> Unit
        }
        notifications.actionProgress(request.conversationId, request.token, request.reply)
        // Do not persist reply text or silently retry it after a restart.
        main.postDelayed({
            notificationActions.expire(request.key)?.let {
                notifications.actionFinished(it.conversationId, it.token, it.reply, false)
            }
        }, 60_000)
        pumpNotificationActions()
    }

    private fun pumpNotificationActions() {
        // A notification action never overrides an explicit Disconnect. During
        // restoration it can wait for the already-requested connection to resume.
        if (!backgroundRequested || (initialized && !snapshot.optBoolean("paired"))) {
            while (true) {
                val (key, request) = notificationActions.next() ?: break
                notifications.actionFinished(request.conversationId, request.token, request.reply, false)
                notificationActions.complete(key)
            }
            return
        }
        if (!initialized || busy || client == null || !snapshot.optBoolean("ready")) return
        val (key, request) = notificationActions.next() ?: return
        val token = gate.token()
        runOperation("Open the conversation to check the notification action.") { active ->
            var result: JSONObject? = null
            var success = false
            var uncertain = false
            try {
                if (request.reply) {
                    result = JSONObject(active.reply(request.conversationId, request.text, request.token))
                    success = result.optString("status") in setOf("accepted", "sent", "delivered", "read")
                    uncertain = result.optString("status") in setOf("unknown", "submitting")
                } else {
                    active.markRead(request.conversationId, request.messageId)
                    success = true
                }
            } catch (_: Exception) {
                // Never report an uncertain native send as definitely unsent.
                uncertain = request.reply
            }
            main.post {
                if (!gate.accepts(token) || client !== active) return@post
                result?.let { applySendResult(it) }
                notifications.actionFinished(request.conversationId, request.token, request.reply, success, uncertain)
                notificationActions.complete(key)
                if (success && visibleActivities > 0) scheduleRefresh()
            }
        }
    }

    fun disconnect(clearBackground: Boolean = true) {
        val cancelledActions = notificationActions.cancel()
        if (!clearBackground) {
            for ((request, active) in cancelledActions) {
                notifications.actionFinished(request.conversationId, request.token, request.reply, false, active && request.reply)
            }
        }
        if (clearBackground) stopBackground()
        cancelRefresh()
        // Stop during async session restoration must not invalidate its completion.
        // The persisted request was cleared, so restoration cannot reconnect.
        if (!initialized) {
            connectionEnabled = false
            importedCookies = null
            cookiesImported = false
            return
        }
        importedCookies = null
        cookiesImported = false
        connectionEnabled = false
        gate.cancelOperations()
        val token = gate.token()
        val active = client
        busy = true
        snapshot.put("ready", false)
        status = "Disconnecting…"
        notifyChanged()
        controls.execute {
            try { active?.disconnect() } catch (_: Exception) { /* Never expose native errors or account state. */ }
            main.post {
                if (!gate.accepts(token) || client !== active) return@post
                snapshot.put("ready", false).put("state", "disconnected")
                busy = false
                challenge = ""
                status = "Disconnected. The encrypted pairing is kept on this device."
                notifyChanged()
                resumeBackground()
            }
        }
    }

    fun forget() {
        notificationActions.cancel(clearHistory = true)
        stopBackground()
        cancelRefresh()
        incomingPolicy = IncomingNotificationPolicy()
        initialized = false
        importedCookies = null
        cookiesImported = false
        connectionEnabled = false
        busy = true
        status = "Removing the local pairing…"
        val old = client
        client = null
        synchronized(authLock) { gate.replaceSession() }
        val token = gate.token()
        notifyChanged()
        controls.execute {
            try { old?.disconnect() } catch (_: Exception) { }
            try { old?.forget() } catch (_: Exception) { }
            val cleared = try { synchronized(authLock) { vault.clear() }; true } catch (_: Exception) { false }
            main.post {
                if (!gate.accepts(token)) return@post
                snapshot = JSONObject().put("state", "disconnected").put("paired", false).put("ready", false)
                conversations = JSONArray()
                messages = JSONArray()
                conversation = null
                selectedConversationId = null
                selectedSimId = ""
                draft = ""
                challenge = ""
                sendNotice = ""
                stopNotice = ""
                hasUncertainSend = false
                lastSendStatus = "none"
                lastSendProtocol = "unconfirmed"
                errorCode = if (cleared) "" else "SESSION_REMOVE_FAILED"
                status = if (cleared) "Local pairing removed." else "Could not remove local pairing. Clear this app's storage in Android Settings."
                busy = !cleared
                notifyChanged()
                if (cleared) { busy = true; initialize() }
            }
        }
    }

    private fun runOperation(failure: String, sending: Boolean = false, block: (Client) -> Unit) {
        val active = client ?: return
        if (busy) return
        val token = gate.token()
        busy = true
        errorCode = ""
        notifyChanged()
        worker.execute {
            var failed = false
            try { block(active) } catch (_: Exception) {
                failed = true
                main.post {
                    if (gate.accepts(token) && client === active) {
                        if (errorCode.isEmpty()) {
                            errorCode = if (sending) "SEND_UNCONFIRMED" else "OPERATION_FAILED"
                            status = failure
                        }
                        if (sending) { hasUncertainSend = true; sendNotice = failure }
                    }
                }
            }
            main.post {
                if (gate.accepts(token) && client === active) {
                    // Read on the UI turn: a worker snapshot can arrive after a
                    // newer ready/offline event and overwrite it with old state.
                    val current = try { JSONObject(active.snapshot()) } catch (_: Exception) { null }
                    if (current != null) applySnapshot(current)
                    busy = false
                    if (!failed && current?.optBoolean("ready") == true) status = "Connected. Choose a SIM and an existing RCS conversation."
                    if (failed && !snapshot.optBoolean("paired")) stopBackground()
                    notifyChanged()
                    pumpNotificationActions()
                    drainRefresh()
                }
            }
        }
    }

    private fun applySnapshot(value: JSONObject) {
        snapshot = value
        challenge = value.optString("challenge", challenge)
        hasUncertainSend = value.optBoolean("hasUncertainSend", hasUncertainSend)
        val journal = value.optJSONArray("sends")
        for (index in 0 until (journal?.length() ?: 0)) {
            val send = journal?.optJSONObject(index) ?: continue
            detectRouteMismatch(send)
        }
        if (hasUncertainSend && lastSendStatus == "none") {
            val sends = value.optJSONArray("sends")
            for (index in 0 until (sends?.length() ?: 0)) {
                val send = sends?.optJSONObject(index) ?: continue
                if (send.optString("status") in setOf("submitting", "accepted", "unknown")) {
                    lastSendStatus = send.optString("status").let { if (it == "submitting") "unknown" else it }
                    lastSendProtocol = send.optString("protocol", "unconfirmed")
                    break
                }
            }
        }
    }

    private fun handleEvent(raw: String) {
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        when (event.optString("type")) {
            "state" -> {
                val wasReady = snapshot.optBoolean("ready")
                snapshot.put("state", event.optString("state"))
                snapshot.put("ready", event.optString("state") == "ready")
                snapshot.put("readinessCheck", event.optString("readinessCheck", "not_started"))
                status = event.optString("message", "Connection state changed.")
                if (event.optString("state") == "ready") errorCode = ""
                if (event.optString("state") == "ready" && !wasReady && visibleActivities > 0) scheduleRefresh()
            }
            "challenge" -> {
                challenge = event.optString("value")
                status = "Open Google Messages and confirm the matching symbol. Then return here."
            }
            "settings" -> snapshot.put("sims", event.optJSONArray("sims") ?: JSONArray())
            "error" -> {
                errorCode = safeCode(event.optString("code"))
                status = event.optString("message", "The bridge reported an error.")
            }
            "send" -> {
                applySendResult(event)
                invalidateFromEvent("send", event.optString("requestId"), event.optString("conversationId"), event)
            }
            "message" -> {
                val message = event.optJSONObject("message")
                if (message != null) {
                    val id = message.optString("conversationId")
                    val eligible = message.optString("protocol") == "RCS" && incomingPolicy.shouldNotify(
                        message.optString("id"), id, message.optBoolean("outgoing"),
                        message.optBoolean("old"), message.optInt("statusCode"),
                        message.optLong("timestampMs"), System.currentTimeMillis())
                    if (eligible &&
                        (visibleActivities == 0 || selectedConversationId != id)) {
                        val chat = if (conversation?.optString("id") == id) conversation
                            else (0 until conversations.length()).asSequence().mapNotNull { conversations.optJSONObject(it) }
                                .firstOrNull { it.optString("id") == id }
                        notifications.incoming(id, message.optString("text"), chat?.optString("name").orEmpty(), message.optString("id"))
                    }
                }
                if (message != null) invalidateFromEvent("message", message.optString("id"), message.optString("conversationId"), message, message.optBoolean("old"))
            }
            "conversation" -> event.optJSONObject("conversation")?.let {
                invalidateFromEvent("conversation", it.optString("id"), it.optString("id"), it)
            }
        }
        notifyChanged()
        pumpNotificationActions()
    }

    private fun applySendResult(result: JSONObject) {
        lastSendStatus = result.optString("status", "unknown")
        lastSendProtocol = result.optString("protocol", "unconfirmed")
        if (lastSendStatus == "unknown") hasUncertainSend = true
        sendNotice = when (lastSendStatus) {
            "accepted" -> "Accepted by the bridge. Waiting for observed protocol and delivery status."
            "sent", "delivered", "read" -> "$lastSendProtocol • $lastSendStatus"
            "rejected" -> "Request rejected. No automatic retry."
            else -> "Result unknown. Check Google Messages before sending again."
        }
        if (lastSendProtocol == "SMS" || lastSendProtocol == "MMS") sendNotice = "Observed $lastSendProtocol instead of RCS. Stop this test and report the diagnostic status."
        detectRouteMismatch(result)
    }

    private fun detectRouteMismatch(send: JSONObject) {
        val protocol = send.optString("protocol")
        if (protocol == "SMS" || protocol == "MMS") {
            stopNotice = "Stop this test: the phone reported $protocol for an RCS request. Check Google Messages and copy diagnostic status."
            hasUncertainSend = true
        }
        val actualSim = send.optString("actualSimId")
        if (actualSim.isNotEmpty() && actualSim != send.optString("simId")) {
            stopNotice = "Stop this test: the phone reported a different sender SIM. Check Google Messages and copy diagnostic status."
            hasUncertainSend = true
        }
    }

    private fun invalidateFromEvent(kind: String, id: String, conversationId: String, value: JSONObject, old: Boolean = false) {
        // Stable key ordering; the historical/live flag is not a content change.
        val signature = value.keys().asSequence().filter { it != "old" }.sorted()
            .joinToString("|") { key -> "$key=${value.opt(key)}" }
        if (refreshEvents.shouldRefresh(kind, id, conversationId, signature, old, selectedConversationId) && visibleActivities > 0) scheduleRefresh()
    }

    private fun scheduleRefresh() {
        historyReads.request()
        if (refreshTimer != null) return
        val token = gate.token()
        val task = Runnable {
            refreshTimer = null
            if (!gate.accepts(token) || !connectionEnabled || visibleActivities == 0 || !historyReads.pending) return@Runnable
            if (!busy && !refreshing) refresh()
            // Completion of the outstanding operation drains one coalesced request.
        }
        refreshTimer = task
        main.postDelayed(task, 1000)
    }

    private fun drainRefresh() {
        if (historyReads.pending && !busy && !refreshing && connectionEnabled && visibleActivities > 0 && snapshot.optBoolean("ready")) scheduleRefresh()
    }

    private fun cancelRefresh() {
        refreshTimer?.let { main.removeCallbacks(it) }
        refreshTimer = null
        historyReads.cancel()
        refreshEvents.clear()
    }

    fun diagnostics(): String {
        val states = setOf("disconnected", "pairing", "awaiting_confirmation", "connecting", "ready", "offline", "reauth_required", "error")
        val statuses = setOf("none", "accepted", "unknown", "rejected", "sent", "delivered", "read")
        val protocols = setOf("unconfirmed", "RCS", "SMS", "MMS", "unknown")
        val version = try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "unknown"} (build $code)"
        } catch (_: Exception) { "unknown" }
        return listOf(
            "RCS Bridge Test $version", "Android API ${android.os.Build.VERSION.SDK_INT}",
            "State: ${snapshot.optString("state").takeIf { it in states } ?: "unknown"}",
            "Paired: ${snapshot.optBoolean("paired")}", "Ready: ${snapshot.optBoolean("ready")}",
            "Phone readiness check: ${snapshot.optString("readinessCheck").takeIf { it in setOf("not_started", "checking", "confirmed", "failed") } ?: "not_started"}",
            "SIM count: ${snapshot.optJSONArray("sims")?.length() ?: 0}",
            "History refresh in progress: $refreshing", "Conversation count: ${conversations.length()}", "Displayed message count: ${messages.length()}",
            "Last error code: ${safeCode(errorCode).ifEmpty { "none" }}",
            "Last send: ${lastSendStatus.takeIf { it in statuses } ?: "unknown"}",
            "Observed protocol: ${lastSendProtocol.takeIf { it in protocols } ?: "unknown"}",
            "Uncertain send: $hasUncertainSend", "Route mismatch: ${stopNotice.isNotEmpty()}", "Background service: $backgroundActive", "Notifications allowed: ${notifications.allowed()}", "Mode: background connection test"
        ).joinToString("\n")
    }

    private fun safeCode(value: String): String = if (value.matches(Regex("[A-Z0-9_]{1,64}"))) value else if (value.isEmpty()) "" else "BRIDGE_ERROR"
}
