# Google Messages RCS bridge test

This separate Android proof of concept tests whether a paired Google Messages
client can send and receive RCS on the same phone that runs Google Messages.
It uses the public `mautrix-gmessages` client library directly. It does not need
a Matrix account, a Matrix server, or an always-on computer.

This is **RCS Bridge Test**, package `app.openbubbles.rcsprototype`. It installs
alongside the official OpenBubbles app, OpenBubbles (Alpha), and SMS Prototype.
It is not yet integrated into the OpenBubbles inbox. Keep Google Messages as
the default SMS app with RCS connected.

## Download

In this repository, open **Actions → Build RCS Bridge Test → a successful run →
Artifacts → RCS-Bridge-Test-arm64-test**. Extract the ZIP and install its APK.
The artifact also includes build provenance, checksums, APK inspection results,
and the corresponding source. The workflow builds
`feature/google-messages-rcs-poc` on relevant pushes and supports manual runs.

The APK is an ARM64 development build signed with a generated Android test key.
Different CI runs can use different test keys, so updating this test app in
place may not work. Removing this test app deletes its local session; it does
not remove Google Messages or either OpenBubbles installation.

## Pair once

Google's current linked-device login requires Google web-session credentials.
Embedded Android WebView sign-in is not a dependable supported login route.
This first test therefore imports a locally prepared login file through the
Android system file picker.

Follow the [local pairing preparation instructions](auth/README.md) on your
computer. The included helper only parses the copied request locally; it never
executes the request or uploads credentials. Transfer the generated file directly
to your phone, import it into **RCS Bridge Test**, and explicitly start pairing.
When the app shows a verification symbol, switch to Google Messages and confirm
the matching symbol, then return to the test app.

The imported file contains sensitive Google session credentials. Do not put it
in GitHub, chat, screenshots, email, or a public sharing link. Delete the transfer
copies after import. The app stores its session encrypted with Android Keystore,
disables Android backup, and does not display or log the imported credentials.
**Disconnect** stops the connection while keeping the saved session.
**Forget** removes the local session. Also use Google Messages' Device Pairing
screen to remove an old linked session if necessary.

The first prototype rejects ambiguous accounts with multiple eligible phones
instead of silently pairing a different phone. It requires the Google account
used by Google Messages. The phone must remain connected to the internet.

## Phone acceptance test

Use an existing one-to-one conversation that already works as RCS in Google
Messages. This avoids mixing new-conversation setup with the initial bridge test.

1. Pair and connect. Confirm that your conversations and available SIMs load.
2. Select the intended SIM explicitly and open an existing RCS conversation.
3. Send one short test message to a number you control or a willing test contact.
4. In Google Messages, verify that the message was actually sent as **RCS**.
   A successful request or an "accepted" state is not proof of delivery or RCS.
5. Reply by RCS from that other phone and confirm that the reply appears in the
   test app. Check the displayed message protocol and status.
6. Repeat with the other SIM only if that line already supports RCS in Google
   Messages. Working SMS on both SIMs does not establish RCS on both lines.
7. Disconnect, reopen the app, reconnect, and check that the saved pairing works.

The wrapper requests RCS and does not automatically retry a failed or uncertain
send as SMS. If a sent message is reported as SMS/MMS, record that actual protocol;
do not count it as an RCS pass. Do not repeatedly tap Send after an uncertain
result: check Google Messages first to avoid duplicate messages.

## Conversation list position and action order (0.2.4)

The conversation list remembers its own visible row and pixel offset separately
from message history. Returning with Back restores that position after layout,
and list refreshes retain the visible conversation when row order or heights
change. Notification actions are ordered **Mark as read**, then **Reply**.
The native bridge and sending behavior are unchanged from 0.2.3.

## Notification actions (0.2.3)

New incoming notifications include **Reply** and **Mark as read**. Reply opens
Android's inline text field and submits one RCS-only attempt without opening the
Activity. It uses the conversation's current Google Messages sender SIM, verified
as active and RCS-enabled; the app's last selected SIM is not used as a fallback.
Replies retain the existing journal and request-ID duplicate prevention.

Mark as read verifies and marks through the original incoming message identified
by the notification. It does not mark a newer message that arrived while the
action was running. Normal history loading still does not send read receipts.
Read markers outside the most recent fifty messages must be handled in Google
Messages instead.

Actions use the requested background connection; they do not override Disconnect.
Requests can wait briefly for an existing connection to restore. Reply text stays
in memory and is not persisted or automatically retried after process death.
Status updates distinguish submission from failure or an unconfirmed result, and
old completions cannot remove newer notifications. An interrupted reply asks the
user to check its status before trying again. The reply field accepts up to 4000
characters. Preview settings and generic lock-screen public versions still apply.

Offline tests cover exact read markers, current-SIM reply routing, journal
deduplication, RemoteInput dispatch, malformed input, queue cancellation and
recovery feedback. Live read receipts and inline replies need phone testing.

## Notification navigation and previews (0.2.2)

Notification taps open the newest loaded message, including when the same chat
was already selected in the background. Each explicit navigation has its own
revision so an older in-flight read cannot satisfy a newer opening. The initial
scroll is applied after layout; later updates keep the user's scroll position.

Settings includes **Show message previews**, off by default. Enable it to include
message text and the known conversation name in new incoming notifications.
Previews keep a generic public version for lock-screen redaction; Android's
notification settings control what the device displays. Turning previews off
removes existing incoming notifications but keeps the connection notification.
Only the preference is persisted; message bodies are not stored by this feature.
Diagnostics is now under Settings.

System Back returns from a conversation to the chat list. On API 33 and later,
the app registers the platform back callback only while a conversation is open;
Back at the chat list retains normal Android behavior. The keyboard can consume
the first Back gesture when open.

## Conversation refresh fix (0.2.1)

History reads no longer emit unchanged send-journal events, which previously
caused a repeating history/read/status loop. Read-only refreshes use a separate
coalescing gate, leaving the composer, draft, focus and cursor available while
messages load. Repeated events and old history do not trigger redundant reads,
and unrelated inbox changes do not rebuild the open conversation. Opening a chat
scrolls to its newest loaded message once; subsequent updates preserve the user's
scroll position. Notification behavior is unchanged from 0.2.0.

Regression coverage includes native journal events and persistence, JVM refresh
gates and event filtering, and offline Android checks for typing, initial history
position and scroll preservation. Live traffic on the phone still needs testing.

## Background connection and notifications (0.2.0)

Pair or Connect starts an Android remote-messaging foreground service with an
ongoing connection notification. You can leave the Activity or turn off the
screen. Use Disconnect in the app or ongoing notification to stop it; the saved
pairing remains encrypted on the device. Forget also stops background work.

Allow message notifications when Android asks. Denying permission does not stop
the connection, but incoming alerts will be unavailable; use Enable message
notifications in the app to change the setting. New live incoming RCS messages
produce generic notifications by default, with optional previews in Settings
starting in 0.2.2. Tapping one opens its conversation. The visible conversation, outgoing updates, old
history and duplicate updates do not produce alerts. The in-memory alert filter
accepts recent events (up to five minutes old) since process startup, so it does
not notify an entire history backlog after a restart.

The service requests best-effort restart after process reclamation and resumes
only while the user has requested a connection. It does not start at boot or
bypass Android Stop, force-stop, Doze, or Samsung battery restrictions. Reopen the
app after reboot or an explicit Android stop. Notification permission, screen-off
receipt, power-management behavior and network recovery still need phone testing.

Background acceptance checks:
1. Update the working build in place, tap Connect, and allow notifications.
2. Press Home; receive a new RCS message on each SIM and tap each notification.
3. Repeat with the screen locked, then after Wi-Fi/mobile-data changes.
4. Reopen the app and confirm connection and selected-conversation loading.
5. Disconnect from the notification; verify the service stops and does not
   reconnect on reopening until Connect is tapped.
6. Deny notifications and verify the connection still works and the app's
   Disconnect control remains usable even when offline.

Do not treat a successful APK build as evidence of uninterrupted background
receipt: test the above on the actual phone before main-app integration.

Use **Settings > Diagnostics** when reporting a problem. The copied diagnostic is
intended to include connection state and sanitized errors only, not credentials,
message contents, phone numbers, or conversation identifiers. Screenshots of
this test app are disabled; use that diagnostic text when reporting a problem.

## Scope and remaining uncertainty

- Google Messages stays responsible for the carrier RCS connection.
- The test app does not request SMS permissions or the default SMS role.
- Only existing RCS conversations and plain-text sends are in scope.
- Pairing, same-phone operation, dual-SIM RCS routing, incoming events, and
  reconnect behavior require testing on the actual phone.
- The upstream protocol is unofficial and changes in Google Messages can break
  compatibility. The source and dependency commits are recorded with each build.
- This does not change Apple activation or iMessage registration.

## Source and license

The isolated harness is licensed under **AGPL-3.0-or-later**, matching its
`mautrix-gmessages` dependency. See [LICENSE](LICENSE) and
[the dependency notice](bridge/THIRD-PARTY.md). The existing repository's license
continues to apply to its other code. The published source bundle contains this
harness, build scripts, and the pinned upstream source used to build the APK.

Primary upstream references:

- [Google Messages bridge source](https://github.com/mautrix/gmessages)
- [Supported upstream features](https://github.com/mautrix/gmessages/blob/main/ROADMAP.md)
- [Upstream pairing instructions](https://docs.mau.fi/bridges/go/gmessages/authentication.html)
