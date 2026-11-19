# One-device emergency factory-reset recovery

This branch exists only to recover the Nokia X20 whose Qist test database was deleted while the phone was still enrolled.

## Behaviour

- Build with `-Pqist.emergencyResetOnUnauthorized=true`.
- If the installed locker next reaches the Qist API and receives HTTP 401, it calls the existing, verified `DevicePolicy.release()` implementation locally.
- It also performs the same cleanup if the previous production build already saved `Enrolment.Revoked` and stopped check-ins. The package-replaced boot receiver schedules a check-in, and the emergency build handles that saved state without needing another API response.
- That clears Qist's factory-reset, safe-boot, add-user, USB-debugging and network restrictions; removes the uninstall block; disables the Qist Home alias; and empties the lock-task allow-list.
- The app then clears its local device credential/customer state and stops its check-in/update work.
- It deliberately does **not** call the deprecated, test-only `clearDeviceOwnerApp()`. Device Owner remains until Android performs the factory reset; the reset itself removes the Device Owner app registration.

If Android refuses any part of the policy cleanup, the app keeps its local enrolment and retries on the next check-in. It does not claim that reset is available until `DevicePolicy.release()` verifies the cleanup.

## Build the signed recovery APK

Run from the repository's Android project root, using the **same release keystore and alias that signed the APK already installed on the Nokia**:

```bash
./gradlew :app:assembleRelease \
  -Pqist.emergencyResetOnUnauthorized=true \
  -Pqist.signing.storeFile="$HOME/path/to/qist-release.jks" \
  -Pqist.signing.storePassword="$QIST_KEYSTORE_PASSWORD" \
  -Pqist.signing.keyAlias=qist \
  -Pqist.signing.keyPassword="$QIST_KEY_PASSWORD" \
  --no-daemon
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Confirm its signing certificate matches the installed app before attempting an update. The version is bumped to 5 so it can update the current main-branch version 4 installation.

## Install only on the recovery Nokia

Transfer the signed APK to the Nokia and install it as an update over the existing Qist app. Android must accept it as the same package signed by the same certificate. Do not uninstall the current app first.

After installation, keep the Nokia online and allow the package-replaced check-in to run. If its local state is still enrolled, the backend's 401 response is the trigger; if it was already revoked, the build performs cleanup locally without another API response. Once the app has cleared and verified the policies, open Android Settings and use the normal factory-reset option.

This removes Qist's Settings factory-reset restriction; it does not bypass Google account verification/Factory Reset Protection if the phone or its OEM has a separate FRP requirement.

## Important isolation rules

- Do not merge this branch into `main`.
- Do not publish this APK to the normal Cloudflare object at `/android/qist.apk`; existing enrolled phones use that shared auto-update URL.
- Do not use a different signing key. A signature mismatch cannot update the installed Device Owner package.
- This branch is not a customer release. Any phone running this emergency build will clear its Qist restrictions when its API check-in is unauthorized.
- No backend change is required; the existing API already returns 401 when the deleted device record/credential is unknown.

## Verification gates before installing on Nokia

1. Review the branch-only [Nokia emergency recovery QA workflow](../../.github/workflows/emergency-nokia-qa.yml). It runs JVM unit tests, ktlint, lint, both build configurations, and real Device Owner policy cleanup in disposable Android 14/15 emulators. The workflow uses a **throwaway CI keystore**, and it does **not** upload or publish any APK.
2. Check that its **latest branch commit** is green. A green unit test does not replace a real device test; test the production-signed APK on an expendable Device Owner device if possible.
3. Check the version of the Locker installed on the Nokia using App Info, or via `adb shell dumpsys package pk.qist.device` if ADB is available. This recovery branch uses versionCode **5**. The replacement must use the exact package name and same signer, with a version code that Android accepts. Never uninstall the Device Owner to work around a version/signature mismatch.
4. Sign the APK locally using your **original production signing keystore** (not the workflow's throwaway key) and verify it with Android SDK's `apksigner verify --verbose --print-certs`. Compare its signing-certificate SHA-256 against the prior installed Qist APK or the known Qist release certificate.
5. Install only on the test Nokia by tapping the APK to **update in place**; do not clear app data. Watch for Android policy cleanup; if Settings reset remains prohibited, stop and inspect Device Owner policy state rather than factory-resetting through an unverified path.

## QA cases explicitly covered in code

- Emergency mode: `Revoked` is locally released, including when **offline** after an APK replacement.
- Emergency mode: HTTP 401 releases; HTTP 503 and transient network failure **do not release**.
- Failed policy cleanup keeps the saved state and retries; no success reported while effective Android restrictions remain.
- Normal mode: `Revoked` does not call the emergency release routine.
- Never-enrolled devices cannot trigger local recovery.
- The emergency APK never runs normal Cloudflare Locker auto-update jobs; old queued updater jobs exit without installing another APK.
- Real Android emulator test exercises `DevicePolicyManager` and checks both admin-specific and **effective** user restrictions before returning a successful hand-back.

**Limitations:** Android may refuse installation/release on a particular Nokia firmware, and Android's Factory Reset Protection or an OEM policy can still require legitimate account-owner verification after reset. No APK from this branch has been installed or physically tested on your Nokia in this review.
