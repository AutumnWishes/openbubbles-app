// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Owns the connected lifetime independently of any Activity. */
class RcsBridgeService : Service() {
    private val controller get() = RcsRuntime.get(this)
    private lateinit var notifications: RcsNotifications
    private var observing = false
    private val observer: () -> Unit = {
        // Controller stop/forget publishes one last state change. Do not repost
        // the ongoing notification after stopForeground/clearAll removes it.
        if (controller.backgroundRequested && controller.backgroundActive) {
            notifications.updateConnection(controller.snapshot.optString("state"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RcsNotifications(this)
        val notification = notifications.connection("connecting")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(RcsNotifications.CONNECTION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(RcsNotifications.CONNECTION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            controller.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action in setOf(RcsNotifications.REPLY_ACTION, RcsNotifications.MARK_READ_ACTION)) {
            val request = intent?.let { NotificationActionRequest.fromIntent(it) }
            if (request != null) controller.handleNotificationAction(request)
            else intent?.let { NotificationActionRequest.routeFromIntent(it) }?.let {
                notifications.actionFinished(it.conversationId, it.token, it.reply, false)
            }
        }
        // The durable request is authoritative, including null-intent restarts.
        if (!controller.backgroundRequested) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            controller.observe(observer)
        }
        controller.backgroundStarted()
        if (!controller.backgroundRequested) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (observing) controller.removeObserver(observer)
        controller.backgroundStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val STOP = "com.openbubbles.rcs.STOP" }
}
