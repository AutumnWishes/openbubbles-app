#!/usr/bin/env bash
set -euo pipefail

# Compile the same SMS source used by OpenBubbles in a separate test app.
# It never loads RustPush or changes the installed OpenBubbles application.
export CI=true
export BOT=true
export FLUTTER_SUPPRESS_ANALYTICS=true
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS_DIR="$ROOT_DIR/build/native_sms_harness"
FLUTTER_COMMAND="${FLUTTER_BIN:-flutter}"
MODE="${1:-test}"
case "$MODE" in test|apk|apk-release) ;; *) echo "Usage: $0 [test|apk|apk-release]" >&2; exit 2 ;; esac
PROTOTYPE_BUILD_NUMBER="${PROTOTYPE_BUILD_NUMBER:-2001}"
if [[ ! "$PROTOTYPE_BUILD_NUMBER" =~ ^[1-9][0-9]{0,8}$ ]]; then
  echo "PROTOTYPE_BUILD_NUMBER must be a positive integer of at most nine digits." >&2
  exit 2
fi

"$FLUTTER_COMMAND" --suppress-analytics --no-version-check create \
  --platforms=android --android-language=kotlin --org app.openbubbles \
  --project-name smsprototype --no-pub "$HARNESS_DIR"

mkdir -p "$HARNESS_DIR/lib/services/native_sms" \
  "$HARNESS_DIR/lib/app/layouts/settings/pages" \
  "$HARNESS_DIR/test/services/native_sms" \
  "$HARNESS_DIR/android/sms-status-checks/src/main/kotlin" \
  "$HARNESS_DIR/android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms"
cp "$ROOT_DIR/lib/services/native_sms/"*.dart "$HARNESS_DIR/lib/services/native_sms/"
cp "$ROOT_DIR/lib/app/layouts/settings/pages/native_sms_page.dart" "$HARNESS_DIR/lib/app/layouts/settings/pages/"
cp "$ROOT_DIR/test/services/native_sms/"*.dart "$HARNESS_DIR/test/services/native_sms/"
cp "$ROOT_DIR/android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/"*.kt \
  "$HARNESS_DIR/android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/"
cp "$ROOT_DIR/tools/native_sms_harness/main.dart" "$HARNESS_DIR/lib/main.dart"
cp "$ROOT_DIR/tools/native_sms_harness/MainActivity.kt" \
  "$HARNESS_DIR/android/app/src/main/kotlin/app/openbubbles/smsprototype/MainActivity.kt"
cp "$ROOT_DIR/tools/native_sms_harness/status_checks.gradle" "$HARNESS_DIR/android/sms-status-checks/build.gradle"
cp "$ROOT_DIR/android/app/src/main/kotlin/com/bluebubbles/messaging/services/sms/NativeSmsStatus.kt" \
  "$ROOT_DIR/android/app/src/test/kotlin/com/bluebubbles/messaging/services/sms/NativeSmsStatusTest.kt" \
  "$HARNESS_DIR/android/sms-status-checks/src/main/kotlin/"
rm -f "$HARNESS_DIR/test/widget_test.dart"

cat > "$HARNESS_DIR/pubspec.yaml" <<'EOF'
name: bluebubbles
description: Isolated test harness for OpenBubbles native SMS source.
publish_to: none
version: 0.1.0+2001
environment:
  sdk: '>=3.5.0 <4.0.0'
dependencies:
  flutter:
    sdk: flutter
dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^4.0.0
flutter:
  uses-material-design: true
EOF

python3 - "$HARNESS_DIR" <<'PY'
from pathlib import Path
import sys
import re
p = Path(sys.argv[1])
manifest = p / 'android/app/src/main/AndroidManifest.xml'
s = manifest.read_text().replace('android:label="smsprototype"', 'android:label="SMS Prototype" android:allowBackup="false"')
permissions = '\n'.join(f'    <uses-permission android:name="android.permission.{name}" />' for name in ('READ_SMS', 'SEND_SMS', 'READ_PHONE_STATE'))
if 'android.permission.READ_SMS' not in s:
    s = s.replace('    <application', permissions + '\n    <uses-feature android:name="android.hardware.telephony" android:required="false" />\n    <application', 1)
if 'NativeSmsSentReceiver' not in s:
    s = s.replace('        <activity', '        <receiver android:name="com.bluebubbles.messaging.services.sms.NativeSmsSentReceiver" android:exported="false" />\n        <activity', 1)
manifest.write_text(s)
gradle = p / 'android/app/build.gradle'
s = gradle.read_text().replace('compileSdk = flutter.compileSdkVersion', 'compileSdk = 36').replace('minSdk = flutter.minSdkVersion', 'minSdk = 24').replace('targetSdk = flutter.targetSdkVersion', 'targetSdk = 36')
s = s.replace('    ndkVersion = flutter.ndkVersion\n', '')
gradle.write_text(s)
settings = p / 'android/settings.gradle'
s = re.sub(r'(id "com.android.application" version )"[^"]+"', r'\1"8.6.0"', settings.read_text())
s = re.sub(r'(id "org.jetbrains.kotlin.android" version )"[^"]+"', r'\1"2.0.0"', s)
if 'id "org.jetbrains.kotlin.jvm"' not in s:
    s = s.replace('id "org.jetbrains.kotlin.android" version "2.0.0" apply false', 'id "org.jetbrains.kotlin.android" version "2.0.0" apply false\n    id "org.jetbrains.kotlin.jvm" version "2.0.0" apply false')
if 'include ":sms-status-checks"' not in s:
    s += '\ninclude ":sms-status-checks"\n'
settings.write_text(s)
wrapper = p / 'android/gradle/wrapper/gradle-wrapper.properties'
s = re.sub(r'gradle-[0-9.]+-(all|bin)\.zip', 'gradle-8.9-bin.zip', wrapper.read_text())
wrapper.write_text(s)
PY

cd "$HARNESS_DIR"
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check pub get
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check analyze --no-pub
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check test --no-pub
if [[ "$MODE" != "test" ]]; then
  (cd android && ./gradlew --no-daemon :sms-status-checks:run)
fi
if [[ "$MODE" == "apk" ]]; then
  "$FLUTTER_COMMAND" --suppress-analytics --no-version-check build apk --debug --no-pub --target-platform android-arm64 --build-number "$PROTOTYPE_BUILD_NUMBER"
elif [[ "$MODE" == "apk-release" ]]; then
  # The generated Flutter template deliberately signs this test app with a debug key.
  "$FLUTTER_COMMAND" --suppress-analytics --no-version-check build apk --release --no-pub --target-platform android-arm64 --split-per-abi --build-number "$PROTOTYPE_BUILD_NUMBER"
fi
