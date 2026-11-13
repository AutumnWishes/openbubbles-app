# Native Android SMS prototype

This change adds a **Phone SMS** screen to OpenBubbles. It reads the Android SMS
provider and sends plain text through an explicitly selected active SIM using
`SmsManager`. These operations do not call Apple IDS or the registration relay.
The SMS screen and iMessage conversations are separate in this first version.

## Current behavior and limits

- Existing SMS history appears in the screen, with bounded pagination.
- Incoming messages written by the default SMS app appear on refresh and through
  a provider observer while OpenBubbles is open.
- Outgoing multipart SMS is tracked in a local SQLite outbox using per-part
  Android sent callbacks. A successful API call alone is not shown as delivery.
- The user explicitly selects a SIM; the implementation does not silently choose
  another active subscription. A client request ID protects against duplicate
  submission of the same request.
- The current default messaging app remains responsible for incoming SMS
  persistence, notifications, MMS, and RCS. This prototype is **not eligible to
  become the Android default SMS app**. It deliberately does not declare a dummy
  MMS receiver that would discard messages.
- MMS attachments, group MMS, RCS, merging SMS into existing iMessage threads,
  and automatic fallback from a failed iMessage are not implemented.
- Native SMS opens in the main app. Android floating chat bubbles use a separate
  engine and do not expose the native SMS entry points in this version.
- Conversations use Android thread/subscription IDs when available. A prefilled
  iMessage contact shortcut is a compose shortcut; it does not promise that an
  international-form number and a national-form history number will be merged.
- Read status inside this screen is local. A non-default app cannot write the
  shared SMS provider's read state or dismiss the default app's notifications.
- `READ_SMS` is a hard-restricted permission. Sideload installer policy and OS
  restrictions may prevent granting it even after a runtime request. A denied
  permission must remain an explicit error; the screen must not pretend that an
  empty inbox proves no messages exist. Android may also restrict access to
  sensitive authentication texts for non-default apps.

Do not select this prototype as a replacement for an established default SMS
app. No default-role selection is offered.

## Isolated Android test app

The harness uses the **same Dart page, service, models, and Kotlin SMS backend**
as the OpenBubbles integration. It avoids the large Apple/Rust dependency graph
so the SMS implementation can be compiled and tested independently.

It has package ID `app.openbubbles.smsprototype` and app label **SMS Prototype**.
It contains no iMessage client. Installing it does not replace the Play Store
OpenBubbles package or import its account data.

Prerequisites used for development:

- Flutter 3.24.0 / Dart 3.5.0
- Java Development Kit 21
- Android SDK platform 36, build tools 34.0.0 and 36.0.0, and platform tools
- Python 3 and a POSIX shell

From the repository root:

```sh
tools/native_sms_harness/run.sh test
tools/native_sms_harness/run.sh apk
tools/native_sms_harness/run.sh apk-release
```

Set `FLUTTER_BIN` if Flutter is not on the path, and configure `JAVA_HOME` and
`ANDROID_SDK_ROOT` for the local toolchains. The script sets `CI=true`, `BOT=true`
and suppresses Flutter analytics. In Flutter 3.24, these CI flags explicitly
short-circuit automatic cloud-host detection in `base/bot_detector.dart`.

The generated harness is under `build/native_sms_harness`. Debug mode produces a
larger multi-ABI APK; release mode produces the smaller ARM64 test APK:

```text
build/native_sms_harness/build/app/outputs/flutter-apk/app-debug.apk
build/native_sms_harness/build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

The GitHub Actions workflow **Build SMS Prototype** runs the same script on
relevant pushes to `feature/native-android-sms` and `rustpush`. It can also be
started with **Run workflow**. It runs the Flutter analysis/tests and the 14
native multipart-status checks through the existing Kotlin Gradle toolchain,
then uploads the ARM64 release-mode test APK as **SMS-Prototype-arm64-test**.
It needs no Apple account, relay code, signing secret, or Rust submodule checkout.
The workflow bootstraps the pinned Flutter 3.24.0 Git commit used for the local
build, avoiding dependence on an archived Flutter SDK download URL.

On GitHub, open **Actions → Build SMS Prototype → a successful run → Artifacts**
and download **SMS-Prototype-arm64-test**. Extract the ZIP to obtain the APK.
The artifact also contains its SHA-256 checksum, source commit, and build number.
Local builds default to Android version code `2001`; set
`PROTOTYPE_BUILD_NUMBER` to override it. GitHub builds use `2001 + run_number`.
The inherited **Full OpenBubbles upstream build** workflow is manual-only in this
fork because its Apple activation dependencies are not supplied by this SMS
project. A successful prototype build does not verify the full OpenBubbles APK.

Both build modes use a generated Android debug/test signing key. Release mode
optimizes the binary; it does not make this a verified production release.
Fresh GitHub-hosted runners can generate a different test signing key. An APK
with a different key cannot update an earlier prototype installation in place;
removing the prototype clears its local outbox/read state. This applies only to
**SMS Prototype**, not the separately installed OpenBubbles application.
No cellular transmission has been validated until a physical-phone test succeeds.

The pure Kotlin multipart status tests can also run without Flutter or Android:

```sh
kotlinc android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/NativeSmsStatus.kt \
  android/app/src/test/kotlin/com/bluebubbles/messaging/services/sms/NativeSmsStatusTest.kt \
  -include-runtime -d /tmp/native-sms-status-tests.jar
java -jar /tmp/native-sms-status-tests.jar
```

The debug harness uses Flutter's standard development networking support; it is
not claimed to have no network permission. The SMS backend itself makes no HTTP
requests and does not send message content to a remote service. Android backup
is disabled for the harness because its local outbox contains message content.

## Full OpenBubbles build and the existing installation

The source baseline is commit `eed1b6332efbb17adbf5ebfa2263ad770169f75e`, with the
pinned Rust submodule `a7fab473e7a33325a760635285db2860de8e1cb0`.

The upstream production application ID is `com.openbubbles.messaging`. A custom
APK signed with a different key cannot update the Play Store app in place.
Uninstalling the working app to overcome that signature mismatch would remove
its private app data; do not use that as an installation procedure.

The upstream `alpha` flavor uses `com.bluebubbles.messaging.alpha`, allowing a
separate installation. It has separate storage and Android Keystore state, so
working iMessage registration is not automatically inherited. Sharing a relay
identity between installations is not validated by this SMS change.

Public source has additional build/activation limits:

- `rustpush/src/activation.rs` embeds files under `certs/fairplay`, which are not
  in the public checkout. Upstream CI substitutes legacy certificates for build
  tests; the checked-in legacy certificate expired on October 11, 2024. A build
  using those substitutions is not evidence of working fresh Apple activation.
- The public OpenAbsinthe dependency is explicitly a mock implementation.
  Relay-based validation uses `get-validation-data` instead of that local mock.
- In this pinned checkout, the relay client calls `get-version-info` and
  `get-validation-data`. Fresh APS activation still calls the local activation
  signer. A relay server providing additional `activate`/`sign-data` endpoints
  does not establish that this client calls them.
- Upstream CI itself notes an `ffmpeg_kit_flutter_new` build problem. The isolated
  SMS harness avoids that unrelated dependency.

The SMS implementation does not alter these Apple activation paths, replace
certificates, reset a relay, or copy account tokens.

## Device acceptance tests

Use a development phone/emulator first. Sending from a real SIM is a real carrier
SMS and may incur ordinary carrier charges. Compose test messages intentionally.

1. Deny permissions. Confirm a clear error and no message send attempt.
2. Grant SMS and phone-state permissions. Confirm history and pagination.
3. With two active SIMs, verify that compose requires the intended SIM and a
   missing/inactive subscription fails without switching to the other line.
4. Send to a consenting test recipient. Check one carrier message and one local
   entry; repeatedly tapping Send must not duplicate the request.
5. Test a multipart Unicode text and confirm failures in any part never produce
   a successful final status.
6. Disable cellular service before sending. Confirm failure or indeterminate
   status, never invented delivery. Do not automatically retry an uncertain send.
7. Receive SMS while the app is open, backgrounded, and stopped. Confirm the
   default app still stores it and displays notifications, and this screen sees
   it after reopening.
8. Confirm the default app still receives MMS and RCS. This screen must not
   advertise those message types as supported.
9. Restart the phone/app with an outstanding send and inspect durable status.
10. Confirm working iMessages in the existing OpenBubbles installation still work.

For true default-SMS support, the next implementation must handle MMS delivery,
download, storage, notifications, and reply intents before declaring the Android
role components. Eligibility alone is insufficient.

## Validation performed

- Focused Flutter analysis of the shared SMS page, models, service, and tests:
  no issues found.
- 22 Flutter tests passed: 6 model tests, 9 mocked platform-channel service tests,
  and 7 widget tests. Cases include permission revocation, pagination, choosing a
  SIM, repeated send taps, no automatic send from a prefilled draft, retry request
  IDs, and a keyboard-constrained phone viewport.
- The native Kotlin SMS backend compiled against Android API 36 and the Flutter
  3.24 embedding. Fourteen standalone Kotlin multipart-status checks passed.
- The same 14 checks also passed through the harness's
  `:sms-status-checks:run` Gradle task used by GitHub Actions.
- Both the isolated debug APK and the smaller ARM64 release-mode test APK built
  successfully with Gradle 8.9, Android Gradle Plugin 8.6.0, Kotlin 2.0.0, Java 21,
  and Flutter 3.24.0. This validates packaging of the shared SMS source in the test
  application, not the full OpenBubbles app.
- The delivered release-mode APK is ARM64 only, has no Internet permission, is
  not debuggable, disables Android backups, and keeps the sent-status receiver
  private. Its Android v2 signature validates with the generated test key.
  `libapp.so` and `libflutter.so` have 64 KiB ELF load alignment, and the APK passes
  `zipalign -c -P 16`; these packaging checks support 16 KiB page compatibility.
- The six edited existing OpenBubbles Dart integration files parsed with the Dart
  formatter, and `git diff --check` passed. The complete OpenBubbles dependency
  graph was not type-checked or built.
- The feature patch passed `git apply --check` against a fresh detached checkout
  of the source baseline.
- No physical carrier send, incoming SMS instrumentation test, or iMessage
  regression test was performed. The available Linux host has no `/dev/kvm` for
  an accelerated Android emulator.
