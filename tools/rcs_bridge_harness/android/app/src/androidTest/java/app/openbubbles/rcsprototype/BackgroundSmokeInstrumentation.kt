// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.util.UUID

/**
 * Offline emulator smoke checks, using only platform instrumentation APIs.
 * Run on a fresh installation with POST_NOTIFICATIONS granted. It refuses a
 * paired installation and never imports credentials, connects, or sends.
 */
class BackgroundSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        var activity: Activity? = null
        var safeToClean = false
        var resultCode = Activity.RESULT_CANCELED
        try {
            val target = targetContext
            // Instrumentation.onCreate runs before Application.onCreate, and
            // start() launches this thread immediately. Read the lateinit field
            // only on the main thread and tolerate its not-yet-assigned state.
            var initializedController: BridgeController? = null
            await("application and local bridge initialization", 15_000) {
                val application = target.applicationContext as? BridgeApplication
                val candidate = try {
                    application?.controller
                } catch (_: UninitializedPropertyAccessException) {
                    null
                }
                if (candidate != null && !candidate.busy && candidate.available) {
                    initializedController = candidate
                    true
                } else false
            }
            val controller = checkNotNull(initializedController)
            onMain { check(!controller.snapshot.optBoolean("paired")) { "Use an unpaired emulator installation." } }
            safeToClean = true
            report.appendLine("PASS: native bridge initialized without an account")

            if (Build.VERSION.SDK_INT >= 33) {
                check(target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    "Grant POST_NOTIFICATIONS before running these smoke checks."
                }
            }
            val manager = target.getSystemService(NotificationManager::class.java)
            val notifications = BridgeNotifications(target)
            check(notifications.allowed()) { "Notifications or their runtime permission are disabled." }
            check(!notifications.previewsEnabled) { "Notification previews must default to off on this upgraded test installation." }
            onMain { notifications.clearAll() }
            activity = startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()

            val firstId = "offline-smoke-chat-one"
            val secondId = "offline-smoke-chat-two"
            onMain {
                notifications.incoming(firstId, "Synthetic private preview one", "Synthetic sender one")
                notifications.incoming(secondId, "Synthetic private preview two", "Synthetic sender two")
            }
            await("two incoming notifications") { manager.activeNotifications.count { isIncoming(it.notification) } == 2 }
            val messages = manager.activeNotifications.filter { isIncoming(it.notification) }
            for (message in messages) {
                val notification = message.notification
                check(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "New RCS message")
                check(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Tap to open the conversation.")
                check(notification.visibility == Notification.VISIBILITY_PRIVATE)
                check(notification.contentIntent != null)
            }
            check(messages[0].notification.contentIntent != messages[1].notification.contentIntent) {
                "Different chats share a PendingIntent."
            }
            report.appendLine("PASS: two private generic incoming notifications have distinct chat PendingIntents")

            // Each tap must open a placeholder, never an immediately sendable chat.
            val opened = mutableSetOf<String>()
            for (message in messages) {
                message.notification.contentIntent.send()
                await("notification conversation placeholder") {
                    val chat = controller.conversation
                    chat != null && chat.optString("id") in setOf(firstId, secondId) && chat.optString("id") !in opened
                }
                onMain {
                    val chat = checkNotNull(controller.conversation)
                    check(chat.optBoolean("readOnly")) { "Notification tap created a writable chat before lookup." }
                    check(!chat.optBoolean("rcs")) { "Notification placeholder claims unverified RCS eligibility." }
                    opened.add(chat.optString("id"))
                }
            }
            check(opened == setOf(firstId, secondId))
            report.appendLine("PASS: each notification tap opens its matching read-only conversation placeholder")

            val previewBody = "Synthetic message preview for the offline test"
            val previewName = "Synthetic preview conversation"
            onMain {
                notifications.previewsEnabled = true
                check(BridgeNotifications(target).previewsEnabled) { "Preview preference was not shared with another notification instance." }
                notifications.incoming("offline-preview-chat", previewBody, previewName)
                notifications.updateConnection("ready")
            }
            await("opt-in preview and ongoing connection notification") {
                manager.activeNotifications.count { isIncoming(it.notification) } == 1 &&
                    manager.activeNotifications.any { it.id == BridgeNotifications.CONNECTION_ID }
            }
            val preview = manager.activeNotifications.single { isIncoming(it.notification) }.notification
            check(preview.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == previewName)
            check(preview.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == previewBody)
            check(preview.visibility == Notification.VISIBILITY_PRIVATE)
            val publicPreview = checkNotNull(preview.publicVersion) { "Preview notification has no redacted public version." }
            check(publicPreview.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "New RCS message")
            check(publicPreview.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Tap to open the conversation.")
            onMain {
                notifications.previewsEnabled = false
                check(!BridgeNotifications(target).previewsEnabled) { "Disabling previews was not persisted." }
            }
            await("disabling previews clears existing message alerts only") {
                manager.activeNotifications.none { isIncoming(it.notification) } &&
                    manager.activeNotifications.any { it.id == BridgeNotifications.CONNECTION_ID }
            }
            onMain { manager.cancel(BridgeNotifications.CONNECTION_ID) }
            report.appendLine("PASS: opt-in previews show message text, retain a generic public version, and clear on disable without removing the connection notification")

            smokeNotificationActions(target, controller, notifications, manager, checkNotNull(activity), report)

            // UI-only readiness lets this exercise read-in-flight rendering. The
            // native client remains unpaired and rejects the read locally; it
            // cannot make an account request or send anything.
            val activeActivity = checkNotNull(activity)
            val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val itemsField = MainActivity::class.java.getDeclaredField("items").apply { isAccessible = true }
            val scrollField = MainActivity::class.java.getDeclaredField("scroll").apply { isAccessible = true }
            val historyScroll = onMain { scrollField.get(activeActivity) as ScrollView }
            val historyItems = onMain { itemsField.get(activeActivity) as LinearLayout }
            val historyChatId = "offline-history-scroll-chat"
            onMain {
                controller.snapshot.put("ready", false)
                controller.openConversation(JSONObject()
                    .put("id", historyChatId)
                    .put("name", "Offline history scroll chat")
                    .put("rcs", true)
                    .put("readOnly", false))
                check(controller.messages.length() == 0)
                render.invoke(activeActivity)
            }
            waitForIdleSync()
            await("empty conversation placeholder layout") {
                historyScroll.height > 0 && !historyScroll.isLayoutRequested && !historyItems.isLayoutRequested
            }
            onMain {
                check(historyScroll.scrollY == 0) { "Empty conversation did not begin at the top." }
                repeat(40) { controller.messages.put(syntheticHistoryMessage(historyChatId, it)) }
                render.invoke(activeActivity)
            }
            // Check the real viewport after both layout and the posted initial
            // scroll (including ScrollView's smooth scrolling) have completed.
            await("first loaded history reaches latest message") {
                val maxScroll = historyItems.height - historyScroll.height +
                    historyScroll.paddingTop + historyScroll.paddingBottom
                historyItems.childCount == 40 && !historyItems.isLayoutRequested &&
                    maxScroll > 0 && historyScroll.scrollY >= maxScroll - 1 && !historyScroll.canScrollVertically(1)
            }
            report.appendLine("PASS: history arriving after an empty placeholder scrolls to the latest message")

            var previousHistoryHeight = 0
            var userScrollY = 0
            onMain {
                previousHistoryHeight = historyItems.height
                userScrollY = historyScroll.scrollY / 3
                check(userScrollY > 0)
                historyScroll.scrollTo(0, userScrollY)
                check(historyScroll.scrollY == userScrollY && historyScroll.canScrollVertically(1)) {
                    "Could not position the viewport in older history."
                }
                repeat(5) { controller.messages.put(syntheticHistoryMessage(historyChatId, 40 + it)) }
                render.invoke(activeActivity)
            }
            await("changed history relayout") {
                historyItems.childCount == 45 && historyItems.height > previousHistoryHeight &&
                    !historyItems.isLayoutRequested && !historyScroll.isLayoutRequested
            }
            waitForIdleSync()
            onMain {
                check(historyScroll.scrollY == userScrollY && historyScroll.canScrollVertically(1)) {
                    "A later history update moved the viewport away from the user's older messages."
                }
            }
            report.appendLine("PASS: later changed history preserves the user's scroll position")

            // Reopen the very same selected conversation from a notification,
            // after the Activity has actually left the foreground. A chat-ID
            // comparison alone cannot recognize this as a fresh navigation.
            onMain { check(activeActivity.moveTaskToBack(true)) { "Could not background the test Activity." } }
            await("test Activity leaves foreground") { !activeActivity.hasWindowFocus() }
            onMain { notifications.incoming(historyChatId) }
            await("same-chat notification posted") { manager.activeNotifications.count { isIncoming(it.notification) } == 1 }
            val reopen = manager.activeNotifications.single { isIncoming(it.notification) }.notification.contentIntent
            reopen.send()
            await("notification reopens same-chat placeholder") {
                activeActivity.hasWindowFocus() && controller.conversation?.optString("id") == historyChatId &&
                    controller.conversation?.optBoolean("readOnly") == true && controller.messages.length() == 0
            }
            waitForIdleSync()
            onMain {
                repeat(46) { controller.messages.put(syntheticHistoryMessage(historyChatId, it)) }
                render.invoke(activeActivity)
            }
            await("same-chat notification opens newest loaded message") {
                val maxScroll = historyItems.height - historyScroll.height +
                    historyScroll.paddingTop + historyScroll.paddingBottom
                historyItems.childCount == 46 && !historyItems.isLayoutRequested &&
                    maxScroll > 0 && historyScroll.scrollY >= maxScroll - 1 && !historyScroll.canScrollVertically(1)
            }
            onMain {
                previousHistoryHeight = historyItems.height
                userScrollY = historyScroll.scrollY / 3
                historyScroll.scrollTo(0, userScrollY)
                controller.messages.put(syntheticHistoryMessage(historyChatId, 46))
                render.invoke(activeActivity)
            }
            await("post-notification history update relayout") {
                historyItems.childCount == 47 && historyItems.height > previousHistoryHeight &&
                    !historyItems.isLayoutRequested && !historyScroll.isLayoutRequested
            }
            waitForIdleSync()
            onMain { check(historyScroll.scrollY == userScrollY) { "Notification scroll request repeated on a later history update." } }
            report.appendLine("PASS: tapping a notification for the same backgrounded chat opens its newest message once, then preserves later user scrolling")

            val draftText = "Draft should survive refresh"
            val cursor = 8
            onMain {
                controller.snapshot.put("ready", false)
                controller.openConversation(JSONObject()
                    .put("id", "offline-refresh-chat")
                    .put("name", "Offline refresh smoke chat")
                    .put("rcs", true)
                    .put("readOnly", false))
                controller.messages.put(JSONObject()
                    .put("id", "offline-refresh-message")
                    .put("conversationId", "offline-refresh-chat")
                    .put("text", "Synthetic message for render identity check")
                    .put("timestampMs", System.currentTimeMillis())
                    .put("outgoing", false)
                    .put("protocol", "RCS")
                    .put("status", "INCOMING_COMPLETE"))
                controller.snapshot.put("ready", true).put("state", "ready")
                render.invoke(activeActivity)

                val editor = checkNotNull(activeActivity.findViewById<EditText>(android.R.id.edit))
                check(editor.isEnabled) { "Ready conversation does not enable the composer." }
                check(editor.requestFocus()) { "Composer cannot receive focus." }
                editor.setText(draftText)
                editor.setSelection(cursor)
                check(controller.draft == draftText)

                val items = itemsField.get(activeActivity) as LinearLayout
                check(items.childCount > 0) { "Synthetic conversation did not render any message views." }
                val originalMessageView = items.getChildAt(0)
                controller.conversations.put(JSONObject()
                    .put("id", "unrelated-inbox-chat")
                    .put("name", "Unrelated inbox change")
                    .put("preview", "Synthetic unrelated update"))
                render.invoke(activeActivity)
                check(items.getChildAt(0) === originalMessageView) {
                    "An unrelated inbox change rebuilt the visible conversation."
                }
                check(editor.isEnabled && editor.hasFocus())
                check(editor.text.toString() == draftText && editor.selectionStart == cursor && editor.selectionEnd == cursor)
                report.appendLine("PASS: unrelated inbox updates preserve visible message views, draft, focus and cursor")

                controller.refresh()
                // Its completion is posted to the main queue, so this same main
                // turn deterministically inspects the in-flight read state.
                check(controller.refreshing) { "Read refresh did not enter its own in-flight state." }
                check(!controller.busy) { "Read refresh entered exclusive busy state." }
                check(editor.isEnabled) { "Read refresh disabled typing." }
                check(editor.hasFocus()) { "Read refresh stole editor focus." }
                check(controller.draft == draftText && editor.text.toString() == draftText) { "Read refresh changed the draft." }
                check(editor.selectionStart == cursor && editor.selectionEnd == cursor) { "Read refresh moved the cursor." }
            }
            await("unpaired native read completes locally") { !controller.refreshing }
            onMain {
                check(controller.draft == draftText) { "Read completion erased the draft." }
                controller.snapshot.put("ready", false).put("state", "disconnected")
                render.invoke(activeActivity)
            }
            report.appendLine("PASS: an in-flight history refresh preserves typing, focus, draft and cursor without exclusive busy state")

            onMain {
                val editor = checkNotNull(activeActivity.findViewById<EditText>(android.R.id.edit))
                activeActivity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
            waitForIdleSync()
            if (Build.VERSION.SDK_INT >= 30) {
                await("keyboard hidden before system Back") {
                    activeActivity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
                }
            }
            // Send a system Back key, which uses the registered platform back
            // dispatcher on current Android. Never call onBackPressed directly:
            // doing so would miss the predictive-back regression.
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("system Back returns to the conversation list") {
                controller.conversation == null && !activeActivity.isFinishing && activeActivity.hasWindowFocus()
            }
            report.appendLine("PASS: system Back leaves the conversation for the list without finishing the Activity")

            smokeInboxPosition(activeActivity, controller, report)

            val preferences = target.getSharedPreferences("background_connection", Context.MODE_PRIVATE)
            onMain {
                check(preferences.edit().putBoolean("requested", true).commit())
                startServiceForTest(target)
            }
            await("unpaired service stops") { !controller.backgroundRequested && !controller.backgroundActive }
            await("unpaired foreground notification removed") { manager.activeNotifications.none { it.id == BridgeNotifications.CONNECTION_ID } }
            onMain {
                check(!controller.snapshot.optBoolean("paired"))
                check(!controller.snapshot.optBoolean("ready"))
            }
            report.appendLine("PASS: unpaired service startup clears the request and removes its foreground notification")

            onMain {
                check(preferences.edit().putBoolean("requested", true).commit())
                controller.disconnect()
            }
            await("explicit disconnect completes") {
                !controller.busy && !controller.backgroundRequested && !controller.backgroundActive &&
                    controller.snapshot.optString("state") == "disconnected"
            }
            // Emulate an already-queued start arriving after the user pressed Stop.
            onMain { startServiceForTest(target) }
            waitForIdleSync()
            SystemClock.sleep(250)
            await("stale service start stays stopped") {
                !controller.backgroundRequested && !controller.backgroundActive &&
                    controller.snapshot.optString("state") == "disconnected" &&
                    manager.activeNotifications.none { it.id == BridgeNotifications.CONNECTION_ID }
            }
            report.appendLine("PASS: explicit Disconnect prevents a late service start from resuming")
            report.appendLine("All offline background smoke checks passed; no account, pairing, network connection, or message send was used.")
            resultCode = Activity.RESULT_OK
        } catch (failure: Throwable) {
            report.appendLine("FAIL: ${failure.javaClass.simpleName}: ${failure.message}")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            if (safeToClean) {
                try {
                    onMain {
                        (targetContext.applicationContext as BridgeApplication).controller.disconnect()
                        BridgeNotifications(targetContext).apply { previewsEnabled = false; clearAll() }
                        activity?.finish()
                    }
                    await("cleanup disconnect completes") {
                        val controller = (targetContext.applicationContext as BridgeApplication).controller
                        !controller.busy && !controller.backgroundActive && !controller.backgroundRequested
                    }
                    waitForIdleSync()
                } catch (failure: Throwable) {
                    report.appendLine("FAIL during cleanup: ${failure.javaClass.simpleName}")
                    resultCode = Activity.RESULT_CANCELED
                }
            }
        }
        finish(resultCode, Bundle().apply { putString("stream", report.toString()) })
    }

    private fun isIncoming(notification: Notification): Boolean =
        if (Build.VERSION.SDK_INT >= 26) notification.channelId == "rcs_messages" else notification.category == Notification.CATEGORY_MESSAGE

    /** Keeps list and message-history viewport state separate, without an account. */
    private fun smokeInboxPosition(activity: Activity, controller: BridgeController, report: StringBuilder) {
        val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
        val itemsField = MainActivity::class.java.getDeclaredField("items").apply { isAccessible = true }
        val scrollField = MainActivity::class.java.getDeclaredField("scroll").apply { isAccessible = true }
        val items = onMain { itemsField.get(activity) as LinearLayout }
        val scroll = onMain { scrollField.get(activity) as ScrollView }
        val anchorId = "offline-inbox-18"
        val expectedOffset = -17

        fun replaceInbox(order: List<Int>, longerRows: Boolean) {
            while (controller.conversations.length() > 0) controller.conversations.remove(controller.conversations.length() - 1)
            for (index in order) {
                controller.conversations.put(JSONObject()
                    .put("id", "offline-inbox-$index")
                    .put("name", if (longerRows && index % 2 == 0) "Synthetic conversation $index with a longer display name that wraps across the available width" else "Synthetic conversation $index")
                    .put("preview", if (longerRows) "Synthetic preview text that is intentionally long enough to wrap onto a second visible line for the inbox layout regression" else "Short synthetic preview")
                    .put("rcs", true)
                    .put("readOnly", false))
            }
        }

        fun anchorRow() = (0 until items.childCount).map { items.getChildAt(it) }
            .firstOrNull { it.tag == anchorId }

        fun atAnchor(): Boolean {
            val row = anchorRow() ?: return false
            return !items.isLayoutRequested && !scroll.isLayoutRequested && row.top - scroll.scrollY == expectedOffset
        }

        onMain {
            check(!controller.snapshot.optBoolean("paired"))
            controller.snapshot.put("ready", false)
            replaceInbox((0 until 45).toList(), longerRows = false)
            controller.showConversations()
        }
        await("long synthetic inbox layout") {
            items.childCount == 45 && items.height > scroll.height * 2 && !items.isLayoutRequested && !scroll.isLayoutRequested
        }
        onMain { checkNotNull(anchorRow()).requestFocus() }
        waitForIdleSync()
        onMain { scroll.scrollTo(0, checkNotNull(anchorRow()).top - expectedOffset) }
        await("inbox positioned partway through an existing row") { atAnchor() }
        onMain {
            check(checkNotNull(anchorRow()).performClick()) { "The actual inbox card could not open its conversation." }
            check(controller.conversation?.optString("id") == anchorId)
            repeat(45) { controller.messages.put(syntheticHistoryMessage(anchorId, it)) }
            render.invoke(activity)
        }
        await("opened chat has its own long history viewport") {
            items.childCount == 45 && items.height > scroll.height * 2 && !items.isLayoutRequested && !scroll.canScrollVertically(1)
        }
        onMain {
            // Leave the chat at a position unrelated to the inbox's saved offset.
            scroll.scrollTo(0, scroll.scrollY / 2)
            val editor = checkNotNull(activity.findViewById<EditText>(android.R.id.edit))
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
        }
        waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 30) {
            await("keyboard hidden before inbox-position Back") {
                activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
            }
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("Back restores the same inbox row and pixel offset") {
            controller.conversation == null && !activity.isFinishing && items.childCount == 45 && atAnchor()
        }
        waitForIdleSync()
        onMain { check(atAnchor()) { "The inbox moved after restoring its saved position." } }
        report.appendLine("PASS: system Back restores the same conversation-list row and pixel offset after viewing long message history")

        onMain {
            replaceInbox(listOf(46, 45) + (0 until 45).reversed(), longerRows = true)
            render.invoke(activity)
        }
        await("inbox refresh preserves its anchor through reorder and changed row heights") {
            items.childCount == 47 && atAnchor()
        }
        waitForIdleSync()
        onMain { check(atAnchor()) { "A refreshed inbox lost its visible conversation anchor." } }
        report.appendLine("PASS: inbox refresh preserves the visible conversation and offset across reordering and row-height changes")

        onMain {
            replaceInbox(listOf(0, 1, 2), longerRows = false)
            render.invoke(activity)
        }
        await("removed inbox anchor falls back within the remaining scroll range") {
            val maxScroll = (items.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
            items.childCount == 3 && !items.isLayoutRequested && !scroll.isLayoutRequested && scroll.scrollY in 0..maxScroll
        }
        report.appendLine("PASS: removing the anchored conversation clamps the list to a valid remaining position")
    }

    /** Exercises notification PendingIntents, but the native client stays unpaired. */
    private fun smokeNotificationActions(
        target: Context,
        controller: BridgeController,
        notifications: BridgeNotifications,
        manager: NotificationManager,
        activity: Activity,
        report: StringBuilder,
    ) {
        val replyChat = "offline-action-reply-chat"
        val readChat = "offline-action-read-chat"
        val replyMessage = "offline-action-reply-message"
        val readMessage = "offline-action-read-message"
        val originalConversation = onMain { controller.conversation?.optString("id") }
        onMain {
            check(!controller.snapshot.optBoolean("paired") && !controller.backgroundRequested && !controller.backgroundActive)
            notifications.clearAll()
            notifications.incoming(replyChat, "Hidden incoming body", "Hidden sender", replyMessage)
            notifications.incoming(readChat, "Another hidden body", "Another hidden sender", readMessage)
        }
        await("two actionable incoming notifications") { manager.activeNotifications.count { isIncoming(it.notification) } == 2 }
        val posted = manager.activeNotifications.filter { isIncoming(it.notification) }
        val replyNotification = posted.single { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == replyMessage }.notification
        val readNotification = posted.single { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == readMessage }.notification
        for (notification in listOf(replyNotification, readNotification)) {
            check(notification.actions?.size == 2) { "Incoming message lacks its two quick actions." }
            check(notification.actions[0].title.toString() == "Mark as read" && notification.actions[1].title.toString() == "Reply") {
                "Notification actions must show Mark as read before Reply."
            }
            check(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Tap to open the conversation.")
            check(notification.visibility == Notification.VISIBILITY_PRIVATE)
            check(notification.publicVersion?.actions.isNullOrEmpty()) { "Public redacted notification exposes actions." }
        }
        val replyAction = replyNotification.actions.single { !it.remoteInputs.isNullOrEmpty() }
        val markReadAction = readNotification.actions.single { it.remoteInputs.isNullOrEmpty() }
        check(replyAction.remoteInputs.single().resultKey == BridgeNotifications.REPLY_TEXT)
        check(replyAction.remoteInputs.single().allowFreeFormInput)
        check(replyAction.actionIntent != markReadAction.actionIntent)
        if (Build.VERSION.SDK_INT >= 31) {
            check(!replyAction.actionIntent.isImmutable) { "Reply must permit Android to attach RemoteInput." }
            check(markReadAction.actionIntent.isImmutable) { "Mark as read should use an immutable PendingIntent." }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            check(replyAction.actionIntent.isForegroundService && markReadAction.actionIntent.isForegroundService)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            check(replyAction.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY)
            check(markReadAction.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        }
        report.appendLine("PASS: incoming alerts expose native free-form Reply and immutable Mark as read actions with distinct service PendingIntents")

        // Validate routing against supplied extras, malformed paths and oversized
        // input before exercising the actual Android PendingIntent dispatch.
        val parserToken = UUID.randomUUID().toString()
        val replyUri = Uri.Builder().scheme("rcsa").authority("reply")
            .appendPath(replyChat).appendPath(replyMessage).appendPath(parserToken).build()
        val parsedReplyIntent = Intent(BridgeNotifications.REPLY_ACTION).setData(replyUri)
            .putExtra(BridgeNotifications.CONVERSATION_ID, "tampered-chat")
        val syntheticReply = "Synthetic offline inline reply"
        attachReply(parsedReplyIntent, syntheticReply)
        val parsedReply = checkNotNull(NotificationActionRequest.fromIntent(parsedReplyIntent))
        check(parsedReply.reply && parsedReply.conversationId == replyChat && parsedReply.messageId == replyMessage)
        check(parsedReply.token == parserToken && parsedReply.text == syntheticReply)
        check(NotificationActionRequest.fromIntent(Intent(parsedReplyIntent).setAction(BridgeNotifications.MARK_READ_ACTION)) == null)
        check(NotificationActionRequest.fromIntent(Intent(parsedReplyIntent).setData(replyUri.buildUpon().appendQueryParameter("route", "tampered").build())) == null)
        check(NotificationActionRequest.fromIntent(Intent(parsedReplyIntent).setData(replyUri.buildUpon().appendPath("extra").build())) == null)
        val oversized = Intent(BridgeNotifications.REPLY_ACTION).setData(replyUri)
        attachReply(oversized, "x".repeat(4001))
        check(NotificationActionRequest.fromIntent(oversized) == null)
        val empty = Intent(BridgeNotifications.REPLY_ACTION).setData(replyUri)
        attachReply(empty, "   ")
        check(NotificationActionRequest.fromIntent(empty) == null)
        val readUri = replyUri.buildUpon().authority("read").build()
        val parsedRead = checkNotNull(NotificationActionRequest.fromIntent(Intent(BridgeNotifications.MARK_READ_ACTION).setData(readUri)))
        check(!parsedRead.reply && parsedRead.conversationId == replyChat && parsedRead.messageId == replyMessage)
        report.appendLine("PASS: action parser uses URI routing and rejects mismatched, malformed, blank and oversized replies")

        val replyToken = checkNotNull(replyNotification.extras.getString(BridgeNotifications.ACTION_TOKEN))
        val replyFillIn = Intent().putExtra(BridgeNotifications.CONVERSATION_ID, "tampered-chat")
        attachReply(replyFillIn, syntheticReply)
        replyAction.actionIntent.send(target, 0, replyFillIn)
        await("unpaired inline reply receives failure feedback") {
            manager.activeNotifications.any {
                it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == replyToken &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Reply not sent"
            }
        }
        val failedReply = manager.activeNotifications.single { it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == replyToken }.notification
        check(failedReply.actions.isNullOrEmpty()) { "Failed reply left an automatic retry action." }
        check(!failedReply.extras.toString().contains(syntheticReply)) { "Reply text leaked into a generic status notification." }
        check(failedReply.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Tap to open the conversation.")
        await("stopped inline action releases foreground service") { !controller.backgroundActive && !controller.backgroundRequested }
        onMain {
            check(!controller.snapshot.optBoolean("paired") && !controller.snapshot.optBoolean("ready"))
            check(controller.conversation?.optString("id") == originalConversation && !activity.isFinishing && activity.hasWindowFocus())
        }
        report.appendLine("PASS: real RemoteInput dispatch fails safely while unpaired, clears the reply action, keeps text private and does not open or switch chats")

        val readToken = checkNotNull(readNotification.extras.getString(BridgeNotifications.ACTION_TOKEN))
        markReadAction.actionIntent.send(target, 0, Intent().putExtra(BridgeNotifications.CONVERSATION_ID, "tampered-chat"))
        await("unpaired mark-read receives failure feedback") {
            manager.activeNotifications.any {
                it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == readToken &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Could not mark as read"
            }
        }
        await("mark-read action releases foreground service") { !controller.backgroundActive && !controller.backgroundRequested }
        onMain { check(controller.conversation?.optString("id") == originalConversation && !activity.isFinishing) }
        report.appendLine("PASS: real Mark as read dispatch reports an unpaired failure without dismissing the unread alert or navigating")

        // Android can deliver an empty or oversized RemoteInput even though
        // the notification's routing is valid. It still needs a completed
        // status update to end the system's inline-reply spinner.
        for ((index, invalidText) in listOf("   ", "x".repeat(4001)).withIndex()) {
            val invalidChat = "offline-invalid-reply-chat-$index"
            val invalidMessage = "offline-invalid-reply-message-$index"
            onMain { notifications.incoming(invalidChat, messageId = invalidMessage) }
            await("invalid-input test notification posted") {
                manager.activeNotifications.any { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == invalidMessage }
            }
            val before = manager.activeNotifications.single { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == invalidMessage }.notification
            val token = checkNotNull(before.extras.getString(BridgeNotifications.ACTION_TOKEN))
            val action = before.actions.single { !it.remoteInputs.isNullOrEmpty() }
            val fillIn = Intent()
            attachReply(fillIn, invalidText)
            action.actionIntent.send(target, 0, fillIn)
            await("invalid RemoteInput ends with generic failure") {
                manager.activeNotifications.any {
                    it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == token &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Reply not sent" &&
                        it.notification.actions.isNullOrEmpty()
                }
            }
            await("invalid reply does not reconnect") { !controller.backgroundActive && !controller.backgroundRequested }
            onMain {
                check(!controller.snapshot.optBoolean("paired") && !controller.snapshot.optBoolean("ready"))
                check(controller.conversation?.optString("id") == originalConversation && !activity.isFinishing && activity.hasWindowFocus())
            }
            onMain { notifications.clearConversation(invalidChat) }
        }
        report.appendLine("PASS: blank and oversized RemoteInput replies finish with generic feedback, no remaining action and no reconnect or navigation")

        // A late completion from an older action must not overwrite or dismiss
        // a newer incoming notification for the same conversation.
        onMain { notifications.incoming(replyChat, messageId = "offline-action-newer-message") }
        await("newer same-chat notification replaces earlier action result") {
            manager.activeNotifications.any { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == "offline-action-newer-message" }
        }
        val newest = manager.activeNotifications.single { it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == "offline-action-newer-message" }.notification
        val newestToken = checkNotNull(newest.extras.getString(BridgeNotifications.ACTION_TOKEN))
        check(newestToken != replyToken)
        onMain {
            notifications.actionProgress(replyChat, replyToken, reply = true)
            notifications.actionFinished(replyChat, replyToken, reply = false, success = true)
        }
        waitForIdleSync()
        val retained = manager.activeNotifications.single { it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == newestToken }.notification
        check(retained.actions?.size == 2 && retained.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "New RCS message")
        onMain { notifications.actionFinished(replyChat, newestToken, reply = false, success = true) }
        await("successful matching mark-read dismisses only its alert") {
            manager.activeNotifications.none { it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == newestToken }
        }
        check(manager.activeNotifications.any { it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == readToken })
        report.appendLine("PASS: stale action completions leave newer messages intact; matching mark-read completion dismisses only its conversation alert")

        val interruptedChat = "offline-interrupted-reply-chat"
        val interruptedMessage = "offline-interrupted-reply-message"
        val untouchedChat = "offline-untouched-action-chat"
        val untouchedMessage = "offline-untouched-action-message"
        onMain {
            notifications.incoming(interruptedChat, messageId = interruptedMessage)
            notifications.incoming(untouchedChat, messageId = untouchedMessage)
        }
        await("interrupted-action test notifications posted") {
            manager.activeNotifications.count {
                it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) in setOf(interruptedMessage, untouchedMessage)
            } == 2
        }
        val interruptedToken = checkNotNull(manager.activeNotifications.single {
            it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == interruptedMessage
        }.notification.extras.getString(BridgeNotifications.ACTION_TOKEN))
        val untouchedToken = checkNotNull(manager.activeNotifications.single {
            it.notification.extras.getString(BridgeNotifications.MESSAGE_ID_EXTRA) == untouchedMessage
        }.notification.extras.getString(BridgeNotifications.ACTION_TOKEN))
        onMain { notifications.actionProgress(interruptedChat, interruptedToken, reply = true) }
        await("reply progress status posted") {
            manager.activeNotifications.any {
                it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == interruptedToken &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Sending reply…"
            }
        }
        onMain { notifications.recoverInterruptedActions() }
        await("interrupted reply recovery requests manual status check") {
            manager.activeNotifications.any {
                it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == interruptedToken &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Check reply status" &&
                    it.notification.actions.isNullOrEmpty()
            }
        }
        val untouched = manager.activeNotifications.single {
            it.notification.extras.getString(BridgeNotifications.ACTION_TOKEN) == untouchedToken
        }.notification
        check(untouched.actions?.size == 2 && untouched.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "New RCS message")
        report.appendLine("PASS: interrupted reply recovery ends progress without retrying or altering fresh incoming notifications")
        onMain { notifications.clearAll() }
    }

    private fun attachReply(intent: Intent, text: String) {
        val input = RemoteInput.Builder(BridgeNotifications.REPLY_TEXT).setAllowFreeFormInput(true).build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(BridgeNotifications.REPLY_TEXT, text) })
    }

    private fun syntheticHistoryMessage(conversationId: String, index: Int): JSONObject = JSONObject()
        .put("id", "offline-history-message-$index")
        .put("conversationId", conversationId)
        .put("text", "Synthetic history message $index\nSecond line ensures the history fills the viewport.")
        .put("timestampMs", 1_700_000_000_000L + index * 1_000L)
        .put("outgoing", false)
        .put("protocol", "RCS")
        .put("status", "INCOMING_COMPLETE")

    private fun startServiceForTest(context: Context) {
        val intent = Intent(context, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun await(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        error("Timed out: $label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
