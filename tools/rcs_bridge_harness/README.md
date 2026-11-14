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

This is a foreground connectivity test. Keep the test app open for live updates;
use refresh if needed. Briefly switching to Google Messages for pairing is
supported by keeping the client outside the Activity. Android can still kill
the process. Background notifications and long-running background reliability
are not implemented or claimed by this test.

Use **Diagnostics** when reporting a problem. The copied diagnostic is
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
