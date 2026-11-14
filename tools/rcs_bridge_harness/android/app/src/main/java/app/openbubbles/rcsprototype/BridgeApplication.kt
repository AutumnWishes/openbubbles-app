// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import android.app.Application
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
    @Volatile private var client: Client? = null
    private var connectionEnabled = false
    private var importedCookies: String? = null
    private var refreshScheduled = false
    private var selectedConversationId: String? = null

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

    init { initialize() }

    fun observe(callback: () -> Unit) { listeners.add(callback); callback() }
    fun removeObserver(callback: () -> Unit) { listeners.remove(callback) }

    private fun notifyChanged() { listeners.toList().forEach { it() } }

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
                client = created
                val initial = JSONObject(created.snapshot())
                main.post {
                    if (gate.accepts(token)) {
                        applySnapshot(initial)
                        busy = false
                        status = if (initial.optBoolean("paired")) "Saved pairing is ready. Tap Connect." else "Import your local sign-in file to begin."
                        notifyChanged()
                    }
                }
            } catch (_: Exception) {
                main.post {
                    if (gate.accepts(token)) {
                        busy = false
                        errorCode = "LOCAL_SESSION_UNAVAILABLE"
                        status = "Could not open the saved pairing. Use Forget pairing to start again."
                        notifyChanged()
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
        importedCookies = null
        cookiesImported = false
        connectionEnabled = true
        runOperation("Pairing failed. Import a fresh sign-in file to try again.") { active ->
            active.pair(cookies)
        }
    }

    fun connect() {
        if (busy || client == null) return
        connectionEnabled = true
        runOperation("Could not connect. Check that Google Messages and this phone are online.") { it.connect() }
    }

    fun showConversations() {
        selectedConversationId = null
        conversation = null
        messages = JSONArray()
        draft = ""
        sendNotice = ""
        notifyChanged()
        refresh()
    }

    fun openConversation(item: JSONObject) {
        selectedConversationId = item.getString("id")
        conversation = item
        messages = JSONArray()
        draft = ""
        sendNotice = ""
        notifyChanged()
        refresh()
    }

    fun refresh() {
        if (busy || !snapshot.optBoolean("ready")) return
        val id = selectedConversationId
        val token = gate.token()
        runOperation("Refresh failed. Reconnect and try again.") { active ->
            val value = JSONObject(if (id == null) active.conversations() else active.messages(id))
            main.post {
                if (gate.accepts(token) && client === active && selectedConversationId == id) {
                    if (id == null) conversations = value.optJSONArray("conversations") ?: JSONArray()
                    else {
                        conversation = value.optJSONObject("conversation") ?: conversation
                        messages = value.optJSONArray("messages") ?: JSONArray()
                    }
                    notifyChanged()
                }
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

    fun disconnect() {
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
            }
        }
    }

    fun forget() {
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
            val current = try { JSONObject(active.snapshot()) } catch (_: Exception) { null }
            main.post {
                if (gate.accepts(token) && client === active) {
                    if (current != null) applySnapshot(current)
                    busy = false
                    if (!failed && current?.optBoolean("ready") == true) status = "Connected. Choose a SIM and an existing RCS conversation."
                    notifyChanged()
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
                status = event.optString("message", "Connection state changed.")
                if (event.optString("state") == "ready") scheduleRefresh()
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
            "send" -> { applySendResult(event); scheduleRefresh() }
            "conversation", "message" -> scheduleRefresh()
        }
        notifyChanged()
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

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        val token = gate.token()
        main.postDelayed({
            refreshScheduled = false
            if (!gate.accepts(token) || !connectionEnabled) return@postDelayed
            if (busy) scheduleRefresh() else refresh()
        }, 1000)
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
            "SIM count: ${snapshot.optJSONArray("sims")?.length() ?: 0}",
            "Conversation count: ${conversations.length()}", "Displayed message count: ${messages.length()}",
            "Last error code: ${safeCode(errorCode).ifEmpty { "none" }}",
            "Last send: ${lastSendStatus.takeIf { it in statuses } ?: "unknown"}",
            "Observed protocol: ${lastSendProtocol.takeIf { it in protocols } ?: "unknown"}",
            "Uncertain send: $hasUncertainSend", "Route mismatch: ${stopNotice.isNotEmpty()}", "Mode: foreground proof of concept"
        ).joinToString("\n")
    }

    private fun safeCode(value: String): String = if (value.matches(Regex("[A-Z0-9_]{1,64}"))) value else if (value.isEmpty()) "" else "BRIDGE_ERROR"
}
