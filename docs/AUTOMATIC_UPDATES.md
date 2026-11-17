# Locker background updates

Version 0.2.2 (versionCode 4) adds update checks for the public Cloudflare APK configured by `qist.lockerUpdateApkUrl`. The default is the existing `android/qist.apk` upload URL. No backend environment variable or separate JSON manifest is required for Locker self-updates.

## Rollout

Install this signed updater-enabled version once on existing enrolled phones, using an ordinary update with the same application ID and signing key. Enrollment, encrypted state, ownership and finance lock state are preserved. An older Locker that has no updater cannot acquire this feature merely because the APK in Cloudflare changed. Do not reset or re-enroll a financed phone to update it.

For subsequent releases, increase `versionCode`, keep the signing identity, and replace the APK at the configured URL. A new strong ETag and Content-Length are required; Cloudflare R2 supplies them. No credentials are sent to the public download host. Metadata uses conditional HEAD requests; downloads resume with Range and If-Match and never combine bytes from different uploads. The APK is bounded to 200 MiB and checked for package, version, size, hash and signing identity before PackageInstaller independently verifies it. Android 7/8 rely on the system installer for archive signature verification; installed signing identity remains pinned. Same or lower versions cannot reinstall or downgrade.

WorkManager checks approximately hourly with connectivity and storage constraints. Reboot, app replacement and successful finance check-ins restore scheduling. Android and OEM battery policies can delay execution; this is not an exact-time delivery guarantee. Network failures back off. Invalid artifacts wait for a changed upload. Downloads are independent of financial check-ins and cannot turn a completed lock/unlock/release command into a retry.

Self-install waits while the finance lock is active and rechecks enrollment/release/lock state immediately before commit. Released or unenrolled phones cannot start updates. Customer and Locker APKs use separate persisted installation slots, files and receivers. A stale callback cannot overwrite the other app's installation or finance state. If Android requires user action, the installer records `needs_attention` instead of launching an unattended prompt or claiming success.

## Verification limits

Host tests cover conditional HEAD, range resume, overwrite protection, rejected ranges, time-budget continuation, archive hash/package/version/signer checks, old-state migration, callback isolation, released devices and finance-lock deferral. Archive-parser/signing tests use Android package-manager shadows; these do not replace the system installer's real cryptographic verification. Release compilation and lint check platform APIs. Real Vivo Android 12 installation, owner retention, process replacement, OEM scheduling and signed Cloudflare-to-device updates still require the enrolled test handset. Keep an update-compatible signed APK available for that bootstrap check.
