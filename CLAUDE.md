@AGENTS.md

## Claude Code

- Run `/verify` before calling a change done; it runs the same checks as CI.
- `feature/<name>/CLAUDE.md` files load when you work in that folder. Update the one you touch in the same change.

## iOS specifics

```bash
(cd iosApp && xcodegen generate)                                      # iOS project (committed, regenerate after editing project.yml)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "id=<simulator udid>" build   # iOS simulator
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64              # iOS framework only
```

- **iOS (`iosApp/`):** `iosArm64` + `iosSimulatorArm64`, static framework `ComposeApp`, Swift host only. `Platform.IOS` is mobile, `DiscoveryPlatform.Ios`, `tracksInstalledApps` and `tracksDownloadedFiles`. "Install" is "download": `IosInstaller.install()` returns `COMPLETED`, `IosDownloader` writes straight to `Documents/Komi Store Downloads` (visible in Files), `PackageArchiveReader` reads the package id from `.apk` (`AndroidManifest.xml`) and `.ipa` (`Info.plist`). `DownloadedFileStore` flags records whose file was deleted; the Downloaded page shows them as "Deleted" with "Download again". Known limits: HTTP proxy does not carry HTTPS on Darwin, proxy credentials are ignored, language change applies on next launch, no background update checks, no notifications.
