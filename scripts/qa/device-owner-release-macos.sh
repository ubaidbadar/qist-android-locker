#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

PACKAGE_NAME="pk.qist.device"
TEST_RUNNER="pk.qist.device.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="pk.qist.device.deviceadmin.DeviceOwnerReleaseTest"
EXPECTED_API="${QIST_EXPECTED_API_LEVEL:-36}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
REPORT_DIR="$ROOT_DIR/build/reports/device-owner-release/$(date +%Y%m%d-%H%M%S)"
SERIAL=""
TEST_STATUS="not-started"

mkdir -p "$REPORT_DIR"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

collect_diagnostics() {
  local exit_code=$?
  if [[ -n "$SERIAL" ]]; then
    "$ADB" -s "$SERIAL" logcat -d -v threadtime > "$REPORT_DIR/logcat.txt" 2>&1 || true
    "$ADB" -s "$SERIAL" shell dumpsys device_policy > "$REPORT_DIR/device-policy.txt" 2>&1 || true
    "$ADB" -s "$SERIAL" shell dumpsys user > "$REPORT_DIR/user-manager.txt" 2>&1 || true
    "$ADB" -s "$SERIAL" shell dumpsys package "$PACKAGE_NAME" > "$REPORT_DIR/package.txt" 2>&1 || true
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$REPORT_DIR/final-screen.png" 2>/dev/null || true
  fi

  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'test_status=%s\n' "$TEST_STATUS"
    printf 'expected_api=%s\n' "$EXPECTED_API"
    printf 'serial=%s\n' "${SERIAL:-not-selected}"
    printf 'timestamp=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$REPORT_DIR/summary.txt"

  printf '\nEvidence directory: %s\n' "$REPORT_DIR"
}
trap collect_diagnostics EXIT

[[ "$(uname -s)" == "Darwin" ]] || fail "Run this harness on macOS. The target machine is your Apple Silicon Mac."
[[ "$(uname -m)" == "arm64" ]] || fail "This harness expects an Apple Silicon Mac (arm64)."
[[ -x "$ADB" ]] || fail "ADB not found at $ADB. Install Android SDK Platform Tools or set ANDROID_SDK_ROOT."
command -v java >/dev/null 2>&1 || fail "Java is missing. Install JDK 17."
[[ -x "$ROOT_DIR/gradlew" ]] || fail "Gradle wrapper is missing or not executable."

if [[ -n "${QIST_EMULATOR_SERIAL:-}" ]]; then
  SERIAL="$QIST_EMULATOR_SERIAL"
  [[ "$SERIAL" == emulator-* ]] || fail "QIST_EMULATOR_SERIAL must identify an Android emulator, never a physical device."
  "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }' || fail "Emulator $SERIAL is not online in ADB."
else
  ONLINE_EMULATORS="$("$ADB" devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1 }')"
  [[ -n "$ONLINE_EMULATORS" ]] || fail "No online emulator found. Wipe and start the disposable AVD in Android Studio Device Manager."
  [[ "$(printf '%s\n' "$ONLINE_EMULATORS" | wc -l | tr -d ' ')" == "1" ]] || fail "More than one emulator is online. Set QIST_EMULATOR_SERIAL to the exact QA emulator."
  SERIAL="$ONLINE_EMULATORS"
fi

"$ADB" -s "$SERIAL" wait-for-device
BOOTED=""
for _ in $(seq 1 180); do
  BOOTED="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$BOOTED" == "1" ]] && break
  sleep 2
done
[[ "$BOOTED" == "1" ]] || fail "Android did not finish booting."

ACTUAL_API="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
ACTUAL_ABI="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ACTUAL_API" == "$EXPECTED_API" ]] || fail "Expected Android API $EXPECTED_API, got $ACTUAL_API."
[[ "$ACTUAL_ABI" == "arm64-v8a" ]] || fail "Expected arm64-v8a system image, got $ACTUAL_ABI."

"$ADB" -s "$SERIAL" shell dumpsys device_policy > "$REPORT_DIR/device-policy-before.txt"
if grep -Eiq 'Device Owner.*ComponentInfo' "$REPORT_DIR/device-policy-before.txt"; then
  fail "This AVD already has a Device Owner. Wipe its data in Device Manager, reboot it, and retry."
fi

printf 'Building unit tests, lint, app APK and instrumentation APK...\n'
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest 2>&1 | tee "$REPORT_DIR/gradle-build.log"

APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || fail "Debug APK was not produced: $APP_APK"
[[ -f "$TEST_APK" ]] || fail "Instrumentation APK was not produced: $TEST_APK"

printf 'Installing Qist debug APK and instrumentation APK on %s...\n' "$SERIAL"
"$ADB" -s "$SERIAL" install -r "$APP_APK" | tee "$REPORT_DIR/install-app.txt"
"$ADB" -s "$SERIAL" install -r "$TEST_APK" | tee "$REPORT_DIR/install-test.txt"

printf 'Running real DevicePolicyManager release test...\n'
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class "$TEST_CLASS" \
  "$TEST_RUNNER" 2>&1 | tee "$REPORT_DIR/instrumentation.txt"
TEST_STATUS=${PIPESTATUS[0]}
set -e
[[ "$TEST_STATUS" -eq 0 ]] || fail "Instrumentation test failed (exit $TEST_STATUS)."

if grep -Eiq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$REPORT_DIR/instrumentation.txt"; then
  fail "Instrumentation output contains a failure marker."
fi

printf 'Checking the actual package uninstall after Device Owner release...\n'
UNINSTALL_OUTPUT="$("$ADB" -s "$SERIAL" shell pm uninstall "$PACKAGE_NAME" 2>&1 | tr -d '\r')"
printf '%s\n' "$UNINSTALL_OUTPUT" | tee "$REPORT_DIR/uninstall.txt"
[[ "$UNINSTALL_OUTPUT" == *"Success"* ]] || fail "PackageManager did not uninstall Qist after release."

PACKAGE_PATH="$("$ADB" -s "$SERIAL" shell pm path "$PACKAGE_NAME" 2>/dev/null | tr -d '\r')"
[[ -z "$PACKAGE_PATH" ]] || fail "Qist package is still present after uninstall: $PACKAGE_PATH"

TEST_STATUS="passed"
printf '\nPASS: Device Owner removal was verified and PackageManager successfully uninstalled Qist.\n'
printf 'The emulator is left running for manual inspection. Evidence: %s\n' "$REPORT_DIR"
