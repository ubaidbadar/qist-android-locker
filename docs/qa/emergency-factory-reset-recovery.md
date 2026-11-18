# One-device emergency factory-reset recovery

This branch exists only to recover the Nokia X20 whose Qist test database was deleted while the phone was still enrolled.

## Behaviour

- Build with `-Pqist.emergencyResetOnUnauthorized=true`.
- When the installed locker next reaches the Qist API and receives HTTP 401, it calls the existing, verified `DevicePolicy.release()` implementation locally.
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

After installation, connect the Nokia to the internet and allow a check-in. The backend's 401 response is the trigger. Once the app has cleared and verified the policies, open Android Settings and use the normal factory-reset option.

## Important isolation rules

- Do not merge this branch into `main`.
- Do not publish this APK to the normal Cloudflare object at `/android/qist.apk`; existing enrolled phones use that shared auto-update URL.
- Do not use a different signing key. A signature mismatch cannot update the installed Device Owner package.
- This branch is not a customer release. Any phone running this emergency build will clear its Qist restrictions when its API check-in is unauthorized.
- No backend change is required; the existing API already returns 401 when the deleted device record/credential is unknown.
