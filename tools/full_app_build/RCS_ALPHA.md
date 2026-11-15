# OpenBubbles RCS Alpha

This experimental full-app build brings the device-tested RCS Bridge Test 0.2.4
connection, background service and notification actions into OpenBubbles. It
adds a shared presentation inbox without migrating or deleting provider data.

## Install and connect

The ARM64 APK uses package `com.bluebubbles.messaging.rcsalpha` and the label
**OpenBubbles RCS Alpha**. Install it alongside your working OpenBubbles and
RCS Bridge Test apps. It has a separate Android data sandbox and pairing.
CI uses a disposable test signing key; APKs from different CI runs may not be
update-compatible. Do not uninstall a working app to solve that conflict.

Open **Use SMS and RCS without iMessage setup**, or **SMS and RCS inbox** in
OpenBubbles settings. Open **RCS connection and notifications**, import the
local login JSON made by `tools/rcs_bridge_harness/auth/prepare_login.py`, and
confirm the matching symbol in Google Messages. The helper supports the
`/web/config?pli=1` request and `--clipboard`.

Keep the login JSON local. It is never included in source, build artifacts,
diagnostics, or GitHub. The native bridge encrypts its saved session with the
Android keystore. The test app's private session is not copied automatically.

Keep Google Messages as the default messaging app with RCS connected. Allow
OpenBubbles SMS access and notification access when prompted. Connecting RCS
starts its foreground service; Disconnect stops it. A force-stop or OS battery
restriction can still interrupt the background connection.

## Inbox and routing

Once RCS is paired, the normal Android inbox shows iMessage, local SMS and RCS.
The original iMessage inbox, archive/search/new-message tools and full rich
conversation view remain accessible from the inbox menu.

- A single-person thread is grouped only when both the exact local sending
  identity and exact recipient match. International phone numbers are normalized
  only for visual punctuation; country codes are never guessed. Email matching
  is case-normalized. Display names and number suffixes are never identities.
- Unknown identities and group chats retain provider-specific threads. Multiple
  SIM choices within one RCS provider conversation remain one row.
- Provider message IDs are preserved and qualified by provider. Identical text
  at similar times is not discarded. The Android SMS provider supplies SMS;
  Google Messages SMS copies are excluded when local SMS access is available.
- Initial route preference is iMessage, then RCS, then local SMS. Multiple
  routes for a service require a choice. Once a route is selected, connection
  changes never silently switch it to another service or SIM.
- RCS/SMS text can be sent from the combined conversation. Selecting iMessage
  opens the existing full iMessage conversation for composing and media.
  iMessage activation, sending, notifications and database records use the
  existing implementation.
- Notification taps open the current conversation at the newest loaded message.
  Android Back returns to an inbox. Notifications offer **Mark as read**, then
  **Reply**, with Android inline reply. Message previews are opt-in.

## Current limits

This is an integration alpha, not a feature-complete replacement for either
messaging app. RCS supports text in existing Google Messages conversations;
new RCS threads, RCS media sending and a full older-history cursor are not yet
implemented. Recent RCS history is limited by the bridge's recent-message API.
Use the full iMessage view for attachments, reactions and advanced features.
MMS media appears as a placeholder in the shared view, not a downloaded asset.

When local SMS access is allowed, a Google thread currently classified as SMS
is attached as read-only history only if its exact local and remote identities
match a native SMS thread. This retains earlier RCS/MMS in matched threads.
Earlier RCS in unmatched, now-SMS Google threads is not shown in this alpha.
Nothing is deleted from Google Messages or the SMS provider.

Public legacy Apple activation fixtures are used only to compile the public
build. A successful APK build does not validate fresh Apple/iMessage activation.
Live paired RCS, both SIMs, iMessage interoperability, notification navigation,
and background delivery in this new package require device testing. The prior
standalone test app's successful device tests do not prove the full integration.

## Validation and build

`tools/full_app_build/run.sh` binds the pinned Go bridge, runs native bridge and
Android policy checks, runs all Flutter tests, builds the full ARM64 APK, runs
integrated Android unit tests and verifies the APK package/contents. The GitHub
Actions workflow runs for PRs targeting `rustpush` and publishes an APK artifact.

Use Java 21, Go 1.27.2, Gradle 8.9, Flutter 3.24.0 at the workflow's pinned
commit, Android API 36, NDK 27.2.12479018 and the pinned public submodules. No
Google sign-in, actual carrier send or read receipt is performed by build/tests.
