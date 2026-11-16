# Locker as a customer authenticator

See [the backend security plan](https://github.com/ubaidbadar/qist-backend/blob/main/docs/TRUSTED_DEVICE_AUTH.md) for protocol, threat boundaries and rollout.

Locker registers a separate non-exportable P-256 Android Keystore key using its current enrollment and proof of possession. Registration cannot replace an existing enrollment key. The signature-protected bound service checks the actual Binder caller UID/package and matching app signatures before any coroutine starts. A package name or installed app alone is insufficient. The OS user must be unlocked, and only a signed backend challenge can approve sign-in.

FCM stays a wake-up signal. Locker fetches requests using its own credential. **Qist sign-in** opens a review screen from the launcher; the finance lock screen also exposes a review action when a request is pending. Notification contents contain no code or identity. A remote approval requires entering the three-digit number from the requesting phone; intents cannot approve a request. Optional authentication refresh follows finance check-in and cannot change its successful outcome.

Run `:app:check :app:assembleDebug :app:assembleRelease`. The protocol fixture verifies Java/backend ECDSA compatibility, while the service test rejects an unauthenticated Binder caller. Existing finance enrollment/check-in/lock/release tests remain in the suite.

For a signed test update, manually run **Build Android APK** on this feature branch. It uploads a validation artifact using existing signing secrets and does not publish to Cloudflare. Check the delivered APK certificate against both installed apps, then verify real Android 12 Keystore/Binder, notification-disabled/manual refresh, kiosk review, local first password setup, existing account sign-in, remote approve/deny and lock/unlock regression on an enrolled test phone. Host tests cannot establish these hardware/OS behaviors. Do not reset or release a live financed phone for testing.
