

## Deferred customer app installation — 6 November 2026

Scope: QR setup downloads only the locker. Customer app installation is a separate durable job, first eligible 15 minutes after enrollment, on unmetered connections by default. Enrollment and lock commands never wait for it. No fabricated customer web portal link.

1. Add an optional validated customer release manifest delivered through existing authenticated check-ins; persist sanitized customer-app reports during existing check-ins. Existing devices and unset configuration remain compatible.
2. Extend locker scheduling with unique delayed installation work. Resume HTTP Range downloads, validate package/version/checksum/signing certificate, then use public PackageInstaller sessions and an explicit result receiver. Reconcile sessions after restart; retry transient errors with backoff and surface permanent failures. Stop installation management after release/revocation.
3. Show reported customer-app status separately from successful device enrollment in the existing portal components and tokens.
4. Add targeted tests for manifest validation, range handling, retry/state behavior, enrollment independence, API authorization and portal labels. No extra release request is needed. Run available type, lint, build and test tasks; document SDK/device/network limitations.
5. Prepare reviewable branches/PRs; do not deploy or merge to production during this implementation. Deployment requires a real approved APK and its signing information, which must never be invented.

Use existing dependencies and current public APIs. No dependency migration, deprecated install intents, Accessibility automation, or changes to existing lock/release behavior.

### Implementation and verification
Implemented a separate unmetered-by-default customer installation job, persistent grace period, resumable 256 KiB ranges, validated artifacts and public PackageInstaller sessions. Healthy time-limited transfers continue without exponential failure backoff. Existing check-ins exchange release metadata/reports; no extra release endpoint or customer web portal was added.

Debug APK assembly and all 59 Android JVM/Robolectric tests passed. Full Android lint and ktlint are checked alongside assembly. A pre-existing Compose header emitted two roots; it now has one Column preserving the existing 24dp spacing. Existing UI tests are isolated from Firebase startup with a plain test Application, retaining all assertions. The release metadata tool was exercised on a real disposable signed APK and rejected an unsigned APK.

Physical Device Owner enrollment/silent installation is still required before production rollout. No production signing key, customer APK release configuration, merge, or deployment was changed. Local runtime needs proxy settings for Robolectric artifact downloads; no proxy configuration is committed.

## Skill-guided efficiency review — 6 November 2026

Scope: retain the existing architecture, UI, dependencies, enrollment, device policies and signing. Reduce redundant encrypted state updates, temporary command collections, installer scheduling and APK request round trips. Keep partial-file recovery, range/size validation and bounded worker execution intact.

Implemented one atomic response update for screen/schedule/release metadata, direct command iteration, and no installer scheduling for unchanged permanently blocked releases. Corrected release metadata still unblocks installation. APK requests stream the remaining range with one reusable 16 KiB buffer; shorter valid responses remain supported. Calls are bounded by the remaining four-minute run budget plus 60 seconds so healthy slow streams can yield without first hitting the download client's 180-second default call timeout.

Added regression coverage for atomic metadata updates, blocked/corrected releases, single-request large transfers and shorter server ranges. `git diff --check` passed. Android build, JVM tests, lint and ktlint could not run: Gradle 9.8.0 with Java 25 failed resolving the repository's existing Android Gradle Plugin 9.4.1; SDK Manager also could not find the configured Android 37 platform. No dependency/SDK downgrades or build-setting changes were made. Prior verification above describes earlier work, not this change. Device behavior and performance timings remain unmeasured.
