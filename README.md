# ReleaseShelf

ReleaseShelf is a personal Android release client for APKs published in GitHub Releases. It tracks selected repositories, compares release metadata with installed package versions, downloads private or public assets, verifies them, and hands the APK to Android's system installer.

## Current capabilities

- Material 3 Compose UI with dynamic color, edge-to-edge layout, update filters, loading/error/empty states, and accessible system install handoff.
- Editable `owner/repository` source list, seeded with the local Android app set.
- Public repository access without credentials and private repository access through a fine-grained GitHub token.
- Token encryption with an Android Keystore AES-GCM key; encrypted token preferences are excluded from backup and device transfer.
- Installed-version comparison using monotonic Android `versionCode`, with semantic `versionName` fallback for legacy releases.
- Background APK downloads via WorkManager with a foreground progress notification (optional notification permission on Android 13+).
- Durable APK cache: verified downloads stay on device so you can **Download only**, install later, or retry after an install failure without re-downloading.
- Authenticated private-release downloads with progress, SHA-256 verification, package/version inspection, and installed-signature compatibility checks.
- PackageInstaller session installs that request no user action when Android allows it (after ReleaseShelf is the installer of record). Initial installs and Play Protect interventions can still require confirmation.
- Install progress on release cards and via notifications so silent sessions still show that an install is underway.

Release checks run on app launch and when the user taps **Check now**. Periodic background checks and update-available notifications remain a follow-up milestone.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The project targets Android 17 (API 37), supports Android 10 and newer, and uses the current stable Compose BOM.

## Private repository setup

In **Sources → GitHub access**, add a fine-grained personal access token restricted to the repositories being tracked. Read-only **Contents** access is sufficient for release metadata and assets. The token is never committed to source or included in Android backups.

## Publishing compatible releases

The local `~/.local/bin/androidrun` publishes both the APK and `androidrun-release.json`. The recommended version flow is:

```bash
androidrun --bump patch
git add app/build.gradle.kts
git commit -m "chore: bump version to 0.1.1"
git push
androidrun --publish
```

`minor` and `major` are also supported. `--bump patch --publish` is available for a one-command local publish, but committing the version first keeps the Git tag and source tree aligned.

Publishing now rejects a `versionCode` that is not greater than the latest published release and rejects duplicate canonical `v<versionName>` tags.

## Release metadata contract

Every new release contains an `androidrun-release.json` asset:

```json
{
  "schemaVersion": 1,
  "displayName": "ExampleApp",
  "packageName": "dev.example.app",
  "versionName": "1.2.3",
  "versionCode": 12,
  "minSdk": 29,
  "apkAsset": "ExampleApp-v1.2.3-release.apk",
  "sha256": "<64 lowercase hex characters>"
}
```

ReleaseShelf retains a fallback parser for older `androidrun` release notes, so existing published APKs can still appear.

## Android security constraints

- Verified APKs are staged through `PackageInstaller` sessions with `USER_ACTION_NOT_REQUIRED` and the `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission. After ReleaseShelf becomes the installer of record for a package, eligible subsequent updates may install without the ordinary confirmation screen.
- Initial installs, Play Protect scans, developer-verification decisions, and any `STATUS_PENDING_USER_ACTION` fallback remain system-controlled. ReleaseShelf always launches the confirmation intent when Android still requires interaction.
- An update APK must be signed by the same certificate as the installed app. Current `androidrun` releases use the stable local Android debug keystore for personal sideloading.
- Dynamic repository sources can resolve arbitrary package IDs, so ReleaseShelf declares `QUERY_ALL_PACKAGES`. This matches its sideloaded app-store role but would require policy review before any Google Play distribution; a Play-targeted variant should prefer a fixed `<queries>` allowlist.
