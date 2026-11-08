# Remove automatic customer app installation — 8 November 2026

Removed the customer APK downloader, verifier, installer, worker, result receiver, release/report/state models,
scheduling hooks, release-generation script, installer documentation and tests specific to the deleted feature.
Enrollment and authenticated check-ins now handle only locker state, commands and push registration. Existing
manual customer-app / Google Play navigation remains unchanged.

On upgrade, app startup cancels the old unique installation work. Production JSON configuration ignores obsolete
stored state and server metadata, preserving enrollment credentials and lock state. Regression tests cover these
compatibility boundaries; the atomic screen/schedule test and lock/unlock/release tests remain.

Verification: static reference/manifest checks and git diff --check passed. Android assembly, JVM tests, lint and
ktlint could not run here: the environment has Java 17, no Android SDK or Gradle installation, and the GitHub
connector cannot retrieve the binary Gradle wrapper JAR. The project requests Java 25 and Android SDK 37.
GitHub Actions is unavailable and is not used for validation. No build settings or dependencies were changed.

A new signed locker build must be installed on devices for this change to take effect. Existing customer apps
are not uninstalled, and an installation already committed to Android can finish independently of the locker.
