// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/** Flutter owns presentation; the RCS connection lives independently of an engine. */
class NativeRcsPlugin(private val activity: Activity, messenger: BinaryMessenger) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val channel = MethodChannel(messenger, "com.openbubbles/rcs")
    private val events = EventChannel(messenger, "com.openbubbles/rcs_events")
    private val supported = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    // An unavailable optional RCS runtime must not prevent the iMessage/SMS UI opening.
    private val controller = if (supported) try {
        RcsRuntime.get(activity)
    } catch (_: LinkageError) {
        null
    } catch (_: Exception) {
        null
    } else null
    private var sink: EventChannel.EventSink? = null
    private var started = false
    private var permissionResult: MethodChannel.Result? = null
    private val observer: () -> Unit = { sink?.success(mapOf("type" to "snapshot", "snapshot" to state())) }
    private val eventObserver: (JSONObject) -> Unit = { event ->
        sink?.let {
            it.success(jsonValue(event))
            if (event.optString("type") == "openConversation") controller?.acknowledgePendingConversation(event.optString("conversationId"))
        }
    }

    init {
        channel.setMethodCallHandler(this)
        events.setStreamHandler(this)
        controller?.observe(observer)
        controller?.observeEvents(eventObserver)
        onNewIntent(activity.intent)
    }

    fun onStart() {
        if (!started) { started = true; controller?.activityStarted() }
    }
    fun onStop() {
        if (started) { started = false; controller?.activityStopped() }
    }
    fun dispose() {
        onStop()
        controller?.removeObserver(observer)
        controller?.removeEventObserver(eventObserver)
        sink = null
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        permissionResult?.error("ACTIVITY_CLOSED", "Notification permission request was interrupted.", null)
        permissionResult = null
    }

    fun onNewIntent(intent: Intent?) {
        if (intent?.action != RcsNotifications.OPEN_CONVERSATION) return
        intent.getStringExtra(RcsNotifications.CONVERSATION_ID)?.let { controller?.openNotificationConversation(it) }
        intent.removeExtra(RcsNotifications.CONVERSATION_ID)
        intent.action = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        observer()
    }
    override fun onCancel(arguments: Any?) { sink = null }

    private fun state(): Any = controller?.publicSnapshot()?.let { jsonValue(it) }
        ?: mapOf("supported" to supported, "initialized" to true, "available" to false,
            "paired" to false, "ready" to false, "busy" to false,
            "errorCode" to if (supported) "LOCAL_SESSION_UNAVAILABLE" else "RCS_UNSUPPORTED",
            "status" to if (supported) "The local RCS bridge could not open. Restart the app to try again."
                else "RCS requires an ARM64 Android build.")

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "snapshot") { result.success(state()); return }
        val bridge = controller
        if (bridge == null) {
            result.error(if (supported) "RCS_UNAVAILABLE" else "RCS_UNSUPPORTED",
                "The local RCS bridge is unavailable on this device.", null)
            return
        }
        try {
            fun required(name: String): String = call.argument<String>(name)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException()
            val done: (JSONObject?, RcsFailure?) -> Unit = { value, failure ->
                if (failure != null) result.error(failure.code, failure.message, null)
                else result.success(value?.let { jsonValue(it) })
            }
            when (call.method) {
                "pair" -> {
                    if (bridge.busy || !bridge.available) { result.error("RCS_BUSY", "Wait for the local RCS bridge to be ready.", null); return }
                    if (bridge.snapshot.optBoolean("paired")) { result.error("RCS_ALREADY_PAIRED", "This app already has an RCS pairing.", null); return }
                    bridge.importCookies(required("cookiesJson"))
                    if (!bridge.cookiesImported) { result.error("INVALID_SIGN_IN_FILE", "Choose the local sign-in JSON created by the helper.", null); return }
                    bridge.pair()
                    result.success(state())
                }
                "connect" -> {
                    if (bridge.busy || !bridge.available) { result.error("RCS_BUSY", "Wait for the local RCS bridge to be ready.", null); return }
                    bridge.connect(); result.success(state())
                }
                "disconnect" -> { bridge.disconnect(); result.success(state()) }
                "forget" -> { bridge.forget(); result.success(state()) }
                "conversations" -> bridge.requestConversations(done)
                "messages" -> bridge.requestMessages(required("conversationId"), done)
                "send" -> bridge.requestSend(required("conversationId"), required("simId"), required("text"), required("requestId"), done)
                "markRead" -> bridge.requestMarkRead(required("conversationId"), required("messageId"), done)
                "setPreviews" -> { bridge.setPreviews(call.argument<Boolean>("enabled") == true); result.success(state()) }
                "setVisibleConversation" -> { bridge.setVisibleConversation(call.argument<String>("conversationId")); result.success(null) }
                "takePendingConversation" -> result.success(bridge.takePendingConversation())
                "requestNotifications" -> requestNotifications(result)
                "diagnostics" -> result.success(bridge.diagnostics())
                else -> result.notImplemented()
            }
        } catch (_: IllegalArgumentException) {
            result.error("INVALID_RCS_REQUEST", "The RCS request is missing a required value.", null)
        } catch (_: RuntimeException) {
            result.error("RCS_UNAVAILABLE", "The RCS operation is unavailable. Open RCS settings to check the connection.", null)
        }
    }

    private fun requestNotifications(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            result.success(true); return
        }
        if (permissionResult != null) { result.error("REQUEST_IN_PROGRESS", "A notification permission request is already open.", null); return }
        permissionResult = result
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
    }
    fun onRequestPermissionsResult(requestCode: Int): Boolean {
        if (requestCode != NOTIFICATION_PERMISSION) return false
        permissionResult?.success(Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        permissionResult = null
        observer()
        return true
    }

    companion object {
        private const val NOTIFICATION_PERMISSION = 53823
        internal fun jsonValue(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
            else -> value
        }
    }
}
