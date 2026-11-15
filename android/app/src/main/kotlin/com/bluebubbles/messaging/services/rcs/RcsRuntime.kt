// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

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
import java.util.concurrent.Executors

/** Process-owned RCS runtime; does not replace OpenBubbles' Application. */
internal object RcsRuntime {
    @Volatile private var instance: RcsController? = null
    @Synchronized fun get(context: android.content.Context): RcsController =
        instance ?: run {
            val app = context.applicationContext as Application
            go.Seq.setContext(app)
            RcsController(app).also { instance = it }
        }
}

/** Process-owned so a pairing challenge survives switching to Google Messages. */
internal class RcsController(private val app: Application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val controls = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // Open the keystore inside initialize()'s guarded worker operation.
    private val vault by lazy { SessionVault(app) }
    private val authLock = Any()
    private val listeners = mutableSetOf<() -> Unit>()
    private val gate = OperationGate()
    private val backgroundPrefs = app.getSharedPreferences("rcs_background_connection", Application.MODE_PRIVATE)
    private val notifications = RcsNotifications(app)
    private var incomingPolicy = IncomingNotificationPolicy()
    private var visibleActivities = 0
    private var initialized = false
    var backgroundActive = false
        private set
    val backgroundRequested: Boolean get() = backgroundPrefs.getBoolean("requested", false)
    @Volatile private var client: Client? = null
    private var connectionEnabled = false
    private var importedCookies: String? = null
    private val notificationActions = NotificationActionQueue<NotificationActionRequest>()
    private var selectedConversationId: String? = null

    var snapshot = JSONObject().put("state", "disconnected").put("paired", false).put("ready", false)
        private set
    var conversations = JSONArray()
        private set
    var conversation: JSONObject? = null
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
    private val eventListeners = mutableSetOf<(JSONObject) -> Unit>()
    private var pendingConversation: String? = null
    fun observeEvents(callback: (JSONObject) -> Unit) { eventListeners.add(callback) }
    fun removeEventObserver(callback: (JSONObject) -> Unit) { eventListeners.remove(callback) }
    private fun emitEvent(event: JSONObject) { eventListeners.toList().forEach { it(event) } }
    fun takePendingConversation(): String? = pendingConversation.also { pendingConversation = null }
    fun acknowledgePendingConversation(id: String) { if (pendingConversation == id) pendingConversation = null }
    fun setVisibleConversation(id: String?) {
        selectedConversationId = id?.takeIf { it.isNotBlank() && it.length <= 512 && it.none { c -> c.code < 32 } }
        if (visibleActivities > 0) selectedConversationId?.let { notifications.clearConversation(it) }
    }

    fun activityStarted() {
        visibleActivities++
        if (backgroundRequested && !backgroundActive) ensureBackground()
        selectedConversationId?.let { notifications.clearConversation(it) }
        // Flutter coalesces history invalidations from the event stream.
    }

    fun activityStopped() { visibleActivities = (visibleActivities - 1).coerceAtLeast(0) }

    /** Public bridge state only: auth/session JSON never enters Flutter. */
    fun publicSnapshot(): JSONObject = JSONObject(snapshot.toString()).apply {
        put("supported", true)
        put("initialized", initialized)
        put("available", available)
        put("busy", busy)
        put("status", status)
        put("errorCode", errorCode)
        put("challenge", challenge)
        put("backgroundRequested", backgroundRequested)
        put("backgroundActive", backgroundActive)
        put("previewsEnabled", notifications.previewsEnabled)
        put("notificationsAllowed", notifications.allowed())
        put("hasUncertainSend", hasUncertainSend)
        put("routeMismatch", stopNotice.isNotEmpty())
    }

    fun setPreviews(enabled: Boolean) {
        notifications.previewsEnabled = enabled
        notifyChanged()
    }

    fun requestConversations(done: (JSONObject?, RcsFailure?) -> Unit) = request(false, done) { active ->
        JSONObject(active.conversations())
    }

    fun requestMessages(id: String, done: (JSONObject?, RcsFailure?) -> Unit) = request(false, done) { active ->
        JSONObject(active.messages(id))
    }

    fun requestSend(id: String, sim: String, text: String, requestId: String, done: (JSONObject?, RcsFailure?) -> Unit) = request(true, done) { active ->
        JSONObject(active.send(id, sim, text, requestId))
    }

    fun requestMarkRead(id: String, messageId: String, done: (JSONObject?, RcsFailure?) -> Unit) = request(true, done) { active ->
        active.markRead(id, messageId)
        JSONObject().put("ok", true)
    }

    private fun request(exclusive: Boolean, done: (JSONObject?, RcsFailure?) -> Unit, block: (Client) -> JSONObject) {
        val active = client
        if (!initialized || active == null) {
            done(null, RcsFailure("RCS_NOT_INITIALIZED", "The local RCS bridge is not ready yet.")); return
        }
        if (exclusive && busy) {
            done(null, RcsFailure("RCS_BUSY", "Wait for the current RCS operation to finish.")); return
        }
        val token = gate.token()
        if (exclusive) { busy = true; notifyChanged() }
        worker.execute {
            var value: JSONObject? = null
            var failure: RcsFailure? = null
            try { value = block(active) } catch (error: Exception) {
                val code = error.message?.substringBefore(':')?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "RCS_OPERATION_FAILED"
                failure = RcsFailure(code, "The RCS operation could not be completed. Check the connection and conversation before trying again.")
            }
            main.post {
                if (!gate.accepts(token) || client !== active) {
                    done(null, RcsFailure("RCS_CANCELLED", "The RCS operation was cancelled.")); return@post
                }
                value?.optJSONArray("conversations")?.let { conversations = it }
                value?.optJSONObject("conversation")?.let {
                    if (it.optString("id") == selectedConversationId) conversation = it
                }
                if (value?.has("requestId") == true) applySendResult(value)
                try { applySnapshot(JSONObject(active.snapshot())) } catch (_: Exception) { }
                if (exclusive) busy = false
                notifyChanged()
                done(value, failure)
                pumpNotificationActions()
            }
        }
    }

    private fun ensureBackground(): Boolean {
        if (backgroundActive) return true
        if (!backgroundPrefs.edit().putBoolean("requested", true).commit()) return false
        return try {
            val intent = Intent(app, RcsBridgeService::class.java)
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
        app.stopService(Intent(app, RcsBridgeService::class.java))
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
        if (id.isBlank() || id.length > 512 || id.any { it.code < 32 || it.code == 127 }) return
        pendingConversation = id
        notifications.clearConversation(id)
        emitEvent(JSONObject().put("type", "openConversation").put("conversationId", id))
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
                if (success) emitEvent(JSONObject().put("type", "invalidate").put("conversationId", request.conversationId))
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
        pendingConversation = null
        stopBackground()
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
                conversation = null
                selectedConversationId = null
                challenge = ""
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

    private fun runOperation(failure: String, block: (Client) -> Unit) {
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
                            errorCode = "OPERATION_FAILED"
                            status = failure
                        }
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
                snapshot.put("state", event.optString("state"))
                snapshot.put("ready", event.optString("state") == "ready")
                snapshot.put("readinessCheck", event.optString("readinessCheck", "not_started"))
                status = event.optString("message", "Connection state changed.")
                if (event.optString("state") == "ready") errorCode = ""
                // History loading belongs to the Flutter service; readiness is emitted below.
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
            }
        }
        emitEvent(event)
        notifyChanged()
        pumpNotificationActions()
    }

    private fun applySendResult(result: JSONObject) {
        lastSendStatus = result.optString("status", "unknown")
        lastSendProtocol = result.optString("protocol", "unconfirmed")
        if (lastSendStatus == "unknown") hasUncertainSend = true
        detectRouteMismatch(result)
    }

    private fun detectRouteMismatch(send: JSONObject) {
        val protocol = send.optString("protocol")
        if (protocol == "SMS" || protocol == "MMS") {
            stopNotice = "The phone reported $protocol for an RCS request. Sending is paused. Check Google Messages and diagnostic status."
            hasUncertainSend = true
        }
        val actualSim = send.optString("actualSimId")
        if (actualSim.isNotEmpty() && actualSim != send.optString("simId")) {
            stopNotice = "The phone reported a different sender SIM. Sending is paused. Check Google Messages and diagnostic status."
            hasUncertainSend = true
        }
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
            "OpenBubbles RCS $version", "Android API ${android.os.Build.VERSION.SDK_INT}",
            "State: ${snapshot.optString("state").takeIf { it in states } ?: "unknown"}",
            "Paired: ${snapshot.optBoolean("paired")}", "Ready: ${snapshot.optBoolean("ready")}",
            "Phone readiness check: ${snapshot.optString("readinessCheck").takeIf { it in setOf("not_started", "checking", "confirmed", "failed") } ?: "not_started"}",
            "SIM count: ${snapshot.optJSONArray("sims")?.length() ?: 0}",
            "Conversation count: ${conversations.length()}",
            "Last error code: ${safeCode(errorCode).ifEmpty { "none" }}",
            "Last send: ${lastSendStatus.takeIf { it in statuses } ?: "unknown"}",
            "Observed protocol: ${lastSendProtocol.takeIf { it in protocols } ?: "unknown"}",
            "Uncertain send: $hasUncertainSend", "Route mismatch: ${stopNotice.isNotEmpty()}", "Background service: $backgroundActive", "Notifications allowed: ${notifications.allowed()}", "Mode: RCS bridge"
        ).joinToString("\n")
    }

    private fun safeCode(value: String): String = if (value.matches(Regex("[A-Z0-9_]{1,64}"))) value else if (value.isEmpty()) "" else "BRIDGE_ERROR"
}

internal data class RcsFailure(val code: String, val message: String)
