# Deferred customer app installation

QR provisioning still downloads only the locker. Successful enrollment persists a 15-minute grace period. An existing authenticated check-in supplies optional customer release metadata; its worker is separate from enrollment, check-ins and lock commands. No customer web portal is assumed.

The download defaults to an unmetered connection (normally Wi-Fi), requires storage not to be low, and uses HTTP Range requests against an immutable APK URL. Partial files survive worker/process restarts. Transient failures back off; healthy transfers yield after a four-minute transfer budget, with bounded network request timeouts. Healthy partial downloads continue in a fresh job after 30 seconds rather than accumulating exponential failure delays. Android controls scheduling, so 15 minutes is an eligibility delay, not a promised completion time. Keep the phone connected to Wi-Fi after handover.

Each request streams the remaining byte range rather than making a new request every 256 KiB. Servers returning shorter valid ranges are supported. A run reuses one 16 KiB buffer, checks its four-minute budget between reads, and bounds each HTTP call by the remaining budget plus 60 seconds for a blocked read. Existing connection/read timeouts remain active. Check-ins save screen, schedule and release metadata together and do not schedule another installer job for an unchanged permanently blocked APK; corrected metadata clears the block.

The approved package is `pk.qist.customer`. A universal APK is required, not an AAB or one split APK. The worker checks package, version, full-file SHA-256 and available storage. Android 9+ additionally checks the archive's current signing certificate before installation. Android 7/8 use the pinned full-file hash of the signature-verified release artifact without calling deprecated `GET_SIGNATURES`; the platform verifies APK signatures and update compatibility. Installed certificates and version are checked using AndroidX compatibility APIs on every supported version. A download or success callback alone never proves installation.

Public `PackageInstaller.Session` APIs perform installation. An explicit non-exported result receiver records only the matching saved session's outcome; the worker verifies actual installation. Pending sessions are reconciled after restart and abandoned after 30 minutes if still incomplete. Permanent validation/installation errors stop retrying that APK until corrected metadata is published. Low storage and network errors retry. Installation management ends when enrollment is revoked or released. The customer application retains its own data and authentication.

## Publish a release

1. Obtain an approved release universal APK. If Play owns the signing key, download its signed universal APK from Play Console. Do not substitute an APK signed with the upload key. Confirm Play installer protection and the app's own integrity rules allow this authorized distribution channel.
2. Upload it to an immutable HTTPS R2/object URL supporting Range requests; keep previous objects available during rollout.
3. Generate metadata from that exact file using Android SDK Build Tools:

   ```bash
   python3 scripts/customer_release.py customer.apk https://YOUR_DOWNLOAD_HOST/customer/VERSION.apk
   ```

   `apksigner` must verify the APK successfully. Pass `--apksigner` and `--aapt2` with absolute SDK tool paths if they are not on PATH. The script derives package, minimum SDK, version, certificate digest, byte size and SHA-256. It emits JSON for `CUSTOMER_APP_RELEASE_JSON` on the Qist backend. Default `wifiOnly` is true; `--allow-metered` explicitly opts into customers' mobile data.
4. Deploy the backend/portal changes, then the updated signed locker. Existing lockers ignore the optional metadata. Leaving `CUSTOMER_APP_RELEASE_JSON` empty disables customer downloads and leaves QR enrollment unchanged. Set it only after testing the exact APK and signing compatibility.
5. Pilot fresh QR enrollment, poor Wi-Fi, interrupted downloads, reboot during installation, existing compatible app, wrong signer, low storage, lost callback and higher installed version on supported vendor devices. Check that lock/check-in operations continue independently and the portal distinguishes enrollment from customer installation.

## Reports and rollout

Reports travel with existing check-ins: `pending`, `downloading`, `installing`, `installed`, `failed`, `needs_attention`, or `not_configured`, with bounded machine-readable details. The server timestamps reports; the portal displays them as last reported, not real-time guaranteed status. Old lockers show “not reported”. No push or polling loop is added to the download feature.

Use one coherent signing and update channel. Keep monotonically increasing version codes; never uninstall/reinstall to work around a certificate mismatch, because that could destroy customer data. Do not request update ownership by default, so the locker does not intentionally prevent another authorized update channel. Switching between Play and locker updates still needs device testing.

To pause rollout, empty `CUSTOMER_APP_RELEASE_JSON` and restart the backend. Updated phones stop starting further installations after receiving the next check-in response; an already committed Android installation may finish. This is not an immediate remote cancellation mechanism.

## Verification

Run the repository's `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:ktlintCheck` tasks. Unit tests cover range resumption, incorrect range rejection, temporary failure, stale callbacks, release behavior, setup delay and independent lock processing. A physical Device Owner phone is required to certify actual silent installation and manufacturer-specific behavior.
