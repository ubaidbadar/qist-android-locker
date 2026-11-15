# Qist Device Owner release emulator QA

This is an isolated QA harness. It does not connect to the production Qist backend, alter a customer phone, merge code, or deploy an APK.

## What it verifies

1. Installs the debug locker and its instrumentation APK on a disposable Android emulator.
2. Provisions the locker as Device Owner using Android's shell identity from UiAutomation.
3. Applies the same policy set used by the release build, including USB-debugging restriction.
4. Simulates the locked state: network restrictions, lock-task allow-list and persistent Home activity.
5. Calls the real DpmDevicePolicy.release() implementation.
6. Verifies every Qist-owned restriction is cleared, uninstall blocking is off, kiosk allow-list is empty and Qist is no longer the Home activity.
7. Calls relinquish() and verifies DevicePolicyManager.isDeviceOwnerApp() is false.
8. From the host, asks Android PackageManager to uninstall the locker and verifies pm path no longer returns a package path.
9. Saves Gradle output, instrumentation output, logcat, dumpsys device_policy, dumpsys user, package details and a screenshot.

The test also checks that a failed relinquish keeps the app in Releasing state and that the next check-in retries without reapplying protections. That part is covered by the JVM unit tests.

## One-time Mac setup

Use your Apple Silicon Mac (M2 Pro) and Android Studio.

1. Install Android Studio and JDK 17.
2. In Android Studio, open **Tools → Device Manager → Create Device**.
3. Choose a Pixel phone profile.
4. Download and select **Android 16 / API 36 / Google APIs / arm64-v8a**.
5. Install the Android SDK platform required by this project (API 37) and Android SDK Platform Tools.
6. Ensure the Android SDK path is ~/Library/Android/sdk, or export ANDROID_SDK_ROOT.

Do not use a physical phone for this script. The script only accepts an ADB emulator serial and refuses to select a physical device.

## Before every run

In Android Studio Device Manager:

1. Stop the QA AVD if it is already running.
2. Open its three-dot menu and choose **Wipe Data**.
3. Start the AVD. When the Android Setup Wizard appears, leave it at the initial welcome/setup screen; do not complete setup or add a Google account.
4. Wait until Android reports boot completion, but do not finish Setup Wizard. Device Owner provisioning is only allowed as part of device setup, before setup completes.
5. Make sure this is the only running emulator. Physical phones may remain connected; the script will ignore them.

Wiping the AVD is intentionally a visible Device Manager operation. The harness does not use deprecated AVD-management commands or silently wipe any device.

## Run locally

From the repository root, after the clean emulator has booted:

    chmod +x scripts/qa/device-owner-release-macos.sh
    ./scripts/qa/device-owner-release-macos.sh

The default expected system image is API 36. For another API level:

    QIST_EXPECTED_API_LEVEL=35 ./scripts/qa/device-owner-release-macos.sh

If multiple emulators are online, select the exact QA emulator:

    QIST_EMULATOR_SERIAL=emulator-5554 ./scripts/qa/device-owner-release-macos.sh

The script checks the actual API level and arm64-v8a ABI before installing anything. It refuses to continue if the AVD already has a Device Owner. Evidence is written under build/reports/device-owner-release/.

## Optional GitHub Actions on this Mac

The workflow is manual-only and uses a self-hosted macOS ARM64 runner. To enable it:

1. Open the repository's **Settings → Actions → Runners → New self-hosted runner**.
2. Select macOS and ARM64, then follow GitHub's displayed installation commands on this Mac.
3. Keep the runner online in the user's logged-in macOS session and make sure Android SDK/JDK 17 are available to it.
4. Before dispatching the workflow, wipe and start the clean AVD in Android Studio Device Manager.
5. Run **Actions → Device Owner release emulator (self-hosted Mac) → Run workflow**.

GitHub-hosted macOS ARM64 runners are not used for this workflow because GitHub documents that nested virtualization is unsupported there. The local Mac is the actual emulator host.

## Important Android platform caveat

Android's public API reference marks DevicePolicyManager.clearDeviceOwnerApp() deprecated since API 26 and says it is intended for testing only. It also says cleanup of previously applied policies is best-effort. This test therefore establishes what the selected AOSP emulator actually does; it does **not** turn this API into a supported production Device Owner offboarding mechanism, nor does an AOSP pass prove behaviour on Tecno, Vivo or Infinix firmware.

Do not merge the production release changes until the emulator evidence is reviewed and the offboarding design is accepted. Physical OEM testing remains a separate release gate.
