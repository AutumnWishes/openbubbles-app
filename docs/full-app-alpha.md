# Full OpenBubbles SMS alpha

This development build packages the full public OpenBubbles source with the
native **Phone SMS** screen. It is separate from the smaller **SMS Prototype**
application used to validate the SMS implementation.

## Build and install

The **Build OpenBubbles SMS Alpha** GitHub Actions workflow builds the branch
`feature/full-openbubbles-sms` on relevant pushes and supports manual dispatch.
After a successful run, download **OpenBubbles-SMS-Alpha-arm64-test** from its
Artifacts section. Extract the ZIP and install the ARM64 APK on the test phone.
The archive also includes `SHA256SUMS.txt` and `BUILD.txt` identifying its source.

The application is named **OpenBubbles (Alpha)** and uses package
`com.bluebubbles.messaging.alpha`. It installs alongside the official
`com.openbubbles.messaging` app. Keep the working official installation and its
account data. Alpha has separate storage and does not import its registration.

On the setup screen, choose **Use Phone SMS without iMessage setup** to test the
same SMS screen inside the full app. Grant the requested SMS/phone permissions,
select the intended SIM, and intentionally send a test message. Incoming SMS is
still stored and notified by the existing default SMS app. This alpha does not
become the default SMS app or add MMS/RCS support.

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
the missing FairPlay activation signatures. No Apple credentials or device
identity are embedded by this build workflow.

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
and deprecation notices remain. These checks do not replace native APK packaging
or a device launch test.

The tester confirmed basic carrier SMS sending and incoming reply display on a
physical Android phone using **SMS Prototype** on November 13, 2026. This is
evidence for the shared SMS implementation, not a full-app launch or iMessage
regression test. Full-alpha installation, startup, SMS permissions, carrier send,
incoming refresh, and Apple activation must be checked separately on a phone.
