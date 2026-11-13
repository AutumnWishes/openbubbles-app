package com.bluebubbles.messaging.services.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * A local SMS transport, independent of Apple IDS. This first implementation deliberately
 * keeps the existing default SMS app responsible for MMS and incoming-message persistence.
 * Do not add default-SMS intent filters until the complete MMS receive path is implemented.
 */
class NativeSmsPlugin(private val activity: Activity, messenger: BinaryMessenger) : MethodChannel.MethodCallHandler {
    private val context = activity.applicationContext
    private val channel = MethodChannel(messenger, CHANNEL)
    private val main = Handler(Looper.getMainLooper())
    private var permissionResult: MethodChannel.Result? = null
    private var observer: ContentObserver? = null

    companion object {
        const val CHANNEL = "com.openbubbles/native_sms"
        const val PERMISSION_REQUEST = 53721
        internal val worker = Executors.newSingleThreadExecutor()
        private var current = WeakReference<NativeSmsPlugin>(null)
        private var database: NativeSmsStore? = null
        private val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

        @Synchronized internal fun store(context: Context): NativeSmsStore =
            database ?: NativeSmsStore(context.applicationContext).also { database = it }

        internal fun changed() {
            current.get()?.let { plugin -> plugin.main.post { plugin.channel.invokeMethod("nativeSmsChanged", null) } }
        }

        /** Android supplies its persisted row URI in the final multipart sent callback. */
        internal fun linkCallbackRow(context: Context, id: Long, rawUri: String?) {
            if (rawUri == null) return
            val uri = Uri.parse(rawUri)
            if (uri.scheme != "content" || uri.authority != "sms") return
            val providerId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
            if (providerId <= 0) return
            val db = store(context)
            val row = db.findId(id) ?: return
            context.contentResolver.query(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId),
                arrayOf("address", "body", "sub_id", "creator", "type"), null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                if (PhoneNumberUtils.normalizeNumber(cursor.getString(0)) != row["address"] ||
                    cursor.getString(1) != row["body"] || cursor.getInt(2) != row["subId"] ||
                    cursor.getString(3) != context.packageName ||
                    providerId <= row["baselineId"] as Long ||
                    cursor.getInt(4) !in listOf(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.MESSAGE_TYPE_FAILED)) return
                db.link(id, providerId)
            }
        }
    }

    init {
        current = WeakReference(this)
        channel.setMethodCallHandler(this)
        observeIfAllowed()
    }

    fun dispose() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        channel.setMethodCallHandler(null)
        permissionResult?.error("ACTIVITY_CLOSED", "Permission request was interrupted; open the app and try again.", null)
        permissionResult = null
        if (current.get() === this) current.clear()
    }

    fun onResume() {
        observeIfAllowed()
        changed()
    }

    fun onRequestPermissionsResult(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        observeIfAllowed()
        permissionResult?.success(hasPermissions())
        permissionResult = null
        return true
    }

    private fun hasPermissions() = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun supported() = context.packageManager.hasSystemFeature(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PackageManager.FEATURE_TELEPHONY_MESSAGING
        else PackageManager.FEATURE_TELEPHONY
    )
    private fun isDefault() = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    private fun observeIfAllowed() {
        val canRead = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!canRead) {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
            observer = null
            return
        }
        if (observer != null) return
        val candidate = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = changed()
            override fun onChange(selfChange: Boolean, uri: Uri?) = changed()
        }
        try {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, candidate)
            observer = candidate
        } catch (_: SecurityException) {
            // The user-facing provider query returns a useful permission error.
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getState" -> result.success(mapOf(
                "supported" to supported(), "isDefault" to isDefault(),
                "permissionsGranted" to hasPermissions(), "canRequestDefault" to false,
                "mode" to "companion", "supportsMms" to false,
            ))
            "requestDefault" -> result.success(false)
            "requestPermissions" -> {
                if (!supported()) { result.success(false); return }
                if (hasPermissions()) { observeIfAllowed(); result.success(true); return }
                if (permissionResult != null) { result.error("REQUEST_IN_PROGRESS", "A permission request is already open.", null); return }
                permissionResult = result
                activity.requestPermissions(permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray(), PERMISSION_REQUEST)
            }
            "getSubscriptions" -> run(result) { requirePermission(Manifest.permission.READ_PHONE_STATE); subscriptions() }
            "listMessages" -> run(result) {
                requirePermission(Manifest.permission.READ_SMS)
                listMessages(call.argument<Number>("limit")?.toInt() ?: 200, call.argument<Number>("beforeId")?.toLong(),
                    call.argument<List<Number>>("ids")?.map { it.toLong() })
            }
            "sendSms" -> run(result) { send(call) }
            "markRead" -> run(result) {
                val ids = call.argument<List<Number>>("ids")?.map { it.toLong() } ?: emptyList()
                require(ids.size <= 1000) { "Mark at most 1000 messages read at a time." }
                // Non-default apps cannot change the shared provider's read state.
                store(context).markRead(ids)
                null
            }
            else -> result.notImplemented()
        }
    }

    private fun run(result: MethodChannel.Result, block: () -> Any?) {
        worker.execute {
            try {
                val value = block()
                main.post { result.success(value) }
            } catch (error: SecurityException) {
                main.post { result.error("SMS_PERMISSION_DENIED", "Android denied SMS access. Enable SMS and Phone permissions in Android settings. If unavailable, this installation may not allow SMS permissions.", null) }
            } catch (error: IllegalArgumentException) {
                main.post { result.error("INVALID_SMS_REQUEST", error.message, null) }
            } catch (error: Exception) {
                // No message bodies, destinations or tokens are logged or exposed in errors.
                main.post { result.error("SMS_UNAVAILABLE", "Android could not complete this SMS operation. Check the SIM and SMS permissions.", null) }
            }
        }
    }

    private fun requirePermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
    }

    @Suppress("DEPRECATION")
    private fun subscriptions(): List<Map<String, Any?>> {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        return manager.activeSubscriptionInfoList.orEmpty().map {
            mapOf("id" to it.subscriptionId, "displayName" to it.displayName.toString(), "number" to "")
        }
    }

    private fun listMessages(requestedLimit: Int, beforeId: Long?, ids: List<Long>?): List<Map<String, Any?>> {
        val limit = requestedLimit.coerceIn(1, 500)
        val db = store(context)
        reconcile(db)
        val localRead = db.readIds()
        val projection = arrayOf("_id", "address", "body", "date", "type", "read", "status", "sub_id", "thread_id")
        val rows = ArrayList<Map<String, Any?>>()
        require(ids == null || ids.size <= 500 && ids.all { it > 0 }) { "Query at most 500 positive SMS IDs." }
        if (ids != null && ids.isEmpty()) return rows
        val selection = when {
            ids != null -> "_id IN (${ids.joinToString(",") { "?" }})"
            beforeId != null && beforeId > 0 -> "_id < ?"
            else -> null
        }
        val args = when {
            ids != null -> ids.map { it.toString() }.toTypedArray()
            beforeId != null && beforeId > 0 -> arrayOf(beforeId.toString())
            else -> null
        }
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, selection, args,
            "_id DESC"
        )?.use { cursor ->
            // Updated TelephonyProvider rejects LIMIT embedded in sortOrder.
            while (rows.size < (if (ids == null) limit else 500) && cursor.moveToNext()) rows.add(providerRow(cursor, localRead))
        } ?: throw IllegalStateException("SMS provider unavailable")
        if (beforeId == null && ids == null) rows.addAll(db.unlinked())
        return rows
    }

    private fun providerRow(c: Cursor, localRead: Set<Long>): Map<String, Any?> {
        val id = c.getLong(c.getColumnIndexOrThrow("_id"))
        return mapOf(
            "id" to id, "address" to (c.getString(c.getColumnIndexOrThrow("address")) ?: ""),
            "body" to (c.getString(c.getColumnIndexOrThrow("body")) ?: ""),
            "date" to c.getLong(c.getColumnIndexOrThrow("date")),
            "type" to c.getInt(c.getColumnIndexOrThrow("type")),
            "read" to (c.getInt(c.getColumnIndexOrThrow("read")) != 0 || id in localRead),
            "status" to c.getInt(c.getColumnIndexOrThrow("status")),
            "subId" to c.getInt(c.getColumnIndexOrThrow("sub_id")),
            "threadId" to c.getLong(c.getColumnIndexOrThrow("thread_id")),
        )
    }

    /** Link each completed local send once to the row automatically persisted by Android. */
    private fun reconcile(db: NativeSmsStore) {
        val linked = db.linkedProviderIds().toMutableSet()
        val unlinked = db.unlinked()
        for (row in unlinked) {
            val date = row["date"] as Long
            var matched = false
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "type", "date"),
                "body = ? AND sub_id = ? AND date >= ? AND date <= ? AND _id > ? AND creator = ? AND type IN (2, 5)",
                arrayOf(row["body"] as String, row["subId"].toString(), (date - 1000).toString(),
                    (date + 300000).toString(), row["baselineId"].toString(), context.packageName),
                "_id ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val address = cursor.getString(1) ?: continue
                    val providerDate = cursor.getLong(3)
                    val statusMatches = row["type"] == Telephony.Sms.MESSAGE_TYPE_OUTBOX || cursor.getInt(2) == row["type"]
                    // A second identical send can overlap an interrupted first send. Without
                    // Android's exact callback URI, never guess which local operation it was.
                    val ambiguous = unlinked.any { other ->
                        other["id"] != row["id"] && other["address"] == row["address"] &&
                            other["body"] == row["body"] && other["subId"] == row["subId"] &&
                            id > (other["baselineId"] as Long) &&
                            providerDate in ((other["date"] as Long) - 1000)..((other["date"] as Long) + 300000)
                    }
                    if (!ambiguous && id !in linked && statusMatches && PhoneNumberUtils.normalizeNumber(address) == row["address"]) {
                        db.link(-(row["id"] as Long), id)
                        linked.add(id)
                        matched = true
                        break
                    }
                }
            }
            if (!matched && row["type"] == Telephony.Sms.MESSAGE_TYPE_OUTBOX &&
                NativeSmsStatus.isUnconfirmedTooLong(date, System.currentTimeMillis())) {
                db.markDispatchUnknown(-(row["id"] as Long))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun send(call: MethodCall): Map<String, Any?> {
        requirePermission(Manifest.permission.SEND_SMS)
        requirePermission(Manifest.permission.READ_SMS)
        requirePermission(Manifest.permission.READ_PHONE_STATE)
        require(supported()) { "This device does not support carrier SMS." }
        check(!isDefault()) { "This prototype requires another app to remain the default SMS app." }
        val clientId = call.argument<String>("clientId").orEmpty()
        require(clientId.length in 1..128) { "A unique clientId is required." }
        val rawAddress = call.argument<String>("address").orEmpty().trim()
        require(rawAddress.matches(Regex("[+0-9() .-]+"))) { "Enter one phone number or short code. Group messaging requires MMS." }
        val address = PhoneNumberUtils.normalizeNumber(rawAddress)
        require(address.matches(Regex("\\+?[0-9]{1,20}"))) { "Enter a valid phone number or short code." }
        val body = call.argument<String>("body").orEmpty()
        require(body.isNotBlank() && body.length <= 10000) { "SMS text must contain 1–10000 characters." }
        val db = store(context)
        db.findClient(clientId)?.let {
            require(it["address"] == address && it["body"] == body) { "A clientId cannot be reused for different message content." }
            require(call.argument<Number>("subscriptionId")?.toInt()?.let { sub -> sub == it["subId"] } != false) { "A clientId cannot be reused with a different SIM." }
            return it // Idempotency prevents duplicate carrier sends after an interrupted UI call.
        }
        val active = subscriptions().map { (it["id"] as Int) }
        val requested = call.argument<Number>("subscriptionId")?.toInt()
        val default = SubscriptionManager.getDefaultSmsSubscriptionId()
        val subId = requested ?: if (default in active) default else active.singleOrNull()
        require(subId != null && subId in active) { "Select an active SIM before sending SMS." }
        val manager = SmsManager.getSmsManagerForSubscriptionId(subId)
        val parts = manager.divideMessage(body)
        require(parts.isNotEmpty() && parts.size <= 20) { "This text exceeds the 20-part SMS limit. Shorten it before sending." }
        val baseline = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: throw IllegalStateException("SMS provider unavailable")
        val queued = db.enqueue(clientId, address, body, subId, parts.size, baseline)
        main.postDelayed({ changed() }, 120001)
        val id = -(queued["id"] as Long)
        val sent = ArrayList<PendingIntent>()
        for (part in parts.indices) {
            val intent = Intent(context, NativeSmsSentReceiver::class.java).apply {
                action = "${context.packageName}.NATIVE_SMS_SENT"
                data = Uri.parse("openbubbles-native-sms://sent/$id/$part")
                putExtra("outboxId", id); putExtra("part", part)
            }
            // Explicit component + private receiver; mutable only so Android can attach its
            // persisted provider URI. No activity/service or implicit intent is exposed.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            sent.add(PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable))
        }
        try {
            if (parts.size == 1) manager.sendTextMessage(address, null, body, sent[0], null)
            else manager.sendMultipartTextMessage(address, null, parts, sent, null)
        } catch (error: Exception) {
            db.markDispatchUnknown(id)
            changed()
            // Keep the clientId/outbox record, because a synchronous binder exception
            // does not prove the modem rejected every part. Never auto-retry.
            return db.findClient(clientId)!!
        }
        changed()
        return queued
    }
}

/** Explicit, non-exported receiver accepts only the PendingIntents given to SmsManager. */
class NativeSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("outboxId", -1)
        val part = intent.getIntExtra("part", -1)
        if (id <= 0 || part < 0) return
        val callbackResult = resultCode
        val providerUri = intent.getStringExtra("uri")
        val pending = goAsync()
        NativeSmsPlugin.worker.execute {
            try {
                NativeSmsPlugin.store(context).recordPart(id, part, callbackResult)
                try {
                    NativeSmsPlugin.linkCallbackRow(context, id, providerUri)
                } catch (_: Exception) {
                    // Missing/revoked READ_SMS or OEM callback differences do not erase status.
                    // The next authorized foreground refresh attempts conservative reconciliation.
                }
                NativeSmsPlugin.changed()
            } finally { pending.finish() }
        }
    }
}
