# Full OpenBubbles SMS alpha

This development build packages the full public OpenBubbles source with the
native **Phone SMS** screen. It is separate from the smaller **SMS Prototype**
application used to validate the SMS implementation.

## Build and install

The **Build OpenBubbles SMS Alpha** GitHub Actions workflow builds the branch
`feature/full-openbubbles-sms` on relevant pushes and supports manual dispatch.
After a successful run, download **OpenBubbles-SMS-Alpha-arm64-test** from its
Artifacts section. Extract the ZIP and install the ARM64 APK on the test phone.
The archive also includes `SHA256SUMS.txt`, `BUILD.txt` identifying its source,
and `APK-CHECKS.txt` with package, signature, architecture, and alignment checks.

The application is named **OpenBubbles (Alpha)** and uses package
`com.bluebubbles.messaging.alpha`. It installs alongside the official
`com.openbubbles.messaging` app. Keep the working official installation and its
account data. Alpha has separate storage and does not import its registration.
The built-in settings and chat backups do not transfer Apple registration.

On the setup screen, choose **Use Phone SMS without iMessage setup** to test the
same SMS screen inside the full app. Grant the requested SMS/phone permissions,
select the intended SIM, and intentionally send a test message. Incoming SMS is
still stored and notified by the existing default SMS app. This alpha does not
become the default SMS app or add MMS/RCS support.

For the first full-app phone test:

1. Open **OpenBubbles (Alpha)** and choose **Use Phone SMS without iMessage setup**.
2. Grant SMS and phone permissions and confirm the intended SIM.
3. Send a short message to a number you control or a willing test contact.
4. Have that number reply by SMS and check that the reply appears in Phone SMS.
5. Close and reopen Alpha and confirm the conversation and send status remain.

Phone SMS is a dedicated screen inside the full app. It is not yet a unified
SMS/iMessage conversation list, and existing iMessage conversations do not
automatically switch to carrier SMS.

The APK is optimized in release mode but signed with a generated Android test
key. Different CI runs can generate different signing keys, so they may not
update an earlier alpha installation in place. Removing **OpenBubbles (Alpha)**
clears only that application's private data; never remove the working official
app to install this alpha.

## Apple activation remains a separate test

The public source is missing its production FairPlay signing material. The
build script uses the upstream public legacy fixtures solely to make compilation
possible. Their certificate has expired. A compiled APK is not proof that fresh
Apple/APNs activation works, and the existing app's working iMessage session does
not validate a new installation.

The public OpenAbsinthe dependency is also a mock. The relay path can supply
validation data, but `relayserver` v0.0.5's Mac identity setting does not supply
the missing FairPlay activation signatures. No user Apple account credentials
or personal device identity are embedded by this build workflow.

## Local build

Initialize the pinned submodules, install Flutter 3.24.0, Java 21, the Android SDK
36, NDK 27.2.12479018, and Rust stable with the `aarch64-linux-android` target.
Protobuf, CMake, and native C/C++ build tools are also required. See the workflow
for the complete CI setup. Then run:

```sh
git -c url.https://github.com/.insteadOf=git@github.com: submodule update --init --recursive
tools/full_app_build/run.sh
```

The script preserves an existing `.env` file, creates empty optional integration
settings if it is absent, and refuses to overwrite non-fixture activation files.
It runs the shared SMS Flutter tests before building the complete APK.

## Test status

The full project's Flutter dependencies resolved without changing `pubspec.yaml`
or its lockfile. The complete `main.dart` Android ARM64 debug bundle compiled,
and all 22 native SMS Flutter tests passed within the full project. Focused
analysis of the integration files found no errors; existing upstream warnings
and deprecation notices remain. The full ARM64 Alpha release APK also compiled
successfully in GitHub Actions. Compilation does not replace a device launch test.

The [full Alpha build #2](https://github.com/AutumnWishes/openbubbles-app/actions/runs/43393216040)
produced the verified download on November 13, 2026, from source commit
`dd55c1bdb10abc7fb1298cbb6a2fb62f5adc5ba5`. Its APK is 157,588,606 bytes, targets
Android API 36, requires API 24 or newer, and contains only ARM64 native libraries.
The final manifest confirms the Alpha label and package, separate provider
authorities, `debuggable=false`, and `allowBackup=false`.

Both the downloaded artifact ZIP digest and the APK checksum matched their
recorded values. Android APK v2 signature verification, ZIP integrity, and
standard/16 KiB ZIP alignment passed. The APK SHA-256 is:

```text
ee8d88b740131685c529e52276cafdd126cef3d092798ed5511ca6b44ddb11cf
```

The APK inspection reported zero packaging errors and 16 warnings for bundled
native libraries with 4 KiB ELF load-segment alignment. This build is not verified
for devices using 16 KiB memory pages; those libraries need validation or
rebuilding before claiming that compatibility. The complete per-library report
is included as `APK-CHECKS.txt` in the artifact.

The tester confirmed basic carrier SMS sending and incoming reply display on a
physical Android phone using **SMS Prototype** on November 13, 2026. This is
evidence for the shared SMS implementation, not a full-app launch or iMessage
regression test. Full-alpha installation, startup, SMS permissions, carrier send,
incoming refresh, and Apple activation must be checked separately on a phone.
