package com.bluebubbles.messaging.services.sms

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony

/** Small durable outbox. The system SMS provider remains the source of received messages. */
internal class NativeSmsStore(context: Context) : SQLiteOpenHelper(context, "native_sms.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE outbox (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, client_id TEXT NOT NULL UNIQUE,
            address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL,
            sub_id INTEGER NOT NULL, type INTEGER NOT NULL, status INTEGER NOT NULL,
            part_count INTEGER NOT NULL, baseline_id INTEGER NOT NULL,
            provider_id INTEGER UNIQUE, error_code INTEGER)""")
        db.execSQL("""CREATE TABLE sent_parts (
            outbox_id INTEGER NOT NULL, part INTEGER NOT NULL, result INTEGER NOT NULL,
            PRIMARY KEY(outbox_id, part))""")
        db.execSQL("CREATE TABLE read_overrides (sms_id INTEGER PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun findClient(clientId: String): Map<String, Any?>? = readableDatabase.query(
        "outbox", null, "client_id = ?", arrayOf(clientId), null, null, null
    ).use { if (it.moveToFirst()) outboxRow(it) else null }

    fun findId(id: Long): Map<String, Any?>? = readableDatabase.query(
        "outbox", null, "_id = ?", arrayOf(id.toString()), null, null, null
    ).use { if (it.moveToFirst()) outboxRow(it) else null }

    fun enqueue(clientId: String, address: String, body: String, subId: Int, parts: Int, baselineId: Long): Map<String, Any?> {
        val values = ContentValues().apply {
            put("client_id", clientId)
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("sub_id", subId)
            put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put("status", Telephony.Sms.STATUS_PENDING)
            put("part_count", parts)
            put("baseline_id", baselineId)
        }
        writableDatabase.insertOrThrow("outbox", null, values)
        return findClient(clientId)!!
    }

    /** Idempotent per-part callbacks survive process recreation; one failed part fails the send. */
    fun recordPart(id: Long, part: Int, result: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.query("outbox", arrayOf("part_count"), "_id = ?", arrayOf(id.toString()), null, null, null)
                .use { if (it.moveToFirst()) it.getInt(0) else return }
            if (part !in 0 until count) return
            db.insertWithOnConflict("sent_parts", null, ContentValues().apply {
                put("outbox_id", id); put("part", part); put("result", result)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val callbacks = mutableMapOf<Int, Int>()
            db.query("sent_parts", arrayOf("part", "result"), "outbox_id = ?", arrayOf(id.toString()), null, null, null).use {
                while (it.moveToNext()) {
                    callbacks[it.getInt(0)] = it.getInt(1)
                }
            }
            val outcome = NativeSmsStatus.aggregate(count, callbacks)
            if (outcome.state != NativeSmsStatus.State.PENDING) {
                db.update("outbox", ContentValues().apply {
                    put("type", if (outcome.state == NativeSmsStatus.State.SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
                    // STATUS_NONE means sent without a delivery receipt; never claim delivery.
                    put("status", if (outcome.state == NativeSmsStatus.State.SENT) Telephony.Sms.STATUS_NONE else Telephony.Sms.STATUS_FAILED)
                    if (outcome.errorCode != null) put("error_code", outcome.errorCode) else putNull("error_code")
                }, "_id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markDispatchUnknown(id: Long) {
        writableDatabase.update("outbox", ContentValues().apply {
            // A binder exception can happen after dispatch. Keep this pending/unknown;
            // automatically retrying could bill and send the same text twice.
            put("error_code", -1000)
        }, "_id = ?", arrayOf(id.toString()))
    }

    fun unlinked(): List<Map<String, Any?>> = readableDatabase.query(
        "outbox", null, "provider_id IS NULL", null, null, null, "_id ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(outboxRow(cursor)) } }

    fun linkedProviderIds(): Set<Long> = readableDatabase.query(
        "outbox", arrayOf("provider_id"), "provider_id IS NOT NULL", null, null, null, null
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    fun link(id: Long, providerId: Long) {
        writableDatabase.update("outbox", ContentValues().apply { put("provider_id", providerId) }, "_id = ?", arrayOf(id.toString()))
    }

    fun markRead(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids.filter { it > 0 }) db.insertWithOnConflict(
                "read_overrides", null, ContentValues().apply { put("sms_id", id) }, SQLiteDatabase.CONFLICT_IGNORE
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun readIds(): Set<Long> = readableDatabase.query("read_overrides", arrayOf("sms_id"), null, null, null, null, null)
        .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    private fun outboxRow(cursor: Cursor): Map<String, Any?> = mapOf(
        "id" to -cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
        "clientId" to cursor.getString(cursor.getColumnIndexOrThrow("client_id")),
        "address" to cursor.getString(cursor.getColumnIndexOrThrow("address")),
        "body" to cursor.getString(cursor.getColumnIndexOrThrow("body")),
        "date" to cursor.getLong(cursor.getColumnIndexOrThrow("date")),
        "subId" to cursor.getInt(cursor.getColumnIndexOrThrow("sub_id")),
        "type" to cursor.getInt(cursor.getColumnIndexOrThrow("type")),
        "status" to cursor.getInt(cursor.getColumnIndexOrThrow("status")),
        "read" to true,
        "threadId" to 0L,
        "baselineId" to cursor.getLong(cursor.getColumnIndexOrThrow("baseline_id")),
        "errorCode" to cursor.getColumnIndexOrThrow("error_code").let { if (cursor.isNull(it)) null else cursor.getInt(it) },
    )
}
