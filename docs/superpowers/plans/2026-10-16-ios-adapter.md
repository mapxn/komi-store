# iOS Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an iOS target to Komi Store that browses and searches like the other platforms and replaces "install" with "download to Documents", with a "Downloaded" page that mirrors the Android "Installed" page.

**Architecture:** Add `iosArm64` and `iosSimulatorArm64` to every KMP module through the existing `build-logic` convention plugins. Replace the few JVM-only calls in `commonMain` with cross-platform code, then give every `expect` declaration and every platform interface an iOS implementation using the same Koin `corePlatformModule` pattern as Android and Desktop. The download-only behavior is an iOS `Installer` whose `install()` returns `COMPLETED`, so the existing "download, validate, record" flow is reused. Package metadata comes from small pure-Kotlin parsers for `AndroidManifest.xml` and for IPA `Info.plist`, so they can be unit tested on the JVM.

**Tech Stack:** Kotlin 2.3.10, Compose Multiplatform 1.10.3, Ktor 3.4.0 (Darwin engine on iOS), Room 2.8.4 with bundled SQLite, Koin 4.1.1, KSafe 2.0.0, okio (already a transitive dependency of DataStore), XcodeGen for the Xcode project, `xcodebuild` and `xcrun simctl` for the simulator.

**Spec:** `docs/superpowers/specs/2026-10-16-ios-adapter-design.md`

## Global Constraints

- Branch is `ios-adapter`. All work and commits go on it.
- iOS targets are `iosArm64` and `iosSimulatorArm64` only. No `iosX64`.
- JDK 21 for Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The Android SDK is at `export ANDROID_HOME=$HOME/Library/Android/sdk`.
- Follow the existing project style. No KDoc, no inline comments unless they explain a non-obvious invariant, workaround, or concurrency detail. No emojis and no em-dashes in code, comments, strings, or commit messages. Official Kotlin style.
- Use `Clock.System.now().toEpochMilliseconds()` (`import kotlin.time.Clock`) for wall-clock time, as the rest of the project does.
- Android and Desktop behavior must not change. After each phase, the JVM and Android targets must still compile and their existing tests must still pass.
- Downloads are saved to `Documents/Komi Store Downloads`. The Xcode bundle id is `zed.rainxch.githubstore`. The URL scheme is `githubstore`.
- iOS handles two package types: `.ipa` (preferred) and `.apk`. `packageName` for an APK is the Android package name from `AndroidManifest.xml`. For an IPA it is `CFBundleIdentifier` from `Payload/*.app/Info.plist`.
- `Platform.IOS` is appended after `LINUX` in the enum. `Platform.IOS.toDiscoveryPlatform()` is `DiscoveryPlatform.Ios`.
- New user-visible strings are added to `values/strings.xml`, `values-zh-rCN/strings-zh-rCN.xml`, and `values-zh-rTW/strings-zh-rTW.xml` in `core/presentation/src/commonMain/composeResources`. Other locales fall back to the default.
- Commit trailer on every commit: `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- Shell prelude for every command block below:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=$HOME/Library/Android/sdk
cd /Users/mapan/Repo/komi-store
```

- Simulator prelude, needed in every shell session that talks to the simulator (Tasks 9 and 14 to 19). The machine has iOS 17.2 and 17.4 simulator runtimes, so the device is selected by its id:

```bash
SIM=$(xcrun simctl list devices available | awk '/-- iOS 17.4 --/{f=1;next} /-- /{f=0} f && /iPhone 15 Pro \(/{print; exit}' | grep -oE '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}')
echo "simulator: $SIM"
```

## Decisions Made While Planning

These are consequences of reading the code. They refine the spec without changing what was approved.

- `Installer.install()` returns `COMPLETED` and does not copy anything. The desktop downloader already writes straight into `FileLocationsProvider.userDownloadsDir()`, and the iOS downloader does the same.
- The iOS database follows the Desktop `initDatabase`: only `MIGRATION_19_20` plus destructive fallback. Room needs `@ConstructedBy` on `AppDatabase` for non-JVM targets, so that annotation and an `expect object AppDatabaseConstructor` are added in `commonMain`. Room generates the actuals for every target.
- iOS announcements use the `"ANDROID"` platform tag. The backend only documents `ANDROID` and `DESKTOP`, and an unknown tag could be rejected. Revisit when the backend adds an iOS tag.
- Changing the app language on iOS is stored in `AppleLanguages` and applies on the next launch, because an iOS app cannot relaunch itself.
- `Dispatchers.IO` is used as-is. It is available in common code in the coroutines version the project uses.

## Review Focus

These are failure modes the spec implies that no feature task would otherwise test. Each line names where its test lives.

1. A corrupt, truncated, non-zip, or manifest-less `.apk` or `.ipa` must make the package reader return `null`, never throw. Tested in Task 6 with truncation sweeps and hand-built bad zips.
2. A release asset name with path separators, `..`, control characters, spaces, or CJK must never write outside `Documents/Komi Store Downloads`. Tested in Task 7 with `DownloadFileName` and a downloader case for `../../evil.ipa`.
3. An interrupted download, an HTTP error, a redirect loop, or an empty body must leave neither a file at the final path nor a `.part` file, and must surface an error. Tested in Task 7 with Ktor `MockEngine`.
4. A downloaded file that the user deletes from the Files app must show as "Deleted" with a "Download again" action, and the Details page must treat it as not downloaded. Tested in Task 8 (file store) and checked in the simulator in Task 19.
5. Two releases that use the same asset file name (for example `Aidoku.ipa` for every version) must replace the old file atomically instead of appending or corrupting it. Tested in Task 7.

---

## Phase A: Compile for iOS

### Task 1: iOS targets in build-logic and toolchain gate

**Files:**
- Create: `build-logic/convention/src/main/kotlin/zed/rainxch/githubstore/convention/KotlinIosTarget.kt`
- Modify: `build-logic/convention/src/main/kotlin/zed/rainxch/githubstore/convention/KotlinMultiplatform.kt`
- Modify: `build-logic/convention/src/main/kotlin/CmpApplicationConventionPlugin.kt`
- Modify: `build-logic/convention/src/main/kotlin/RoomConventionPlugin.kt`

**Interfaces:**
- Produces: every module that applies `convention.kmp.library`, `convention.cmp.library`, `convention.cmp.feature`, or `convention.cmp.application` has `iosArm64` and `iosSimulatorArm64` targets, so `iosMain` source sets and `compileKotlinIosSimulatorArm64` tasks exist. Room KSP runs for both iOS targets.

- [ ] **Step 1: Prove the baseline builds before changing anything**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=$HOME/Library/Android/sdk
cd /Users/mapan/Repo/komi-store
./gradlew --console=plain :core:domain:compileKotlinJvm 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. If it fails with `SDK location not found`, create `local.properties` (git-ignored) with one line `sdk.dir=/Users/mapan/Library/Android/sdk` and rerun. If it fails because Android platform 36 is missing, let AGP download it (licenses are already accepted) by rerunning once with network. If it still fails for an environment reason, stop and report. Do not continue on a broken baseline.

- [ ] **Step 2: Create the iOS target configuration**

Create `build-logic/convention/src/main/kotlin/zed/rainxch/githubstore/convention/KotlinIosTarget.kt`:

```kotlin
package zed.rainxch.githubstore.convention

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal fun Project.configureIosTargets() {
    extensions.configure<KotlinMultiplatformExtension> {
        iosArm64()
        iosSimulatorArm64()
    }
}
```

- [ ] **Step 3: Call it from the multiplatform configuration**

In `KotlinMultiplatform.kt`, change

```kotlin
    configureAndroidTarget()
    configureJvmTarget()
```

to

```kotlin
    configureAndroidTarget()
    configureJvmTarget()
    configureIosTargets()
```

- [ ] **Step 4: Call it from the application plugin**

In `CmpApplicationConventionPlugin.kt`, add `import zed.rainxch.githubstore.convention.configureIosTargets` next to the other convention imports, and change

```kotlin
            configureAndroidTarget()
            configureJvmTarget()
```

to

```kotlin
            configureAndroidTarget()
            configureJvmTarget()
            configureIosTargets()
```

- [ ] **Step 5: Run Room KSP for both iOS targets**

In `RoomConventionPlugin.kt`, after the `"kspJvm"` line add:

```kotlin
                "kspIosArm64"(libs.findLibrary("androidx-room-compiler").get())
                "kspIosSimulatorArm64"(libs.findLibrary("androidx-room-compiler").get())
```

- [ ] **Step 6: Toolchain gate**

```bash
./gradlew --console=plain :core:domain:compileKotlinIosSimulatorArm64 2>&1 | tail -40
```

Expected: the first run downloads the Kotlin/Native compiler (large, several minutes). The build then FAILS inside `core:domain` with source errors, for example `Expected function 'getPlatform' has no actual declaration`. That failure is the pass condition for this gate, because it proves the Kotlin/Native toolchain and Xcode 27.0 work and Gradle now sees the iOS target.

If instead the failure mentions Xcode, `xcrun`, a missing SDK, or an unsupported Xcode version, STOP and report to the user. Do not try to work around a toolchain failure.

Also confirm nothing else broke:

```bash
./gradlew --console=plain :core:domain:compileKotlinJvm 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add build-logic
git commit -m "build: add iOS targets to convention plugins" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: `Platform.IOS` and exhaustive `when` fixes

**Files:**
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/system/Platform.kt`
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/Platform.kt`
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/utils/ReleasePlatforms.kt`
- Modify (exhaustive `when`): `core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/BrowseFilterStoreImpl.kt`, `AnnouncementsRepositoryImpl.kt`, `StarredRepositoryImpl.kt`; `feature/feed/presentation/src/commonMain/kotlin/zed/rainxch/feed/presentation/FeedViewModel.kt`; `feature/home/data/src/commonMain/kotlin/zed/rainxch/home/data/repository/HomeRepositoryImpl.kt`; `feature/dev-profile/data/src/commonMain/kotlin/zed/rainxch/devprofile/data/repository/DeveloperProfileRepositoryImpl.kt`; `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/FeedbackViewModel.kt`; plus any desktop file the compiler reports
- Test: `core/domain/src/commonTest/kotlin/zed/rainxch/core/domain/model/system/PlatformTest.kt`

**Interfaces:**
- Produces: `Platform.IOS`, `Platform.tracksInstalledApps: Boolean` (true for ANDROID and IOS), `Platform.tracksDownloadedFiles: Boolean` (true only for IOS), `fun isIos(): Boolean` in `zed.rainxch.core.domain`, and `Platform.toDiscoveryPlatform()` mapping `IOS` to `DiscoveryPlatform.Ios`.

- [ ] **Step 1: Write the failing test**

Create `core/domain/src/commonTest/kotlin/zed/rainxch/core/domain/model/system/PlatformTest.kt`:

```kotlin
package zed.rainxch.core.domain.model.system

import zed.rainxch.core.domain.model.repository.DiscoveryPlatform
import zed.rainxch.core.domain.utils.toDiscoveryPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun iosIsMobileAndNotDesktop() {
        assertTrue(Platform.IOS.isMobile)
        assertFalse(Platform.IOS.isDesktop)
    }

    @Test
    fun androidIsMobileAndDesktopsAreNot() {
        assertTrue(Platform.ANDROID.isMobile)
        listOf(Platform.WINDOWS, Platform.MACOS, Platform.LINUX).forEach {
            assertFalse(it.isMobile)
            assertTrue(it.isDesktop)
        }
    }

    @Test
    fun onlyAndroidAndIosTrackInstalledApps() {
        assertTrue(Platform.ANDROID.tracksInstalledApps)
        assertTrue(Platform.IOS.tracksInstalledApps)
        listOf(Platform.WINDOWS, Platform.MACOS, Platform.LINUX).forEach {
            assertFalse(it.tracksInstalledApps)
        }
    }

    @Test
    fun onlyIosTracksDownloadedFiles() {
        assertTrue(Platform.IOS.tracksDownloadedFiles)
        Platform.entries.filter { it != Platform.IOS }.forEach {
            assertFalse(it.tracksDownloadedFiles)
        }
    }

    @Test
    fun iosMapsToIosDiscoveryPlatform() {
        assertEquals(DiscoveryPlatform.Ios, Platform.IOS.toDiscoveryPlatform())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew --console=plain :core:domain:jvmTest --tests "*PlatformTest" 2>&1 | tail -20
```

Expected: compilation FAILS with `Unresolved reference 'IOS'`.

- [ ] **Step 3: Add the enum value and capabilities**

Replace the body of `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/model/system/Platform.kt` with:

```kotlin
package zed.rainxch.core.domain.model.system

enum class Platform {
    ANDROID,
    WINDOWS,
    MACOS,
    LINUX,
    IOS,
    ;

    val isMobile: Boolean get() = this == ANDROID || this == IOS
    val isDesktop: Boolean get() = !isMobile
    val tracksInstalledApps: Boolean get() = this == ANDROID || this == IOS
    val tracksDownloadedFiles: Boolean get() = this == IOS
}
```

In `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/Platform.kt` add below `isAndroid()`:

```kotlin
fun isIos(): Boolean = getPlatform() == Platform.IOS
```

In `ReleasePlatforms.kt`, inside `Platform.toDiscoveryPlatform()`, add the branch after `LINUX`:

```kotlin
        Platform.IOS -> DiscoveryPlatform.Ios
```

- [ ] **Step 4: Confirm nothing persists the enum by ordinal**

```bash
grep -rnE "Platform\.(entries|valueOf)|Platform\.values\(\)|platform\.ordinal" --include='*.kt' core feature composeApp | grep -v /build/
```

Expected: no output that treats `Platform` ordinals as stored data. If something does, stop and tell the user.

- [ ] **Step 5: Run the test**

```bash
./gradlew --console=plain :core:domain:jvmTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`, `PlatformTest` passes, and the existing `ReleasePlatformsTest` and the rest of the domain tests still pass.

- [ ] **Step 6: Fix every non-exhaustive `when` over `Platform`**

Compile the JVM and Android targets of every module and fix what the compiler reports:

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -60
```

Use these exact patterns. Known sites (add the `IOS` branch right after the `LINUX` branch unless stated):

`core/data/.../repository/BrowseFilterStoreImpl.kt` and `feature/feed/presentation/.../FeedViewModel.kt` (`detectedPlatform`):

```kotlin
        Platform.IOS -> DiscoveryPlatform.Ios
```

`core/data/.../repository/AnnouncementsRepositoryImpl.kt`, change `Platform.ANDROID -> "ANDROID"` to:

```kotlin
        Platform.ANDROID, Platform.IOS -> "ANDROID"
```

`core/data/.../repository/StarredRepositoryImpl.kt` (`matchesPlatform`) and both `when (platform)` blocks in `feature/dev-profile/data/.../DeveloperProfileRepositoryImpl.kt`:

```kotlin
            Platform.IOS -> name.endsWith(".ipa") || name.endsWith(".apk")
```

(indent to match the surrounding branches; in `DeveloperProfileRepositoryImpl` the existing branches are one level deeper).

`feature/home/data/.../HomeRepositoryImpl.kt`, scoring `when (devicePlatform)`, add before the `Platform.WINDOWS, Platform.MACOS, Platform.LINUX` branch:

```kotlin
            Platform.IOS -> {
                if (topics.contains("ios")) score += 10
                if (topics.contains("mobile")) score += 5
                if (language == "swift" || language == "objective-c") score += 5
                if (desc.contains("ios") || desc.contains("ipa")) score += 3
            }
```

and in `checkRepoHasInstallers`, add after the `Platform.LINUX` branch:

```kotlin
                        Platform.IOS -> {
                            name.endsWith(".ipa") || name.endsWith(".apk")
                        }
```

`feature/tweaks/presentation/.../feedback/FeedbackViewModel.kt` (`displayName`):

```kotlin
            Platform.IOS -> "iOS"
```

`core/data/src/jvmMain/.../services/DesktopInstaller.kt`: the two `when (platform)` expressions in `isAssetInstallable` and `choosePrimaryAsset` list `Platform.ANDROID`. Change `Platform.ANDROID ->` to `Platform.ANDROID, Platform.IOS ->` in both. The other desktop `when` blocks, including `DesktopAppLauncher`, already have an `else` branch and need no change. These branches are unreachable at runtime.

Repeat the compile command until it prints no `e:` lines.

- [ ] **Step 7: Run the JVM tests of the touched modules**

```bash
./gradlew --console=plain :core:domain:jvmTest :core:presentation:jvmTest 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core feature composeApp
git commit -m "feat(domain): add Platform.IOS with installed-app and downloaded-file capabilities" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---
### Task 3: Cross-platform utilities in `core/domain`

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/utils/NumberFormatting.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/utils/UrlEncoding.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/utils/Crypto.kt`
- Create: `core/domain/src/androidMain/kotlin/zed/rainxch/core/domain/utils/Crypto.android.kt`
- Create: `core/domain/src/jvmMain/kotlin/zed/rainxch/core/domain/utils/Crypto.jvm.kt`
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/system/LocalFileSystem.kt`
- Test: `core/domain/src/commonTest/kotlin/zed/rainxch/core/domain/utils/NumberFormattingTest.kt`, `UrlEncodingTest.kt`, `CryptoTest.kt`

**Interfaces:**
- Produces: `fun Double.formatFixed(decimals: Int): String` (half-up rounding, always `.` as separator), `fun Int.padStartZero(width: Int): String`, `fun encodeUrlComponent(value: String): String` (RFC 3986 unreserved characters are kept, everything else is percent-encoded as UTF-8), `expect fun sha256(input: ByteArray): ByteArray`, `expect fun secureRandomBytes(size: Int): ByteArray`, `fun ByteArray.toHex(): String` (lowercase), and `interface LocalFileSystem { fun exists(path: String): Boolean; fun size(path: String): Long; fun delete(path: String): Boolean }` where `size` returns `-1` when the path is missing or not a regular file and `delete` returns `false` when nothing was deleted.
- The iOS `actual`s of `Crypto.kt` are added in Task 9.

- [ ] **Step 1: Write the failing tests**

`NumberFormattingTest.kt`:

```kotlin
package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormattingTest {
    @Test
    fun formatsFixedDecimals() {
        assertEquals("0.00", 0.0.formatFixed(2))
        assertEquals("1.0", 1.0.formatFixed(1))
        assertEquals("1.5", (1536.0 / 1024.0).formatFixed(1))
        assertEquals("123.46", 123.456.formatFixed(2))
    }

    @Test
    fun roundsHalfUp() {
        assertEquals("13", 12.5.formatFixed(0))
        assertEquals("2.3", 2.25.formatFixed(1))
        assertEquals("1000.0", 999.95.formatFixed(1))
    }

    @Test
    fun handlesNegativeValues() {
        assertEquals("-1.5", (-1.5).formatFixed(1))
        assertEquals("0.00", (-0.001).formatFixed(2))
    }

    @Test
    fun padsIntegersWithZeros() {
        assertEquals("05", 5.padStartZero(2))
        assertEquals("12", 12.padStartZero(2))
        assertEquals("007", 7.padStartZero(3))
    }
}
```

`UrlEncodingTest.kt`:

```kotlin
package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlEncodingTest {
    @Test
    fun keepsUnreservedCharacters() {
        assertEquals("ABCD-1234", encodeUrlComponent("ABCD-1234"))
        assertEquals("a_b.c~d", encodeUrlComponent("a_b.c~d"))
    }

    @Test
    fun encodesReservedCharacters() {
        assertEquals("a%20b", encodeUrlComponent("a b"))
        assertEquals("a%26b%3Dc", encodeUrlComponent("a&b=c"))
    }

    @Test
    fun encodesNonAsciiAsUtf8() {
        assertEquals("%C3%A9", encodeUrlComponent("é"))
        assertEquals("%E4%B8%AD", encodeUrlComponent("中"))
    }
}
```

`CryptoTest.kt`:

```kotlin
package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CryptoTest {
    @Test
    fun sha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256OfEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun secureRandomBytesHasRequestedSizeAndVaries() {
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)
        assertEquals(32, first.size)
        assertEquals(0, secureRandomBytes(0).size)
        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun toHexIsLowercaseAndZeroPadded() {
        assertEquals("000fff", byteArrayOf(0, 15, -1).toHex())
    }
}
```

- [ ] **Step 2: Run and watch them fail**

```bash
./gradlew --console=plain :core:domain:jvmTest 2>&1 | tail -15
```

Expected: compilation FAILS (`Unresolved reference: formatFixed`, `encodeUrlComponent`, `sha256`).

- [ ] **Step 3: Implement**

`NumberFormatting.kt`:

```kotlin
package zed.rainxch.core.domain.utils

import kotlin.math.abs
import kotlin.math.roundToLong

fun Double.formatFixed(decimals: Int): String {
    require(decimals in 0..9)
    if (isNaN() || isInfinite()) return toString()

    var factor = 1L
    repeat(decimals) { factor *= 10 }

    val scaled = (abs(this) * factor).roundToLong()
    val integerPart = scaled / factor
    val fraction = scaled % factor
    val sign = if (this < 0 && scaled != 0L) "-" else ""

    return if (decimals == 0) {
        "$sign$integerPart"
    } else {
        "$sign$integerPart.${fraction.toString().padStart(decimals, '0')}"
    }
}

fun Int.padStartZero(width: Int): String = toString().padStart(width, '0')
```

`UrlEncoding.kt`:

```kotlin
package zed.rainxch.core.domain.utils

private const val HEX_DIGITS = "0123456789ABCDEF"

fun encodeUrlComponent(value: String): String =
    buildString {
        for (byte in value.encodeToByteArray()) {
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            val unreserved =
                char in 'A'..'Z' ||
                    char in 'a'..'z' ||
                    char in '0'..'9' ||
                    char == '-' ||
                    char == '_' ||
                    char == '.' ||
                    char == '~'
            if (unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[unsigned shr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }
```

`Crypto.kt`:

```kotlin
package zed.rainxch.core.domain.utils

expect fun sha256(input: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
```

`Crypto.android.kt` and `Crypto.jvm.kt` (identical bodies):

```kotlin
package zed.rainxch.core.domain.utils

import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
```

`LocalFileSystem.kt`:

```kotlin
package zed.rainxch.core.domain.system

interface LocalFileSystem {
    fun exists(path: String): Boolean

    fun size(path: String): Long

    fun delete(path: String): Boolean
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew --console=plain :core:domain:jvmTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, all new tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/domain
git commit -m "feat(domain): add cross-platform formatting, url encoding, crypto and file-system helpers" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Make `core/data` `commonMain` free of JVM-only code

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/data/build.gradle.kts`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/utils/FileDigest.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/utils/OkioLocalFileSystem.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/OkioDigestVerifier.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/ProxyTesterImpl.kt`, `HttpClientFactory.kt`, `ForgejoApiClient.kt`, `ProxyManager.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadOrchestrator.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`
- Modify (time): `core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/HiddenReposRepositoryImpl.kt`, `InstalledAppsRepositoryImpl.kt`, `SeenReposRepositoryImpl.kt`; `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/use_cases/SyncInstalledAppsUseCase.kt`
- Test: `core/data/src/jvmTest/kotlin/zed/rainxch/core/data/utils/FileDigestTest.kt`, `OkioLocalFileSystemTest.kt`; `.../network/OkioDigestVerifierTest.kt`, `ProxyFailureClassificationTest.kt`

**Interfaces:**
- Consumes: `LocalFileSystem` (Task 3).
- Produces: `object FileDigest { fun sha256Hex(path: String, fileSystem: FileSystem = FileSystem.SYSTEM): String }` (streams the file, lowercase hex), `class OkioLocalFileSystem(fileSystem: FileSystem = FileSystem.SYSTEM) : LocalFileSystem`, `class OkioDigestVerifier(fileSystem: FileSystem = FileSystem.SYSTEM) : DigestVerifier`, `internal fun classifyProxyFailure(error: Exception): ProxyTestOutcome.Failure`, a Koin `single<LocalFileSystem>` in `coreModule`, and the catalog entries `ktor-client-darwin` and `ktor-client-mock`.

- [ ] **Step 1: Add catalog entries and test dependencies**

In `gradle/libs.versions.toml`, after the `ktor-client-cio` line add:

```toml
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

In `core/data/build.gradle.kts`, inside `kotlin { sourceSets { ... } }` after `jvmMain { ... }` add:

```kotlin
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }
```

- [ ] **Step 2: Write the failing tests**

`core/data/src/jvmTest/kotlin/zed/rainxch/core/data/utils/FileDigestTest.kt`:

```kotlin
package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileDigestTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-digest-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    @Test
    fun hashesFileContent() {
        val file = dir / "abc.txt"
        fileSystem.write(file) { writeUtf8("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileDigest.sha256Hex(file.toString(), fileSystem),
        )
    }

    @Test
    fun hashesEmptyFile() {
        val file = dir / "empty.bin"
        fileSystem.write(file) { }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FileDigest.sha256Hex(file.toString(), fileSystem),
        )
    }
}
```

`core/data/src/jvmTest/kotlin/zed/rainxch/core/data/utils/OkioLocalFileSystemTest.kt`:

```kotlin
package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkioLocalFileSystemTest {
    private val fileSystem = FileSystem.SYSTEM
    private val local = OkioLocalFileSystem(fileSystem)
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-local-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    @Test
    fun reportsExistenceAndSize() {
        val file = dir / "a.bin"
        fileSystem.write(file) { writeUtf8("12345") }
        assertTrue(local.exists(file.toString()))
        assertEquals(5L, local.size(file.toString()))
    }

    @Test
    fun missingPathHasNegativeSize() {
        val missing = (dir / "missing").toString()
        assertFalse(local.exists(missing))
        assertEquals(-1L, local.size(missing))
    }

    @Test
    fun directoryIsNotARegularFile() {
        assertEquals(-1L, local.size(dir.toString()))
    }

    @Test
    fun deleteRemovesFileAndReportsMissing() {
        val file = dir / "gone.bin"
        fileSystem.write(file) { writeUtf8("x") }
        assertTrue(local.delete(file.toString()))
        assertFalse(local.exists(file.toString()))
        assertFalse(local.delete(file.toString()))
    }
}
```

`core/data/src/jvmTest/kotlin/zed/rainxch/core/data/network/OkioDigestVerifierTest.kt`:

```kotlin
package zed.rainxch.core.data.network

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkioDigestVerifierTest {
    private val fileSystem = FileSystem.SYSTEM
    private val verifier = OkioDigestVerifier(fileSystem)
    private lateinit var dir: Path

    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-verify-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun fileWithAbc(): String {
        val file = dir / "abc.txt"
        fileSystem.write(file) { writeUtf8("abc") }
        return file.toString()
    }

    @Test
    fun matchingDigestPasses() =
        runBlocking {
            assertNull(verifier.verify(fileWithAbc(), "sha256:$abcDigest"))
            assertNull(verifier.verify(fileWithAbc(), abcDigest.uppercase()))
        }

    @Test
    fun mismatchingDigestReportsBothValues() =
        runBlocking {
            val result = verifier.verify(fileWithAbc(), "sha256:" + "0".repeat(64))
            assertNotNull(result)
            assertTrue(result.contains(abcDigest))
        }

    @Test
    fun missingFileIsReported() =
        runBlocking {
            val result = verifier.verify((dir / "nope").toString(), abcDigest)
            assertNotNull(result)
            assertTrue(result.startsWith("File not found"))
        }
}
```

`core/data/src/jvmTest/kotlin/zed/rainxch/core/data/network/ProxyFailureClassificationTest.kt`:

```kotlin
package zed.rainxch.core.data.network

import zed.rainxch.core.domain.network.ProxyTestOutcome
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyFailureClassificationTest {
    @Test
    fun socketTimeoutIsTimeout() {
        assertEquals(ProxyTestOutcome.Failure.Timeout, classifyProxyFailure(SocketTimeoutException("t")))
    }

    @Test
    fun unknownHostIsDnsFailure() {
        assertEquals(ProxyTestOutcome.Failure.DnsFailure, classifyProxyFailure(UnknownHostException("h")))
    }

    @Test
    fun connectExceptionIsProxyUnreachable() {
        assertEquals(ProxyTestOutcome.Failure.ProxyUnreachable, classifyProxyFailure(ConnectException("c")))
    }

    @Test
    fun causeChainIsInspected() {
        val wrapped = RuntimeException("outer", ConnectException("inner"))
        assertEquals(ProxyTestOutcome.Failure.ProxyUnreachable, classifyProxyFailure(wrapped))
    }

    @Test
    fun otherErrorsAreUnknownWithMessage() {
        assertEquals(
            ProxyTestOutcome.Failure.Unknown("boom"),
            classifyProxyFailure(IllegalStateException("boom")),
        )
    }
}
```

- [ ] **Step 3: Run and watch them fail**

```bash
./gradlew --console=plain :core:data:jvmTest 2>&1 | tail -15
```

Expected: compilation FAILS (`Unresolved reference: FileDigest`, `OkioLocalFileSystem`, `OkioDigestVerifier`, `classifyProxyFailure`).

- [ ] **Step 4: Implement the new classes**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/utils/FileDigest.kt`:

```kotlin
package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

object FileDigest {
    fun sha256Hex(
        path: String,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): String {
        val hashing = HashingSink.sha256(blackholeSink())
        fileSystem.source(path.toPath()).buffer().use { it.readAll(hashing) }
        return hashing.hash.hex()
    }
}
```

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/utils/OkioLocalFileSystem.kt`:

```kotlin
package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import zed.rainxch.core.domain.system.LocalFileSystem

class OkioLocalFileSystem(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : LocalFileSystem {
    override fun exists(path: String): Boolean = fileSystem.exists(path.toPath())

    override fun size(path: String): Long =
        fileSystem
            .metadataOrNull(path.toPath())
            ?.takeIf { it.isRegularFile }
            ?.size
            ?: -1L

    override fun delete(path: String): Boolean {
        val target = path.toPath()
        if (!fileSystem.exists(target)) return false
        return try {
            fileSystem.delete(target)
            true
        } catch (_: IOException) {
            false
        }
    }
}
```

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/OkioDigestVerifier.kt`:

```kotlin
package zed.rainxch.core.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import zed.rainxch.core.data.utils.FileDigest
import zed.rainxch.core.domain.network.DigestVerifier

class OkioDigestVerifier(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DigestVerifier {
    override suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String? =
        withContext(Dispatchers.IO) {
            if (!fileSystem.exists(filePath.toPath())) return@withContext "File not found: $filePath"
            val expected = expectedDigest.removePrefix("sha256:").lowercase()
            val actual =
                runCatching { FileDigest.sha256Hex(filePath, fileSystem) }
                    .getOrElse { return@withContext "Digest computation failed: ${it.message}" }
            if (actual == expected) null else "Digest mismatch (expected $expected, got $actual)"
        }
}
```

- [ ] **Step 5: Rewrite the exception handling in `ProxyTesterImpl.kt`**

Replace the four `java.*` imports (`java.io.IOException`, `java.net.ConnectException`, `java.net.SocketTimeoutException`, `java.net.UnknownHostException`) by deleting them. Then replace the whole `catch` chain after `} catch (e: CancellationException) { throw e }` (the first one) so the `try` ends like this:

```kotlin
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyProxyFailure(e)
        } finally {
            client.close()
        }
```

and add at the end of the file, after the class:

```kotlin
internal fun classifyProxyFailure(error: Exception): ProxyTestOutcome.Failure =
    when {
        error is HttpRequestTimeoutException || error.hasClassNamed("SocketTimeoutException") ->
            ProxyTestOutcome.Failure.Timeout

        error is UnresolvedAddressException || error.hasClassNamed("UnknownHostException") ->
            ProxyTestOutcome.Failure.DnsFailure

        error.hasClassNamed("ConnectException") -> ProxyTestOutcome.Failure.ProxyUnreachable

        else -> ProxyTestOutcome.Failure.Unknown(error.message)
    }

private fun Throwable.hasClassNamed(name: String): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it::class.simpleName == name }
```

Matching by simple class name keeps the exact JVM behavior (the JVM exception classes have those simple names) without importing `java.net`.

- [ ] **Step 6: Replace the remaining JVM types in `core/data` `commonMain`**

Run this script. Every replacement asserts that it found its target, so a mismatch fails loudly instead of silently skipping.

```bash
python3 -I - <<'PY'
import pathlib

base = pathlib.Path("core/data/src/commonMain/kotlin/zed/rainxch/core/data")

def edit(path, pairs):
    p = base / path
    s = p.read_text()
    for old, new in pairs:
        assert old in s, f"{path}: missing {old!r}"
        s = s.replace(old, new)
    p.write_text(s)

edit("network/HttpClientFactory.kt", [("import java.io.IOException", "import kotlinx.io.IOException")])
edit("network/ForgejoApiClient.kt", [("import java.io.IOException", "import kotlinx.io.IOException")])
edit("network/ProxyManager.kt", [
    ("import java.util.concurrent.atomic.AtomicReference\n", ""),
    ("    private val mirror = AtomicReference<MirrorActive?>(null)\n", "    @Volatile private var mirror: MirrorActive? = null\n"),
    ("fun currentMirror(): MirrorActive? = mirror.get()", "fun currentMirror(): MirrorActive? = mirror"),
    ("fun currentMirrorTemplate(): String? = mirror.get()?.template", "fun currentMirrorTemplate(): String? = mirror?.template"),
    ("mirror.set(active)", "mirror = active"),
])
edit("services/DefaultDownloadOrchestrator.kt", [
    ("runCatching { java.io.File(filePath).delete() }",
     "runCatching { FileSystem.SYSTEM.delete(filePath.toPath(), mustExist = false) }"),
    ("import zed.rainxch.core.data.data_source.TokenStore\n",
     "import okio.FileSystem\nimport okio.Path.Companion.toPath\nimport zed.rainxch.core.data.data_source.TokenStore\n"),
])
edit("di/SharedModule.kt", [
    ("import zed.rainxch.core.data.network.createPlatformHttpClient\n",
     "import zed.rainxch.core.data.network.createPlatformHttpClient\n"
     "import zed.rainxch.core.data.utils.OkioLocalFileSystem\n"
     "import zed.rainxch.core.domain.system.LocalFileSystem\n"),
    ("        single<Platform> {\n            getPlatform()\n        }\n",
     "        single<Platform> {\n            getPlatform()\n        }\n\n"
     "        single<LocalFileSystem> {\n            OkioLocalFileSystem()\n        }\n"),
])
print("ok")
PY
```

- [ ] **Step 7: Replace `System.currentTimeMillis()` in the four files that live in core**

```bash
python3 -I - <<'PY'
import pathlib

files = [
    "core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/HiddenReposRepositoryImpl.kt",
    "core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/InstalledAppsRepositoryImpl.kt",
    "core/data/src/commonMain/kotlin/zed/rainxch/core/data/repository/SeenReposRepositoryImpl.kt",
    "core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/use_cases/SyncInstalledAppsUseCase.kt",
]
for f in files:
    p = pathlib.Path(f)
    s = p.read_text()
    assert "System.currentTimeMillis()" in s, f
    s = s.replace("System.currentTimeMillis()", "Clock.System.now().toEpochMilliseconds()")
    if "import kotlin.time.Clock\n" not in s:
        lines = s.split("\n")
        last = max(i for i, l in enumerate(lines) if l.startswith("import "))
        lines.insert(last + 1, "import kotlin.time.Clock")
        s = "\n".join(lines)
    p.write_text(s)
print("ok")
PY
```

- [ ] **Step 8: Run the tests and the regression compiles**

```bash
./gradlew --console=plain :core:data:jvmTest :core:domain:jvmTest 2>&1 | tail -15
./gradlew --console=plain :core:data:compileDebugKotlinAndroid 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` for both. The new tests pass.

- [ ] **Step 9: Commit**

```bash
git add gradle core
git commit -m "refactor(data): remove JVM-only APIs from core data commonMain" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: Make feature and presentation `commonMain` free of JVM-only code

**Files:**
- Modify: `core/presentation/src/commonMain/kotlin/zed/rainxch/core/presentation/utils/FileSizeFormatter.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksViewModel.kt`
- Modify: `feature/auth/data/build.gradle.kts`, `feature/auth/data/src/commonMain/kotlin/zed/rainxch/auth/data/crypto/PkceGenerator.kt`, `feature/auth/data/src/commonMain/kotlin/zed/rainxch/auth/data/repository/AuthenticationRepositoryImpl.kt`
- Modify: `feature/auth/presentation/src/commonMain/kotlin/zed/rainxch/auth/presentation/AuthenticationViewModel.kt`
- Modify: `feature/details/data/src/commonMain/kotlin/zed/rainxch/details/data/system/AttestationVerifierImpl.kt`, `.../translation/YoudaoTranslator.kt`
- Modify: `feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/AppsViewModel.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/import/ExternalImportViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`
- Test: `core/presentation/src/jvmTest/kotlin/zed/rainxch/core/presentation/utils/FileSizeFormatterTest.kt`, `feature/auth/data/src/jvmTest/kotlin/zed/rainxch/auth/data/crypto/PkceGeneratorTest.kt`

**Interfaces:**
- Consumes: `formatFixed`, `padStartZero`, `encodeUrlComponent`, `sha256`, `secureRandomBytes`, `toHex` (Task 3), `FileDigest` and `LocalFileSystem` (Task 4).
- Produces: `AppsViewModel` and `DetailsViewModel` take a new constructor parameter `localFileSystem: LocalFileSystem`. `DetailsViewModel` is built explicitly in `ViewModelsModule`, so that call gains `localFileSystem = get()`.

Behavior note for the report: `String.format` follows the device locale (for example a comma decimal separator, or Arabic-Indic digits in a timer). The cross-platform formatting always uses ASCII digits and a dot. This is a small visible change on Android and Desktop in those locales, and it is intentional.

- [ ] **Step 1: Write the failing tests**

`core/presentation/src/jvmTest/kotlin/zed/rainxch/core/presentation/utils/FileSizeFormatterTest.kt`:

```kotlin
package zed.rainxch.core.presentation.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSizeFormatterTest {
    @Test
    fun formatsBytesBelowOneKilobyte() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun formatsLargerUnits() {
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("1.0 MB", formatFileSize(1_048_576))
        assertEquals("5.0 GB", formatFileSize(5L * 1_073_741_824L))
    }

    @Test
    fun respectsDecimals() {
        assertEquals("1.50 KB", formatFileSize(1536, decimals = 2))
    }
}
```

`feature/auth/data/src/jvmTest/kotlin/zed/rainxch/auth/data/crypto/PkceGeneratorTest.kt`:

```kotlin
package zed.rainxch.auth.data.crypto

import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalEncodingApi::class)
class PkceGeneratorTest {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun challengeIsBase64UrlSha256OfVerifier() {
        val triplet = PkceGenerator.generate()
        val expected =
            b64.encode(MessageDigest.getInstance("SHA-256").digest(triplet.codeVerifier.encodeToByteArray()))
        assertEquals(expected, triplet.codeChallenge)
    }

    @Test
    fun stateAndVerifierHaveExpectedLengths() {
        val triplet = PkceGenerator.generate()
        assertEquals(43, triplet.state.length)
        assertEquals(64, triplet.codeVerifier.length)
    }

    @Test
    fun consecutiveValuesDiffer() {
        val first = PkceGenerator.generate()
        val second = PkceGenerator.generate()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
    }
}
```

In `feature/auth/data/build.gradle.kts`, add inside `sourceSets { ... }` after `commonMain { ... }`:

```kotlin
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
```

- [ ] **Step 2: Run and watch the formatter test fail**

```bash
./gradlew --console=plain :core:presentation:jvmTest --tests "*FileSizeFormatterTest" 2>&1 | tail -15
```

Expected: this one may already PASS with the old implementation, since `"%.1f".format` works on the JVM. That is fine: it is the regression guard for the rewrite. `PkceGeneratorTest` also passes before and after for the same reason. Run both to record the baseline:

```bash
./gradlew --console=plain :feature:auth:data:jvmTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Rewrite the JVM-only code**

`FileSizeFormatter.kt`: add `import zed.rainxch.core.domain.utils.formatFixed` and replace the last line with:

```kotlin
    return "${size.formatFixed(decimals)} ${units[index]}"
```

`TweaksViewModel.kt` (inside the size formatter, around line 165): add `import zed.rainxch.core.domain.utils.formatFixed` and replace `"${"%.1f".format(size)} ${units[unitIndex]}"` with `"${size.formatFixed(1)} ${units[unitIndex]}"`.

`PkceGenerator.kt` (full replacement):

```kotlin
package zed.rainxch.auth.data.crypto

import zed.rainxch.core.domain.utils.secureRandomBytes
import zed.rainxch.core.domain.utils.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object PkceGenerator {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun generate(): PkceTriplet {
        val state = b64.encode(secureRandomBytes(STATE_BYTES))
        val codeVerifier = b64.encode(secureRandomBytes(VERIFIER_BYTES))
        val codeChallenge = b64.encode(sha256(codeVerifier.encodeToByteArray()))

        return PkceTriplet(
            state = state,
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
        )
    }

    private const val STATE_BYTES = 32
    private const val VERIFIER_BYTES = 48
}
```

Run the rest of the replacements:

```bash
python3 -I - <<'PY'
import pathlib

def edit(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    for old, new in pairs:
        assert old in s, f"{path}: missing {old!r}"
        s = s.replace(old, new)
    p.write_text(s)

def add_import_after(path, anchor, new_import):
    p = pathlib.Path(path)
    s = p.read_text()
    assert anchor in s, f"{path}: missing anchor {anchor!r}"
    if new_import in s:
        return
    s = s.replace(anchor, anchor + new_import, 1)
    p.write_text(s)

auth_repo = "feature/auth/data/src/commonMain/kotlin/zed/rainxch/auth/data/repository/AuthenticationRepositoryImpl.kt"
edit(auth_repo, [
    ("import java.util.concurrent.TimeoutException\n", ""),
    ("throw TimeoutException(", "throw DevicePollTimeoutException("),
    ("} catch (e: TimeoutException) {", "} catch (e: DevicePollTimeoutException) {"),
])
p = pathlib.Path(auth_repo)
s = p.read_text()
s = s.rstrip("\n") + "\n\nprivate class DevicePollTimeoutException(message: String) : Exception(message)\n"
p.write_text(s)

auth_vm = "feature/auth/presentation/src/commonMain/kotlin/zed/rainxch/auth/presentation/AuthenticationViewModel.kt"
edit(auth_vm, [
    ("import java.net.URLEncoder\n", ""),
    ('URLEncoder.encode(userCode, "UTF-8")', "encodeUrlComponent(userCode)"),
    ('"%02d:%02d".format(minutes, seconds)', '"${minutes.padStartZero(2)}:${seconds.padStartZero(2)}"'),
    ("import kotlinx.coroutines.CancellationException\n",
     "import kotlinx.coroutines.CancellationException\n"
     "import zed.rainxch.core.domain.utils.encodeUrlComponent\n"
     "import zed.rainxch.core.domain.utils.padStartZero\n"),
])

attestation = "feature/details/data/src/commonMain/kotlin/zed/rainxch/details/data/system/AttestationVerifierImpl.kt"
edit(attestation, [
    ("import java.io.File\nimport java.io.FileInputStream\nimport java.security.MessageDigest\n", ""),
    ("import kotlinx.coroutines.CancellationException\n",
     "import kotlinx.coroutines.CancellationException\nimport zed.rainxch.core.data.utils.FileDigest\n"),
    ("val digest = computeSha256(filePath)", "val digest = FileDigest.sha256Hex(filePath)"),
])
p = pathlib.Path(attestation)
s = p.read_text()
start = s.index("    private fun computeSha256(filePath: String): String {")
end = s.rindex("}")
s = s[:start].rstrip("\n") + "\n" + s[end:]
p.write_text(s)

youdao = "feature/details/data/src/commonMain/kotlin/zed/rainxch/details/data/translation/YoudaoTranslator.kt"
p = pathlib.Path(youdao)
s = p.read_text()
start = s.index("    private fun sha256Hex(input: String): String {")
end = s.index("\n    }\n", start) + len("\n    }\n")
s = s[:start] + "    private fun sha256Hex(input: String): String = sha256(input.encodeToByteArray()).toHex()\n" + s[end:]
s = s.replace("import java.security.MessageDigest\n", "import zed.rainxch.core.domain.utils.sha256\nimport zed.rainxch.core.domain.utils.toHex\n")
p.write_text(s)

details_vm = "feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt"
edit(details_vm, [
    ("import java.io.File\n", ""),
    ("import java.util.concurrent.atomic.AtomicBoolean\n", ""),
    ("private val rateLimited = AtomicBoolean(false)", "private val rateLimited = MutableStateFlow(false)"),
    ("rateLimited.set(false)", "rateLimited.value = false"),
    ("rateLimited.set(true)", "rateLimited.value = true"),
    ("rateLimited.get()", "rateLimited.value"),
    ("            val file = File(parkedPath)\n            if (file.exists() && file.length() > 0) parkedPath else null\n",
     "            if (localFileSystem.size(parkedPath) > 0) parkedPath else null\n"),
    ("    private val userSessionRepository: UserSessionRepository,\n    private val packageNameParam: String? = null,\n",
     "    private val userSessionRepository: UserSessionRepository,\n    private val localFileSystem: LocalFileSystem,\n    private val packageNameParam: String? = null,\n"),
    ("import zed.rainxch.core.domain.system.DownloadSpec\n",
     "import zed.rainxch.core.domain.system.DownloadSpec\nimport zed.rainxch.core.domain.system.LocalFileSystem\n"),
])

apps_vm = "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/AppsViewModel.kt"
edit(apps_vm, [
    ("import java.io.File\n", ""),
    ("                        val file = File(existingPath)\n", ""),
    ("val deleted = file.delete()", "val deleted = localFileSystem.delete(existingPath)"),
    ("runCatching { File(path).takeIf { it.exists() }?.delete() }", "runCatching { localFileSystem.delete(path) }"),
    ("    private val browserHelper: BrowserHelper,\n) : ViewModel() {",
     "    private val browserHelper: BrowserHelper,\n    private val localFileSystem: LocalFileSystem,\n) : ViewModel() {"),
    ("import zed.rainxch.core.domain.system.InstallPolicy\n",
     "import zed.rainxch.core.domain.system.InstallPolicy\nimport zed.rainxch.core.domain.system.LocalFileSystem\n"),
])

modules = "composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt"
edit(modules, [
    ("                systemInstallSerializer = get(),\n            )\n        }\n        viewModel { params ->\n            DetailsAboutViewModel(",
     "                systemInstallSerializer = get(),\n                localFileSystem = get(),\n            )\n        }\n        viewModel { params ->\n            DetailsAboutViewModel("),
])

times = [
    "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/AppsViewModel.kt",
    "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/import/ExternalImportViewModel.kt",
    "feature/auth/data/src/commonMain/kotlin/zed/rainxch/auth/data/repository/AuthenticationRepositoryImpl.kt",
    "feature/auth/presentation/src/commonMain/kotlin/zed/rainxch/auth/presentation/AuthenticationViewModel.kt",
]
for f in times:
    p = pathlib.Path(f)
    s = p.read_text()
    assert "System.currentTimeMillis()" in s, f
    s = s.replace("System.currentTimeMillis()", "Clock.System.now().toEpochMilliseconds()")
    if "import kotlin.time.Clock\n" not in s:
        lines = s.split("\n")
        last = max(i for i, l in enumerate(lines) if l.startswith("import "))
        lines.insert(last + 1, "import kotlin.time.Clock")
        s = "\n".join(lines)
    p.write_text(s)
print("ok")
PY
```

If any `assert` fails, open the named file, find the current text of that line, and adapt that one replacement. Do not skip it.

- [ ] **Step 4: Run tests and regression compiles**

```bash
./gradlew --console=plain :core:presentation:jvmTest :feature:auth:data:jvmTest 2>&1 | tail -10
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -30
```

Expected: tests pass and the compile prints only `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add core feature composeApp
git commit -m "refactor: replace JVM-only APIs in feature and presentation commonMain" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---
### Task 6: Package readers for `.apk` and `.ipa`

**Files:**
- Modify: `core/data/build.gradle.kts` (already has `commonTest` from Task 4)
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/AndroidManifestParser.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/PropertyListParser.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/PackageArchiveReader.kt`
- Create (generated): `core/data/src/commonTest/kotlin/zed/rainxch/core/data/packages/PackageFixtures.kt`
- Create: `core/data/src/commonTest/kotlin/zed/rainxch/core/data/packages/PackageTestSupport.kt`
- Test: `core/data/src/commonTest/kotlin/zed/rainxch/core/data/packages/AndroidManifestParserTest.kt`, `PropertyListParserTest.kt`, `PackageArchiveReaderTest.kt`

The tests are in `commonTest`, not `jvmTest`, on purpose: they use only okio and `kotlin.test`, so they run on the JVM now and on the iOS simulator in Task 11. That is the only place the okio zip reading and the parsers are exercised on Kotlin/Native before the end-to-end run.

**Interfaces:**
- Produces: `internal data class ManifestInfo(packageName: String, versionName: String?, versionCode: Long, label: String?)`, `internal object AndroidManifestParser { fun parse(bytes: ByteArray): ManifestInfo? }` (parses the binary `AndroidManifest.xml` of an APK, returns `null` for anything malformed and never throws), `internal object PropertyListParser { fun parseTopLevelStrings(bytes: ByteArray): Map<String, String>? }` (binary or XML plist, top-level keys whose value is a string or integer, integers rendered in decimal, nested containers ignored, never throws), and `class PackageArchiveReader(fileSystem: FileSystem = FileSystem.SYSTEM) { fun read(filePath: String): ApkPackageInfo? }` which dispatches on the file extension (`apk`, `ipa`, case-insensitive) and returns `null` for any other extension, a missing file, or any unreadable package.
- `ApkPackageInfo` mapping for an APK: `packageName` from `package`, `versionName` from `versionName` or `"unknown"`, `versionCode` from `versionCode` combined with `versionCodeMajor` (`(major shl 32) or minor`), `appName` from the `<application android:label>` only when it is a literal string else `""`, `signingFingerprint` is `null`. For an IPA: `packageName` from `CFBundleIdentifier` (required), `versionName` from `CFBundleShortVersionString` else `CFBundleVersion` else `"unknown"`, `versionCode` from `CFBundleVersion` as a `Long` else `0`, `appName` from `CFBundleDisplayName` else `CFBundleName` else `""`, `signingFingerprint` is `null`.
- Test support (`commonTest`): `internal fun fixtureBytes(base64: String): ByteArray` and `internal class TempDir { val path: Path; fun write(name: String, bytes: ByteArray): String; fun zipEntry(zipFile: String, entryPath: String): ByteArray; fun delete() }`.

- [ ] **Step 1: Generate the test fixtures**

The fixtures are tiny real packages: an APK built by `aapt2` (the same tool that builds real manifests), two IPAs whose `Info.plist` is XML and binary (`plutil`), two IPA variants for version handling, and three deliberately broken archives. The plist contains a nested dictionary with a competing `CFBundleName` to prove only top-level keys are read.

```bash
set -e
export ANDROID_HOME=$HOME/Library/Android/sdk
cd /Users/mapan/Repo/komi-store
WORK=$(mktemp -d /tmp/komi-fixtures.XXXXXX)
AAPT2=$(ls $ANDROID_HOME/build-tools/*/aapt2 | tail -1)
ANDROID_JAR=$(ls $ANDROID_HOME/platforms/*/android.jar | tail -1)

cat > $WORK/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="zed.rainxch.fixture"
    android:versionCode="42"
    android:versionName="1.2.3">
    <application android:label="Fixture App" />
</manifest>
EOF
$AAPT2 link --manifest $WORK/AndroidManifest.xml -I $ANDROID_JAR -o $WORK/fixture.apk

cat > $WORK/Info.xml.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key>
    <string>zed.rainxch.fixture.ios</string>
    <key>CFBundleShortVersionString</key>
    <string>2.3.4</string>
    <key>CFBundleVersion</key>
    <string>17</string>
    <key>CFBundleDisplayName</key>
    <string>Fixture iOS</string>
    <key>CFBundleName</key>
    <string>FixtureName</string>
    <key>UIApplicationSceneManifest</key>
    <dict>
        <key>CFBundleName</key>
        <string>Nested Should Not Win</string>
        <key>UIApplicationSupportsMultipleScenes</key>
        <false/>
    </dict>
    <key>MinimumOSVersion</key>
    <string>15.0</string>
</dict>
</plist>
EOF
mkdir -p $WORK/xml/Payload/Fixture.app $WORK/bin/Payload/Fixture.app
cp $WORK/Info.xml.plist $WORK/xml/Payload/Fixture.app/Info.plist
(cd $WORK/xml && zip -q -r ../fixture-xml.ipa Payload)
plutil -convert binary1 -o $WORK/bin/Payload/Fixture.app/Info.plist $WORK/Info.xml.plist
(cd $WORK/bin && zip -q -r ../fixture-bin.ipa Payload)

make_ipa() {
    local name=$1 plist=$2
    mkdir -p $WORK/$name/Payload/App.app
    printf '%s' "$plist" > $WORK/$name/Payload/App.app/Info.plist
    (cd $WORK/$name && zip -q -r ../$name.ipa Payload)
}
make_ipa odd '<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>a.b.c</string><key>CFBundleVersion</key><string>1.2.3.4</string></dict></plist>'
make_ipa short '<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>a.b.c</string><key>CFBundleVersion</key><string>99</string><key>CFBundleShortVersionString</key><string>5.0</string></dict></plist>'

printf 'x' > $WORK/other.txt
(cd $WORK && zip -q no-manifest.zip other.txt)
mkdir -p $WORK/np/Payload/App.app && printf 'x' > $WORK/np/Payload/App.app/readme.txt
(cd $WORK/np && zip -q -r ../no-plist.zip Payload)
mkdir -p $WORK/bp/Payload/App.app && printf '\001\002\003' > $WORK/bp/Payload/App.app/Info.plist
(cd $WORK/bp && zip -q -r ../bad-plist.zip Payload)

mkdir -p core/data/src/commonTest/kotlin/zed/rainxch/core/data/packages
python3 -I - "$WORK" <<'PY'
import base64, pathlib, sys

work = pathlib.Path(sys.argv[1])
fixtures = [
    ("APK_BASE64", "fixture.apk"),
    ("IPA_XML_BASE64", "fixture-xml.ipa"),
    ("IPA_BINARY_BASE64", "fixture-bin.ipa"),
    ("IPA_ODD_VERSION_BASE64", "odd.ipa"),
    ("IPA_SHORT_VERSION_BASE64", "short.ipa"),
    ("ZIP_NO_MANIFEST_BASE64", "no-manifest.zip"),
    ("ZIP_NO_PLIST_BASE64", "no-plist.zip"),
    ("ZIP_BAD_PLIST_BASE64", "bad-plist.zip"),
]


def const(name, filename):
    encoded = base64.b64encode((work / filename).read_bytes()).decode()
    chunks = [encoded[i:i + 96] for i in range(0, len(encoded), 96)]
    body = " +\n        ".join(f'"{chunk}"' for chunk in chunks)
    return f"    const val {name} =\n        {body}\n"


out = (
    "package zed.rainxch.core.data.packages\n\n"
    "// Minimal APK built with aapt2, IPAs built with plutil and zip, and three deliberately broken archives.\n"
    "internal object PackageFixtures {\n"
    + "\n".join(const(name, filename) for name, filename in fixtures)
    + "}\n"
)
pathlib.Path("core/data/src/commonTest/kotlin/zed/rainxch/core/data/packages/PackageFixtures.kt").write_text(out)
print("wrote fixtures")
PY
```

Expected: `wrote fixtures`, and the file is about 10 KB.

- [ ] **Step 2: Write the test support and the failing tests**

`PackageTestSupport.kt`:

```kotlin
package zed.rainxch.core.data.packages

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
internal fun fixtureBytes(base64: String): ByteArray = Base64.decode(base64)

internal class TempDir {
    private val fileSystem = FileSystem.SYSTEM
    val path: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-pkg-${Random.nextLong().toString(16)}"

    init {
        fileSystem.createDirectories(path)
    }

    fun write(
        name: String,
        bytes: ByteArray,
    ): String {
        val file = path / name
        fileSystem.write(file) { write(bytes) }
        return file.toString()
    }

    fun zipEntry(
        zipFile: String,
        entryPath: String,
    ): ByteArray = fileSystem.openZip(zipFile.toPath()).read(entryPath.toPath()) { readByteArray() }

    fun delete() {
        fileSystem.deleteRecursively(path)
    }
}
```

`AndroidManifestParserTest.kt`:

```kotlin
package zed.rainxch.core.data.packages

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidManifestParserTest {
    private val temp = TempDir()

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun manifestBytes(): ByteArray {
        val apk = temp.write("fixture.apk", fixtureBytes(PackageFixtures.APK_BASE64))
        return temp.zipEntry(apk, "/AndroidManifest.xml")
    }

    @Test
    fun parsesPackageVersionAndLabel() {
        assertEquals(
            ManifestInfo(
                packageName = "zed.rainxch.fixture",
                versionName = "1.2.3",
                versionCode = 42L,
                label = "Fixture App",
            ),
            AndroidManifestParser.parse(manifestBytes()),
        )
    }

    @Test
    fun rejectsBytesThatAreNotABinaryManifest() {
        assertNull(AndroidManifestParser.parse(ByteArray(0)))
        assertNull(AndroidManifestParser.parse(byteArrayOf(1, 2, 3)))
        assertNull(AndroidManifestParser.parse(ByteArray(200) { (it * 7).toByte() }))
        assertNull(AndroidManifestParser.parse("<manifest package=\"a.b\"/>".encodeToByteArray()))
    }

    @Test
    fun truncatedInputNeverThrows() {
        val bytes = manifestBytes()
        for (length in 0..bytes.size) {
            AndroidManifestParser.parse(bytes.copyOf(length))
        }
    }

    @Test
    fun corruptedInputNeverThrows() {
        val bytes = manifestBytes()
        for (index in bytes.indices) {
            val copy = bytes.copyOf()
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            AndroidManifestParser.parse(copy)
        }
    }
}
```

`PropertyListParserTest.kt`:

```kotlin
package zed.rainxch.core.data.packages

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PropertyListParserTest {
    private val temp = TempDir()

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun plistBytes(base64Ipa: String): ByteArray {
        val ipa = temp.write("fixture.ipa", fixtureBytes(base64Ipa))
        return temp.zipEntry(ipa, "/Payload/Fixture.app/Info.plist")
    }

    private val expected =
        mapOf(
            "CFBundleIdentifier" to "zed.rainxch.fixture.ios",
            "CFBundleShortVersionString" to "2.3.4",
            "CFBundleVersion" to "17",
            "CFBundleDisplayName" to "Fixture iOS",
            "CFBundleName" to "FixtureName",
            "MinimumOSVersion" to "15.0",
        )

    @Test
    fun parsesXmlPlistTopLevelOnly() {
        val result = PropertyListParser.parseTopLevelStrings(plistBytes(PackageFixtures.IPA_XML_BASE64))
        assertNotNull(result)
        assertEquals(expected, result.filterKeys { it in expected })
    }

    @Test
    fun parsesBinaryPlistTopLevelOnly() {
        val result = PropertyListParser.parseTopLevelStrings(plistBytes(PackageFixtures.IPA_BINARY_BASE64))
        assertNotNull(result)
        assertEquals(expected, result.filterKeys { it in expected })
    }

    @Test
    fun decodesXmlEntitiesIntegersAndEmptyStrings() {
        val xml =
            """
            <plist version="1.0"><dict>
              <key>A</key><string>a &amp; b &lt;c&gt; &quot;d&quot;</string>
              <key>B</key><integer>9</integer>
              <key>C</key><string/>
              <key>D</key><true/>
              <key>E</key><array><string>ignored</string></array>
              <key>F</key><string>after</string>
            </dict></plist>
            """.trimIndent()
        val result = PropertyListParser.parseTopLevelStrings(xml.encodeToByteArray())
        assertEquals(mapOf("A" to "a & b <c> \"d\"", "B" to "9", "C" to "", "F" to "after"), result)
    }

    @Test
    fun rejectsDataThatIsNotAPropertyList() {
        assertNull(PropertyListParser.parseTopLevelStrings(ByteArray(0)))
        assertNull(PropertyListParser.parseTopLevelStrings("hello".encodeToByteArray()))
        assertNull(PropertyListParser.parseTopLevelStrings("bplist00garbage".encodeToByteArray()))
        assertNull(PropertyListParser.parseTopLevelStrings(ByteArray(300) { (it * 13).toByte() }))
    }

    @Test
    fun truncatedAndCorruptedBinaryPlistNeverThrows() {
        val bytes = plistBytes(PackageFixtures.IPA_BINARY_BASE64)
        for (length in 0..bytes.size) {
            PropertyListParser.parseTopLevelStrings(bytes.copyOf(length))
        }
        for (index in bytes.indices) {
            val copy = bytes.copyOf()
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            PropertyListParser.parseTopLevelStrings(copy)
        }
    }
}
```

`PackageArchiveReaderTest.kt`:

```kotlin
package zed.rainxch.core.data.packages

import okio.FileSystem
import zed.rainxch.core.domain.model.apk.ApkPackageInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageArchiveReaderTest {
    private val temp = TempDir()
    private val reader = PackageArchiveReader(FileSystem.SYSTEM)

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun write(
        name: String,
        base64: String,
    ): String = temp.write(name, fixtureBytes(base64))

    @Test
    fun readsApk() {
        assertEquals(
            ApkPackageInfo(
                packageName = "zed.rainxch.fixture",
                versionName = "1.2.3",
                versionCode = 42L,
                appName = "Fixture App",
                signingFingerprint = null,
            ),
            reader.read(write("app.apk", PackageFixtures.APK_BASE64)),
        )
    }

    @Test
    fun readsIpaWithXmlAndBinaryPlist() {
        val expected =
            ApkPackageInfo(
                packageName = "zed.rainxch.fixture.ios",
                versionName = "2.3.4",
                versionCode = 17L,
                appName = "Fixture iOS",
                signingFingerprint = null,
            )
        assertEquals(expected, reader.read(write("xml.ipa", PackageFixtures.IPA_XML_BASE64)))
        assertEquals(expected, reader.read(write("bin.IPA", PackageFixtures.IPA_BINARY_BASE64)))
    }

    @Test
    fun nonIntegerBuildNumberBecomesVersionCodeZero() {
        val info = reader.read(write("odd.ipa", PackageFixtures.IPA_ODD_VERSION_BASE64))
        assertEquals(0L, info?.versionCode)
        assertEquals("1.2.3.4", info?.versionName)
    }

    @Test
    fun shortVersionIsPreferredForVersionName() {
        val info = reader.read(write("short.ipa", PackageFixtures.IPA_SHORT_VERSION_BASE64))
        assertEquals("5.0", info?.versionName)
        assertEquals(99L, info?.versionCode)
    }

    @Test
    fun unknownExtensionMissingFileAndEmptyFileReturnNull() {
        assertNull(reader.read(write("app.zip", PackageFixtures.APK_BASE64)))
        assertNull(reader.read(temp.path.toString() + "/missing.apk"))
        assertNull(reader.read(temp.write("empty.ipa", ByteArray(0))))
    }

    @Test
    fun brokenPackagesReturnNull() {
        val apk = fixtureBytes(PackageFixtures.APK_BASE64)
        assertNull(reader.read(temp.write("truncated.apk", apk.copyOf(100))))
        assertNull(reader.read(temp.write("notzip.apk", "not a zip file at all".encodeToByteArray())))
        assertNull(reader.read(write("nomanifest.apk", PackageFixtures.ZIP_NO_MANIFEST_BASE64)))
        assertNull(reader.read(write("nopayload.ipa", PackageFixtures.ZIP_NO_MANIFEST_BASE64)))
        assertNull(reader.read(write("noplist.ipa", PackageFixtures.ZIP_NO_PLIST_BASE64)))
        assertNull(reader.read(write("badplist.ipa", PackageFixtures.ZIP_BAD_PLIST_BASE64)))
    }
}
```

- [ ] **Step 3: Run and watch them fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew --console=plain :core:data:jvmTest --tests "*packages*" 2>&1 | tail -15
```

Expected: compilation FAILS (`Unresolved reference: AndroidManifestParser`, `PropertyListParser`, `PackageArchiveReader`, `ManifestInfo`).

- [ ] **Step 4: Implement `AndroidManifestParser`**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/AndroidManifestParser.kt`:

```kotlin
package zed.rainxch.core.data.packages

internal data class ManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
)

internal object AndroidManifestParser {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val NO_ENTRY = -1
    private const val MAX_STRINGS = 200_000
    private const val ELEMENT_BODY_OFFSET = 16
    private const val MIN_ATTRIBUTE_SIZE = 20

    fun parse(bytes: ByteArray): ManifestInfo? =
        try {
            parseOrThrow(bytes)
        } catch (_: Exception) {
            null
        }

    private fun parseOrThrow(b: ByteArray): ManifestInfo? {
        if (b.size < 8 || u16(b, 0) != RES_XML_TYPE) return null

        val declaredSize = u32(b, 4).toLong() and 0xFFFFFFFFL
        val end = minOf(declaredSize, b.size.toLong()).toInt()
        var position = u16(b, 2)
        var strings: List<String> = emptyList()
        var manifest: Map<String, Any?>? = null
        var application: Map<String, Any?>? = null

        while (position + 8 <= end) {
            val type = u16(b, position)
            val size = u32(b, position + 4)
            if (size < 8 || size > end - position) break

            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(b, position)
                RES_XML_START_ELEMENT_TYPE -> {
                    val (name, attributes) = readElement(b, position, strings)
                    when (name) {
                        "manifest" -> if (manifest == null) manifest = attributes
                        "application" -> if (application == null) application = attributes
                    }
                }
            }
            position += size
        }

        return toInfo(manifest ?: return null, application)
    }

    private fun toInfo(
        manifest: Map<String, Any?>,
        application: Map<String, Any?>?,
    ): ManifestInfo? {
        val packageName = (manifest["package"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val minor = (manifest["versionCode"] as? Long) ?: 0L
        val major = (manifest["versionCodeMajor"] as? Long) ?: 0L
        return ManifestInfo(
            packageName = packageName,
            versionName = manifest["versionName"] as? String,
            versionCode = (major shl 32) or minor,
            label = application?.get("label") as? String,
        )
    }

    private fun readStringPool(
        b: ByteArray,
        chunkStart: Int,
    ): List<String> {
        val headerSize = u16(b, chunkStart + 2)
        val count = u32(b, chunkStart + 8)
        val flags = u32(b, chunkStart + 16)
        val stringsStart = u32(b, chunkStart + 20)
        require(count in 0..MAX_STRINGS)

        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsStart = chunkStart + headerSize
        return List(count) { index ->
            var offset = chunkStart + stringsStart + u32(b, offsetsStart + 4 * index)
            if (utf8) {
                val firstLengthByte = u8(b, offset)
                offset += if ((firstLengthByte and 0x80) != 0) 2 else 1
                var byteLength = u8(b, offset)
                offset += 1
                if ((byteLength and 0x80) != 0) {
                    byteLength = ((byteLength and 0x7F) shl 8) or u8(b, offset)
                    offset += 1
                }
                b.decodeToString(offset, offset + byteLength)
            } else {
                var length = u16(b, offset)
                offset += 2
                if ((length and 0x8000) != 0) {
                    length = ((length and 0x7FFF) shl 16) or u16(b, offset)
                    offset += 2
                }
                require(length >= 0 && offset + 2L * length <= b.size)
                CharArray(length) { u16(b, offset + 2 * it).toChar() }.concatToString()
            }
        }
    }

    private fun readElement(
        b: ByteArray,
        chunkStart: Int,
        strings: List<String>,
    ): Pair<String?, Map<String, Any?>> {
        val nameIndex = u32(b, chunkStart + 20)
        val attributeStart = u16(b, chunkStart + 24)
        val attributeSize = u16(b, chunkStart + 26)
        val attributeCount = u16(b, chunkStart + 28)
        require(attributeSize >= MIN_ATTRIBUTE_SIZE)

        val first = chunkStart + ELEMENT_BODY_OFFSET + attributeStart
        val attributes = HashMap<String, Any?>()
        for (index in 0 until attributeCount) {
            val attribute = first + index * attributeSize
            val key = strings.getOrNull(u32(b, attribute + 4)) ?: continue
            attributes[key] = attributeValue(b, attribute, strings)
        }
        return strings.getOrNull(nameIndex) to attributes
    }

    private fun attributeValue(
        b: ByteArray,
        attribute: Int,
        strings: List<String>,
    ): Any? {
        val raw = u32(b, attribute + 8)
        val dataType = u8(b, attribute + 15)
        val data = u32(b, attribute + 16)
        return when (dataType) {
            TYPE_STRING -> strings.getOrNull(data)
            TYPE_INT_DEC, TYPE_INT_HEX -> data.toLong() and 0xFFFFFFFFL
            else -> if (raw != NO_ENTRY) strings.getOrNull(raw) else null
        }
    }

    private fun u8(
        b: ByteArray,
        offset: Int,
    ): Int = b[offset].toInt() and 0xFF

    private fun u16(
        b: ByteArray,
        offset: Int,
    ): Int = u8(b, offset) or (u8(b, offset + 1) shl 8)

    private fun u32(
        b: ByteArray,
        offset: Int,
    ): Int = u16(b, offset) or (u16(b, offset + 2) shl 16)
}
```

- [ ] **Step 5: Implement `PropertyListParser`**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/PropertyListParser.kt`:

```kotlin
package zed.rainxch.core.data.packages

internal object PropertyListParser {
    private val BINARY_MAGIC = "bplist00".encodeToByteArray()
    private val XML_TOKEN = Regex("""<(/?)([A-Za-z]+)\s*(/?)>|([^<]+)""")

    fun parseTopLevelStrings(bytes: ByteArray): Map<String, String>? =
        try {
            if (isBinary(bytes)) {
                BinaryPlist(bytes).topLevelStrings()
            } else {
                parseXml(bytes.decodeToString())
            }
        } catch (_: Exception) {
            null
        }

    private fun isBinary(bytes: ByteArray): Boolean =
        bytes.size >= BINARY_MAGIC.size && BINARY_MAGIC.indices.all { bytes[it] == BINARY_MAGIC[it] }

    private fun parseXml(text: String): Map<String, String>? {
        val start = text.indexOf("<dict>")
        if (start < 0) return null

        var depth = 0
        var key: String? = null
        var pending: StringBuilder? = null
        val result = LinkedHashMap<String, String>()

        for (match in XML_TOKEN.findAll(text, start)) {
            val (closing, name, selfClosing, characters) = match.destructured
            if (name.isEmpty()) {
                pending = (pending ?: StringBuilder()).append(characters)
                continue
            }

            val isClose = closing.isNotEmpty()
            val isSelf = selfClosing.isNotEmpty()
            val isContainer = name == "dict" || name == "array"

            when {
                isContainer && !isClose && !isSelf -> {
                    depth++
                    pending = null
                }

                isContainer && isClose -> {
                    depth--
                    pending = null
                    key = null
                    if (depth == 0) break
                }

                depth != 1 -> pending = null

                name == "key" && !isClose && !isSelf -> pending = null

                name == "key" && isClose -> {
                    key = pending?.toString()?.trim()
                    pending = null
                }

                isClose || isSelf -> {
                    val currentKey = key
                    if (currentKey != null) {
                        when {
                            name == "string" && isSelf -> result[currentKey] = ""
                            name == "string" -> result[currentKey] = unescape(pending?.toString().orEmpty())
                            (name == "integer" || name == "real") && !isSelf ->
                                result[currentKey] = pending?.toString().orEmpty().trim()
                        }
                    }
                    key = null
                    pending = null
                }

                else -> pending = null
            }
        }
        return result
    }

    private fun unescape(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private class BinaryPlist(
        private val b: ByteArray,
    ) {
        private val offsetSize: Int
        private val refSize: Int
        private val objectCount: Int
        private val topObject: Int
        private val tableOffset: Int

        init {
            require(b.size >= MIN_SIZE)
            val trailer = b.size - TRAILER_SIZE
            offsetSize = u8(trailer + 6)
            refSize = u8(trailer + 7)
            require(offsetSize in 1..8 && refSize in 1..8)
            objectCount = checkedInt(readBigEndian(trailer + 8, 8))
            topObject = checkedInt(readBigEndian(trailer + 16, 8))
            tableOffset = checkedInt(readBigEndian(trailer + 24, 8))
            require(objectCount in 1..MAX_OBJECTS && topObject in 0 until objectCount)
            require(tableOffset >= BINARY_MAGIC.size && tableOffset.toLong() + objectCount.toLong() * offsetSize <= b.size)
        }

        fun topLevelStrings(): Map<String, String>? {
            val offset = objectOffset(topObject)
            if ((u8(offset) shr 4) != DICT_TYPE) return null

            val (count, start) = readLength(offset)
            require(start.toLong() + 2L * count * refSize <= b.size)
            val result = LinkedHashMap<String, String>()
            for (index in 0 until count) {
                val key = scalar(readRef(start + index * refSize)) as? String ?: continue
                when (val value = scalar(readRef(start + (count + index) * refSize))) {
                    is String -> result[key] = value
                    is Long -> result[key] = value.toString()
                }
            }
            return result
        }

        private fun scalar(index: Int): Any? {
            val offset = objectOffset(index)
            val marker = u8(offset)
            return when (marker shr 4) {
                0x0 ->
                    when (marker) {
                        0x08 -> false
                        0x09 -> true
                        else -> null
                    }

                0x1 -> {
                    val size = 1 shl (marker and 0x0F)
                    if (size > 8) null else readBigEndian(offset + 1, size)
                }

                0x5 -> {
                    val (length, start) = readLength(offset)
                    require(start.toLong() + length <= b.size)
                    b.decodeToString(start, start + length)
                }

                0x6 -> {
                    val (length, start) = readLength(offset)
                    require(start.toLong() + 2L * length <= b.size)
                    CharArray(length) { ((u8(start + 2 * it) shl 8) or u8(start + 2 * it + 1)).toChar() }
                        .concatToString()
                }

                else -> null
            }
        }

        private fun readLength(offset: Int): Pair<Int, Int> {
            val low = u8(offset) and 0x0F
            if (low != 0x0F) return low to offset + 1
            val intMarker = u8(offset + 1)
            require((intMarker shr 4) == 0x1)
            val size = 1 shl (intMarker and 0x0F)
            require(size <= 4)
            return checkedInt(readBigEndian(offset + 2, size)) to offset + 2 + size
        }

        private fun objectOffset(index: Int): Int {
            val offset = checkedInt(readBigEndian(tableOffset + index * offsetSize, offsetSize))
            require(offset in 0 until b.size)
            return offset
        }

        private fun readRef(position: Int): Int {
            val ref = checkedInt(readBigEndian(position, refSize))
            require(ref in 0 until objectCount)
            return ref
        }

        private fun readBigEndian(
            offset: Int,
            size: Int,
        ): Long {
            var value = 0L
            for (index in 0 until size) {
                value = (value shl 8) or u8(offset + index).toLong()
            }
            return value
        }

        private fun checkedInt(value: Long): Int {
            require(value in 0..Int.MAX_VALUE.toLong())
            return value.toInt()
        }

        private fun u8(offset: Int): Int = b[offset].toInt() and 0xFF

        private companion object {
            const val TRAILER_SIZE = 32
            const val MIN_SIZE = 8 + TRAILER_SIZE
            const val MAX_OBJECTS = 1_000_000
            const val DICT_TYPE = 0xD
        }
    }
}
```

- [ ] **Step 6: Implement `PackageArchiveReader`**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/PackageArchiveReader.kt`:

```kotlin
package zed.rainxch.core.data.packages

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import zed.rainxch.core.domain.model.apk.ApkPackageInfo

class PackageArchiveReader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun read(filePath: String): ApkPackageInfo? =
        when (filePath.substringAfterLast('.', "").lowercase()) {
            "apk" -> readApk(filePath)
            "ipa" -> readIpa(filePath)
            else -> null
        }

    private fun readApk(filePath: String): ApkPackageInfo? =
        withZip(filePath) { zip ->
            val bytes = zip.readEntry("/AndroidManifest.xml".toPath()) ?: return@withZip null
            val manifest = AndroidManifestParser.parse(bytes) ?: return@withZip null
            ApkPackageInfo(
                packageName = manifest.packageName,
                versionName = manifest.versionName ?: UNKNOWN_VERSION,
                versionCode = manifest.versionCode,
                appName = manifest.label.orEmpty(),
                signingFingerprint = null,
            )
        }

    private fun readIpa(filePath: String): ApkPackageInfo? =
        withZip(filePath) { zip ->
            val appDirectory =
                zip
                    .listOrNull("/Payload".toPath())
                    ?.filter { it.name.endsWith(".app", ignoreCase = true) }
                    ?.minByOrNull { it.name }
                    ?: return@withZip null
            val bytes = zip.readEntry(appDirectory / "Info.plist") ?: return@withZip null
            val plist = PropertyListParser.parseTopLevelStrings(bytes) ?: return@withZip null

            val identifier = plist["CFBundleIdentifier"]?.takeIf { it.isNotBlank() } ?: return@withZip null
            val buildNumber = plist["CFBundleVersion"]
            ApkPackageInfo(
                packageName = identifier,
                versionName = plist["CFBundleShortVersionString"] ?: buildNumber ?: UNKNOWN_VERSION,
                versionCode = buildNumber?.toLongOrNull() ?: 0L,
                appName = plist["CFBundleDisplayName"] ?: plist["CFBundleName"] ?: "",
                signingFingerprint = null,
            )
        }

    private fun <T> withZip(
        filePath: String,
        block: (FileSystem) -> T?,
    ): T? =
        try {
            val zipPath = filePath.toPath()
            if (fileSystem.exists(zipPath)) block(fileSystem.openZip(zipPath)) else null
        } catch (_: Exception) {
            null
        }

    private fun FileSystem.readEntry(path: Path): ByteArray? {
        val metadata = metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile) return null
        if ((metadata.size ?: 0L) > MAX_ENTRY_BYTES) return null
        return source(path).buffer().use { it.readByteArray() }
    }

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    }
}
```

If `import okio.openZip` does not resolve for the iOS targets when Task 12 compiles them, that is the "Okio zip on iOS" risk named in the spec. STOP at that point and report to the user instead of writing a custom zip reader.

- [ ] **Step 7: Run the tests**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*packages*" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests in the three classes pass. If a parser test fails, fix the parser, not the test: the fixtures are the ground truth produced by `aapt2` and `plutil`.

- [ ] **Step 8: Commit**

```bash
git add core/data
git commit -m "feat(data): read package metadata from apk and ipa files" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Safe file names and a streaming downloader in `commonMain`

**Files:**
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/DownloadFileName.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/StreamingFileDownloader.kt`
- Test: `core/data/src/jvmTest/kotlin/zed/rainxch/core/data/download/DownloadFileNameTest.kt`, `StreamingFileDownloaderTest.kt`

**Interfaces:**
- Consumes: `DownloadProgress(bytesDownloaded: Long, totalBytes: Long?, percent: Int?)` from `core/domain`, `GithubAssetAuth` from `core/data`.
- Produces: `object DownloadFileName { fun sanitize(suggested: String?, url: String): String }` (keeps only the last path segment, strips control characters, trims, throws `IllegalArgumentException` for blank, `.` or `..`), `fun downloadedFilePath(downloadsDir: String, assetName: String?): Path?` (`null` for a null, blank, or invalid name), and `class StreamingFileDownloader(clientFactory: () -> HttpClient, tokenProvider: suspend () -> String?, fileSystem: FileSystem = FileSystem.SYSTEM)` with `fun download(url: String, directory: String, fileName: String?): Flow<DownloadProgress>` and `suspend fun cancel(fileName: String): Boolean`.
- Contract of `download`: `clientFactory` returns a client configured with `followRedirects = false`; the downloader closes it. Redirects are followed manually up to 10 hops and the `Authorization` header is only sent on the first hop and only to a GitHub host (matching OkHttp, which strips it on cross-host redirects). Bytes go to `<name>.part-<random>` inside `directory` and are moved onto `<name>` only after a non-empty complete transfer, replacing any existing file. On any failure or cancellation the part file is deleted and the flow fails; a cancel through `cancel(fileName)` fails the flow with `kotlinx.io.IOException`.

- [ ] **Step 1: Write the failing tests**

`DownloadFileNameTest.kt`:

```kotlin
package zed.rainxch.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFileNameTest {
    @Test
    fun keepsOnlyTheLastPathSegment() {
        assertEquals("evil.ipa", DownloadFileName.sanitize("../../evil.ipa", "https://x/y"))
        assertEquals("c.apk", DownloadFileName.sanitize("a\\b\\c.apk", "https://x/y"))
        assertEquals("a.ipa", DownloadFileName.sanitize("/abs/path/a.ipa", "https://x/y"))
    }

    @Test
    fun stripsControlCharactersAndTrims() {
        assertEquals("foobar.ipa", DownloadFileName.sanitize("foo\u0000bar.ipa", "https://x/y"))
        assertEquals("spaced name.ipa", DownloadFileName.sanitize("  spaced name.ipa ", "https://x/y"))
    }

    @Test
    fun keepsUnicodeNames() {
        assertEquals("应用 1.0.ipa", DownloadFileName.sanitize("应用 1.0.ipa", "https://x/y"))
    }

    @Test
    fun fallsBackToTheUrlWhenNoNameIsSuggested() {
        assertEquals("app.ipa", DownloadFileName.sanitize(null, "https://x/y/app.ipa?token=1#frag"))
        assertEquals("app.ipa", DownloadFileName.sanitize("  ", "https://x/y/app.ipa"))
    }

    @Test
    fun generatesANameWhenNothingUsableExists() {
        assertTrue(DownloadFileName.sanitize(null, "https://x/").startsWith("asset-"))
    }

    @Test
    fun rejectsDotNames() {
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize("..", "https://x/y") }
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize("a/..", "https://x/y") }
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize(".", "https://x/y") }
    }

    @Test
    fun downloadedFilePathUsesTheSanitizedName() {
        assertEquals("/docs/evil.ipa", downloadedFilePath("/docs", "../evil.ipa").toString())
        assertNull(downloadedFilePath("/docs", null))
        assertNull(downloadedFilePath("/docs", " "))
        assertNull(downloadedFilePath("/docs", ".."))
    }
}
```

`StreamingFileDownloaderTest.kt`:

```kotlin
package zed.rainxch.core.data.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import zed.rainxch.core.domain.model.installation.DownloadProgress
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingFileDownloaderTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-dl-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun downloader(
        token: String? = null,
        handler: MockRequestHandler,
    ) = StreamingFileDownloader(
        clientFactory = { HttpClient(MockEngine(handler)) { followRedirects = false } },
        tokenProvider = { token },
        fileSystem = fileSystem,
    )

    private suspend fun StreamingFileDownloader.collect(
        url: String,
        name: String?,
    ): List<DownloadProgress> = download(url, dir.toString(), name).toList()

    private fun filesInDir(): List<String> = fileSystem.list(dir).map { it.name }

    @Test
    fun savesTheFileAndReportsProgress() =
        runBlocking {
            val body = "hello".encodeToByteArray()
            val progress =
                downloader {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
                }.collect("https://example.com/a.ipa", "a.ipa")

            assertEquals("hello", fileSystem.read(dir / "a.ipa") { readUtf8() })
            assertEquals(5L, progress.last().bytesDownloaded)
            assertEquals(100, progress.last().percent)
            assertEquals(listOf("a.ipa"), filesInDir())
        }

    @Test
    fun progressPercentIsNullWhenLengthIsUnknown() =
        runBlocking {
            val progress =
                downloader { respond("abc".encodeToByteArray(), HttpStatusCode.OK) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            assertNull(progress.last().totalBytes)
            assertEquals(3L, progress.last().bytesDownloaded)
        }

    @Test
    fun httpErrorLeavesNoFile() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader { respond("nope", HttpStatusCode.NotFound) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun emptyBodyFailsAndLeavesNoFile() =
        runBlocking {
            assertFailsWith<Exception> {
                downloader { respond(ByteArray(0), HttpStatusCode.OK) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun existingFileIsReplaced() =
        runBlocking {
            fileSystem.write(dir / "a.ipa") { writeUtf8("old content that is longer") }
            downloader { respond("new!".encodeToByteArray(), HttpStatusCode.OK) }
                .collect("https://example.com/a.ipa", "a.ipa")
            assertEquals("new!", fileSystem.read(dir / "a.ipa") { readUtf8() })
            assertEquals(listOf("a.ipa"), filesInDir())
        }

    @Test
    fun pathTraversalNamesStayInsideTheDirectory() =
        runBlocking {
            downloader { respond("x".encodeToByteArray(), HttpStatusCode.OK) }
                .collect("https://example.com/a.ipa", "../../evil.ipa")
            assertEquals(listOf("evil.ipa"), filesInDir())
        }

    @Test
    fun authorizationIsOnlySentOnTheFirstHop() =
        runBlocking {
            val seen = mutableListOf<Triple<String, String?, String?>>()
            downloader(token = "secret") { request ->
                seen += Triple(
                    request.url.toString(),
                    request.headers[HttpHeaders.Authorization],
                    request.headers[HttpHeaders.Accept],
                )
                if (request.url.host == "api.github.com") {
                    respond(
                        ByteArray(0),
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://example.com/blob/a.ipa"),
                    )
                } else {
                    respond("data".encodeToByteArray(), HttpStatusCode.OK)
                }
            }.collect("https://api.github.com/repos/o/r/releases/assets/1", "a.ipa")

            assertEquals(2, seen.size)
            assertEquals("Bearer secret", seen[0].second)
            assertEquals("application/octet-stream", seen[0].third)
            assertEquals("https://example.com/blob/a.ipa", seen[1].first)
            assertNull(seen[1].second)
        }

    @Test
    fun tokenIsNotSentToNonGithubHosts() =
        runBlocking {
            var authorization: String? = "unset"
            downloader(token = "secret") { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                respond("data".encodeToByteArray(), HttpStatusCode.OK)
            }.collect("https://example.com/a.ipa", "a.ipa")
            assertNull(authorization)
        }

    @Test
    fun relativeRedirectIsResolvedAgainstTheCurrentUrl() =
        runBlocking {
            val urls = mutableListOf<String>()
            downloader { request ->
                urls += request.url.toString()
                if (request.url.encodedPath == "/start") {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final/a.ipa"))
                } else {
                    respond("data".encodeToByteArray(), HttpStatusCode.OK)
                }
            }.collect("https://example.com/start", "a.ipa")
            assertEquals(listOf("https://example.com/start", "https://example.com/final/a.ipa"), urls)
        }

    @Test
    fun redirectLoopFailsAndLeavesNoFile() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/again"))
                }.collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun redirectWithoutLocationFails() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader { respond(ByteArray(0), HttpStatusCode.Found) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
        }

    @Test
    fun cancelByNameFailsTheDownloadAndLeavesNoFile() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val downloader =
                downloader {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val result = async { runCatching { downloader.collect("https://example.com/slow.ipa", "slow.ipa") } }

            withTimeout(5_000) { started.await() }
            assertTrue(downloader.cancel("slow.ipa"))

            val outcome = withTimeout(5_000) { result.await() }
            assertTrue(outcome.exceptionOrNull() is IOException)
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun cancelOfUnknownNameReturnsFalse() =
        runBlocking {
            assertEquals(false, downloader { respond("x", HttpStatusCode.OK) }.cancel("nothing.ipa"))
        }
}
```

- [ ] **Step 2: Run and watch them fail**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*download*" 2>&1 | tail -15
```

Expected: compilation FAILS (`Unresolved reference: DownloadFileName`, `StreamingFileDownloader`).

- [ ] **Step 3: Implement `DownloadFileName`**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/DownloadFileName.kt`:

```kotlin
package zed.rainxch.core.data.download

import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

object DownloadFileName {
    fun sanitize(
        suggested: String?,
        url: String,
    ): String {
        val raw =
            suggested?.takeIf { it.isNotBlank() }
                ?: url
                    .substringAfterLast('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .ifBlank { "asset-${Random.nextLong().toString(16)}" }
        val name =
            raw
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { it.code >= 0x20 && it.code != 0x7F }
                .trim()
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid file name: $raw" }
        return name
    }
}

fun downloadedFilePath(
    downloadsDir: String,
    assetName: String?,
): Path? {
    val name = assetName?.takeIf { it.isNotBlank() } ?: return null
    val safeName =
        try {
            DownloadFileName.sanitize(name, "")
        } catch (_: IllegalArgumentException) {
            return null
        }
    return downloadsDir.toPath() / safeName
}
```

- [ ] **Step 4: Implement `StreamingFileDownloader`**

`core/data/src/commonMain/kotlin/zed/rainxch/core/data/download/StreamingFileDownloader.kt`:

```kotlin
package zed.rainxch.core.data.download

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import zed.rainxch.core.data.network.GithubAssetAuth
import zed.rainxch.core.domain.model.installation.DownloadProgress
import kotlin.random.Random

class StreamingFileDownloader(
    private val clientFactory: () -> HttpClient,
    private val tokenProvider: suspend () -> String?,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val registryLock = Mutex()
    private val active = HashMap<String, MutableSet<Job>>()

    fun download(
        url: String,
        directory: String,
        fileName: String?,
    ): Flow<DownloadProgress> =
        channelFlow {
            val safeName = DownloadFileName.sanitize(fileName, url)
            val directoryPath = directory.toPath()
            fileSystem.createDirectories(directoryPath)
            val destination = directoryPath / safeName
            val partFile = directoryPath / "$safeName.part-${Random.nextLong().toString(16)}"

            val worker =
                launch {
                    try {
                        val (downloaded, total) =
                            transfer(url, partFile) { bytes, totalBytes ->
                                send(DownloadProgress(bytes, totalBytes, percentOf(bytes, totalBytes)))
                            }
                        fileSystem.atomicMove(partFile, destination)
                        send(DownloadProgress(downloaded, total, percentOf(downloaded, total) ?: 100))
                    } catch (e: Throwable) {
                        fileSystem.delete(partFile, mustExist = false)
                        throw e
                    }
                }

            registryLock.withLock { active.getOrPut(safeName) { mutableSetOf() }.add(worker) }
            try {
                worker.join()
            } finally {
                withContext(NonCancellable) {
                    registryLock.withLock { active[safeName]?.remove(worker) }
                }
            }
            if (worker.isCancelled) throw IOException("Download cancelled")
        }

    suspend fun cancel(fileName: String): Boolean {
        val name =
            try {
                DownloadFileName.sanitize(fileName, "")
            } catch (_: IllegalArgumentException) {
                return false
            }
        val jobs = registryLock.withLock { active.remove(name)?.toList().orEmpty() }
        jobs.forEach { it.cancel() }
        return jobs.isNotEmpty()
    }

    private suspend fun transfer(
        url: String,
        partFile: Path,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Pair<Long, Long?> {
        val http = clientFactory()
        try {
            val token = tokenProvider()
            var currentUrl = url
            var hop = 0
            while (true) {
                val requestUrl = currentUrl
                val outcome =
                    http
                        .prepareGet(requestUrl) {
                            if (hop == 0 && token != null && GithubAssetAuth.isGithubHost(requestUrl)) {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                if (GithubAssetAuth.isGithubApiHost(requestUrl)) {
                                    header(HttpHeaders.Accept, "application/octet-stream")
                                }
                            }
                        }.execute { response -> handle(response, partFile, onProgress) }

                when (outcome) {
                    is Outcome.Done -> return outcome.downloaded to outcome.total
                    is Outcome.Redirect -> {
                        hop += 1
                        if (hop > MAX_REDIRECTS) throw IOException("Too many redirects")
                        currentUrl = URLBuilder(requestUrl).takeFrom(outcome.location).buildString()
                    }
                }
            }
        } finally {
            http.close()
        }
    }

    private suspend fun handle(
        response: HttpResponse,
        partFile: Path,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Outcome {
        val status = response.status.value
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location] ?: throw IOException("Redirect $status without Location")
            return Outcome.Redirect(location)
        }
        if (!response.status.isSuccess()) throw IOException("Unexpected code $status")

        val total = response.contentLength()?.takeIf { it > 0 }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = 0L
        fileSystem.sink(partFile).buffer().use { sink ->
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                downloaded += read
                onProgress(downloaded, total)
            }
        }
        if (downloaded == 0L) throw IOException("Download produced an empty file")
        return Outcome.Done(downloaded, total)
    }

    private fun percentOf(
        downloaded: Long,
        total: Long?,
    ): Int? = if (total != null && total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null

    private sealed interface Outcome {
        data class Redirect(
            val location: String,
        ) : Outcome

        data class Done(
            val downloaded: Long,
            val total: Long?,
        ) : Outcome
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_REDIRECTS = 10
    }
}
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*download*" 2>&1 | tail -25
```

Expected: `BUILD SUCCESSFUL` and all tests pass. If a Ktor API name differs in 3.4.0 (for example `readAvailable` is a member rather than an extension, or `contentLength` lives in another package), fix the import or call to match the compiler error; the behavior under test does not change.

- [ ] **Step 6: Commit**

```bash
git add core/data
git commit -m "feat(data): add streaming file downloader with safe names and atomic replace" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Downloaded-file store and share hook

**Files:**
- Create: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/system/DownloadedFileStore.kt`
- Modify: `core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/helpers/ShareManager.kt`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/services/DefaultDownloadedFileStore.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/di/SharedModule.kt`
- Test: `core/data/src/jvmTest/kotlin/zed/rainxch/core/data/services/DefaultDownloadedFileStoreTest.kt`

**Interfaces:**
- Consumes: `downloadedFilePath` (Task 7), `Platform.tracksDownloadedFiles` (Task 2), `FileLocationsProvider`.
- Produces: `interface DownloadedFileStore { fun isFilePresent(app: InstalledApp): Boolean }` (true on every platform except iOS, where it checks that `userDownloadsDir()/<installedAssetName>` exists), `class DefaultDownloadedFileStore(files: FileLocationsProvider, platform: Platform, fileSystem: FileSystem = FileSystem.SYSTEM)`, a Koin `single<DownloadedFileStore>`, and `ShareManager.shareExistingFile(filePath: String): Boolean` with a default body returning `false` so Android and Desktop need no change.

- [ ] **Step 1: Write the failing test**

`DefaultDownloadedFileStoreTest.kt`:

```kotlin
package zed.rainxch.core.data.services

import okio.FileSystem
import okio.Path
import zed.rainxch.core.domain.model.installation.InstallSource
import zed.rainxch.core.domain.model.installation.InstalledApp
import zed.rainxch.core.domain.model.system.Platform
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultDownloadedFileStoreTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-store-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun locations() =
        object : FileLocationsProvider {
            override fun appDownloadsDir() = dir.toString()

            override fun userDownloadsDir() = dir.toString()

            override fun setExecutableIfNeeded(path: String) = Unit

            override fun getCacheSizeBytes() = 0L

            override fun clearCacheFiles() = true
        }

    private fun app(assetName: String?) =
        InstalledApp(
            packageName = "a.b.c",
            repoId = 1L,
            repoName = "repo",
            repoOwner = "owner",
            repoOwnerAvatarUrl = "",
            repoDescription = null,
            primaryLanguage = null,
            repoUrl = "",
            installedVersion = "1.0.0",
            installedAssetName = assetName,
            installedAssetUrl = null,
            latestVersion = null,
            latestAssetName = null,
            latestAssetUrl = null,
            latestAssetSize = null,
            appName = "App",
            installSource = InstallSource.THIS_APP,
            installedAt = 0L,
            lastCheckedAt = 0L,
            lastUpdatedAt = 0L,
            isUpdateAvailable = false,
            signingFingerprint = null,
            systemArchitecture = "AARCH64",
            fileExtension = "ipa",
        )

    private fun store(platform: Platform) = DefaultDownloadedFileStore(locations(), platform, fileSystem)

    @Test
    fun platformsThatDoNotTrackFilesAlwaysReportPresent() {
        assertTrue(store(Platform.ANDROID).isFilePresent(app("missing.apk")))
        assertTrue(store(Platform.MACOS).isFilePresent(app(null)))
    }

    @Test
    fun iosReportsPresentOnlyWhenTheFileExists() {
        fileSystem.write(dir / "App.ipa") { writeUtf8("x") }
        assertTrue(store(Platform.IOS).isFilePresent(app("App.ipa")))
        assertFalse(store(Platform.IOS).isFilePresent(app("Other.ipa")))
    }

    @Test
    fun iosTreatsAMissingAssetNameAsDeleted() {
        assertFalse(store(Platform.IOS).isFilePresent(app(null)))
        assertFalse(store(Platform.IOS).isFilePresent(app("  ")))
    }

    @Test
    fun iosNeverLooksOutsideTheDownloadsDirectory() {
        val outside = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-outside-${Random.nextLong().toString(16)}.ipa"
        fileSystem.write(outside) { writeUtf8("x") }
        try {
            assertFalse(store(Platform.IOS).isFilePresent(app("../${outside.name}")))
        } finally {
            fileSystem.delete(outside)
        }
    }

    @Test
    fun iosDetectsAFileDeletedAfterwards() {
        val file = dir / "App.ipa"
        fileSystem.write(file) { writeUtf8("x") }
        val store = store(Platform.IOS)
        assertTrue(store.isFilePresent(app("App.ipa")))
        fileSystem.delete(file)
        assertFalse(store.isFilePresent(app("App.ipa")))
    }
}
```

- [ ] **Step 2: Run and watch it fail**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*DefaultDownloadedFileStoreTest" 2>&1 | tail -10
```

Expected: compilation FAILS (`Unresolved reference: DefaultDownloadedFileStore`).

- [ ] **Step 3: Implement**

`DownloadedFileStore.kt`:

```kotlin
package zed.rainxch.core.domain.system

import zed.rainxch.core.domain.model.installation.InstalledApp

interface DownloadedFileStore {
    fun isFilePresent(app: InstalledApp): Boolean
}
```

In `ShareManager.kt`, add after `pickFile`:

```kotlin
    fun shareExistingFile(filePath: String): Boolean = false
```

`DefaultDownloadedFileStore.kt`:

```kotlin
package zed.rainxch.core.data.services

import okio.FileSystem
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.domain.model.installation.InstalledApp
import zed.rainxch.core.domain.model.system.Platform
import zed.rainxch.core.domain.system.DownloadedFileStore

class DefaultDownloadedFileStore(
    private val files: FileLocationsProvider,
    private val platform: Platform,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DownloadedFileStore {
    override fun isFilePresent(app: InstalledApp): Boolean {
        if (!platform.tracksDownloadedFiles) return true
        val path = downloadedFilePath(files.userDownloadsDir(), app.installedAssetName) ?: return false
        return fileSystem.exists(path)
    }
}
```

In `SharedModule.kt`, add these two imports after `import zed.rainxch.core.domain.system.LocalFileSystem`:

```kotlin
import zed.rainxch.core.data.services.DefaultDownloadedFileStore
import zed.rainxch.core.domain.system.DownloadedFileStore
```

and this binding after the `single<LocalFileSystem>` block:

```kotlin
        single<DownloadedFileStore> {
            DefaultDownloadedFileStore(
                files = get(),
                platform = get(),
            )
        }
```

- [ ] **Step 4: Run the tests and regression compiles**

```bash
./gradlew --console=plain :core:data:jvmTest 2>&1 | tail -10
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```

Expected: tests pass and the compile prints only `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add core
git commit -m "feat(data): detect downloaded files that were deleted outside the app" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---
### Task 9: iOS actuals for `core/domain` and `core/presentation`

**Files:**
- Create: `core/domain/src/iosMain/kotlin/zed/rainxch/core/domain/Platform.ios.kt`
- Create: `core/domain/src/iosMain/kotlin/zed/rainxch/core/domain/utils/Crypto.ios.kt`
- Create: `core/presentation/src/iosMain/kotlin/zed/rainxch/core/presentation/components/InstalledAppIcon.ios.kt`
- Create: `core/presentation/src/iosMain/kotlin/zed/rainxch/core/presentation/components/ScrollbarContainer.ios.kt`
- Create: `core/presentation/src/iosMain/kotlin/zed/rainxch/core/presentation/utils/TimeZoneChangeSignal.ios.kt`
- Create: `core/presentation/src/iosMain/kotlin/zed/rainxch/core/presentation/utils/isPullToRefreshSupported.ios.kt`

**Interfaces:**
- Consumes: the `expect` declarations `getPlatform()`, `getOsVersion()`, `getSystemLocaleTag()`, `sha256`, `secureRandomBytes`, `InstalledAppIcon`, the three `ScrollbarContainer` overloads, `ObserveTimeZoneChanges`, `isPullToRefreshSupported`.
- Produces: iOS `actual`s. `getOsVersion()` returns `"iOS <systemVersion>"`. `getSystemLocaleTag()` returns the first entry of `NSLocale.preferredLanguages` (already BCP 47, for example `en-US` or `zh-Hans-CN`) or `"und"`.

- [ ] **Step 1: Write the `core/domain` actuals**

`Platform.ios.kt`:

```kotlin
package zed.rainxch.core.domain

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import platform.UIKit.UIDevice
import zed.rainxch.core.domain.model.system.Platform

actual fun getPlatform(): Platform = Platform.IOS

actual fun getOsVersion(): String = "iOS ${UIDevice.currentDevice.systemVersion}"

actual fun getSystemLocaleTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf { it.isNotBlank() } ?: "und"
```

`Crypto.ios.kt`:

```kotlin
package zed.rainxch.core.domain.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual fun sha256(input: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                if (input.isEmpty()) null else inputPinned.addressOf(0),
                input.size.convert(),
                digestPinned.addressOf(0).reinterpret(),
            )
        }
    }
    return digest
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
    }
    return bytes
}
```

- [ ] **Step 2: Write the `core/presentation` actuals**

`InstalledAppIcon.ios.kt`:

```kotlin
package zed.rainxch.core.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.app_icon

@Composable
actual fun InstalledAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier,
    apkFilePath: String?,
) {
    Image(
        painter = painterResource(Res.drawable.app_icon),
        contentDescription = appName,
        modifier = modifier,
    )
}
```

`ScrollbarContainer.ios.kt`:

```kotlin
package zed.rainxch.core.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun ScrollbarContainer(
    listState: LazyListState,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
    }
}

@Composable
actual fun ScrollbarContainer(
    gridState: LazyStaggeredGridState,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
    }
}

@Composable
actual fun ScrollbarContainer(
    gridState: LazyGridState,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
    }
}
```

`TimeZoneChangeSignal.ios.kt`:

```kotlin
package zed.rainxch.core.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSystemTimeZoneDidChangeNotification

@Composable
actual fun ObserveTimeZoneChanges() {
    DisposableEffect(Unit) {
        val observer =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = NSSystemTimeZoneDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                TimeZoneChangeSignal.onTimeZoneChanged()
            }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
}
```

`isPullToRefreshSupported.ios.kt`:

```kotlin
package zed.rainxch.core.presentation.utils

actual fun isPullToRefreshSupported(): Boolean = true
```

- [ ] **Step 3: Compile both modules for iOS**

```bash
./gradlew --console=plain :core:domain:compileKotlinIosSimulatorArm64 :core:presentation:compileKotlinIosSimulatorArm64 2>&1 | grep -E "^e: |^w: .*deprecat|FAILED|BUILD" | head -40
```

Expected: `BUILD SUCCESSFUL`. If a cinterop symbol name is wrong (for example `NSSystemTimeZoneDidChangeNotification` or `kSecRandomDefault`), correct it to the symbol the compiler suggests. Fix only the iOS file. If `core:domain` still reports an `expect` without an `actual` or a JVM-only API inside `commonMain` (for example `String.format` or `java.*`), replace it using the helpers from Tasks 3 to 5, then rerun.

- [ ] **Step 4: Run the domain tests on the iOS simulator target**

This proves the pure-Kotlin helpers behave the same on Kotlin/Native, including `sha256` through CommonCrypto and `secureRandomBytes` through `SecRandomCopyBytes`.

```bash
./gradlew --console=plain :core:domain:iosSimulatorArm64Test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. This boots a simulator; the first run can take a few minutes. If Gradle cannot start a simulator because of the iOS 17.x runtimes versus the 27.0 SDK (the risk named in the spec), STOP and tell the user instead of downloading a runtime.

- [ ] **Step 5: Commit**

```bash
git add core
git commit -m "feat(ios): add iOS actuals for core domain and presentation" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: iOS data primitives in `core/data` (HTTP, storage, paths, localization)

**Files:**
- Modify: `core/data/build.gradle.kts`
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/local/db/AppDatabaseConstructor.kt`
- Modify: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/local/db/AppDatabase.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/network/HttpClientFactory.ios.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/local/IosAppDataPaths.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/local/data_store/createDataStore.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/local/data_store/createAnnouncementsDataStore.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/local/db/initDatabase.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/services/IosFileLocationsProvider.kt`
- Create: `core/data/src/iosMain/kotlin/zed/rainxch/core/data/services/IosLocalizationManager.kt`

**Interfaces:**
- Consumes: `createPlatformHttpClient(proxyConfig: ProxyConfig): HttpClient` (`expect`), `createDataStore(producePath: () -> String)`, `dataStoreFileName`, `announcementsDataStoreFileName`, `FileLocationsProvider`, `LocalizationManager`, `MIGRATION_19_20`.
- Produces: `object IosAppDataPaths { fun documentsDir(): String; fun cachesDir(): String; fun appDataDir(): String }` (`appDataDir()` is `Library/Application Support/KomiStore`, created on demand), `fun createDataStore(): DataStore<Preferences>`, `fun createAnnouncementsDataStore(): DataStore<Preferences>`, `fun initDatabase(): AppDatabase`, `class IosFileLocationsProvider : FileLocationsProvider`, `class IosLocalizationManager : LocalizationManager`.

- [ ] **Step 1: Give Room a constructor for non-JVM targets**

Room cannot create the database through reflection on iOS. Create `core/data/src/commonMain/kotlin/zed/rainxch/core/data/local/db/AppDatabaseConstructor.kt`:

```kotlin
package zed.rainxch.core.data.local.db

import androidx.room.RoomDatabaseConstructor

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
```

In `AppDatabase.kt`, add `import androidx.room.ConstructedBy` and put `@ConstructedBy(AppDatabaseConstructor::class)` directly above `abstract class AppDatabase : RoomDatabase() {`, below the `@Database(...)` annotation. Room's compiler generates the `actual` for every target.

Verify this does not change the Android or Desktop databases:

```bash
./gradlew --console=plain :core:data:kspKotlinJvm :core:data:kspDebugKotlinAndroid :core:data:kspKotlinIosSimulatorArm64 2>&1 | tail -15
git status --short core/data/schemas
```

Expected: `BUILD SUCCESSFUL` and no changes under `core/data/schemas` (the schema is unchanged, only a constructor was added).

- [ ] **Step 2: Add the Darwin engine**

In `core/data/build.gradle.kts`, inside `sourceSets { ... }` after `jvmMain { ... }` add:

```kotlin
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
```

- [ ] **Step 3: HTTP client**

`core/data/src/iosMain/kotlin/zed/rainxch/core/data/network/HttpClientFactory.ios.kt`:

```kotlin
package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.darwin.Darwin
import zed.rainxch.core.domain.model.settings.ProxyConfig

actual fun createPlatformHttpClient(proxyConfig: ProxyConfig): HttpClient =
    HttpClient(Darwin) {
        engine {
            proxy =
                when (proxyConfig) {
                    is ProxyConfig.None, is ProxyConfig.System -> null
                    is ProxyConfig.Http -> ProxyBuilder.http("http://${proxyConfig.host}:${proxyConfig.port}")
                    is ProxyConfig.Socks -> ProxyBuilder.socks(proxyConfig.host, proxyConfig.port)
                }
        }
    }
```

Known limits, to be reported at the end: the Darwin engine ignores proxy credentials, an HTTP proxy does not carry HTTPS requests, and `ProxyConfig.None` behaves like the system proxy because `NSURLSession` cannot bypass it.

- [ ] **Step 4: Paths and storage**

`IosAppDataPaths.kt`:

```kotlin
package zed.rainxch.core.data.local

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

object IosAppDataPaths {
    fun documentsDir(): String = searchPath(NSDocumentDirectory)

    fun cachesDir(): String = searchPath(NSCachesDirectory)

    fun appDataDir(): String {
        val dir = "${searchPath(NSApplicationSupportDirectory)}/KomiStore"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        return dir
    }

    private fun searchPath(directory: NSSearchPathDirectory): String =
        NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String
            ?: error("No directory available for $directory")
}
```

`createDataStore.kt`:

```kotlin
package zed.rainxch.core.data.local.data_store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import zed.rainxch.core.data.local.IosAppDataPaths

fun createDataStore(): DataStore<Preferences> =
    createDataStore(
        producePath = { "${IosAppDataPaths.appDataDir()}/$dataStoreFileName" },
    )
```

`createAnnouncementsDataStore.kt`:

```kotlin
package zed.rainxch.core.data.local.data_store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import zed.rainxch.core.data.local.IosAppDataPaths

fun createAnnouncementsDataStore(): DataStore<Preferences> =
    createDataStore(
        producePath = { "${IosAppDataPaths.appDataDir()}/$announcementsDataStoreFileName" },
    )
```

`initDatabase.kt`:

```kotlin
package zed.rainxch.core.data.local.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import zed.rainxch.core.data.local.IosAppDataPaths
import zed.rainxch.core.data.local.db.migrations.MIGRATION_19_20

fun initDatabase(): AppDatabase =
    Room
        .databaseBuilder<AppDatabase>(
            name = "${IosAppDataPaths.appDataDir()}/github_store.db",
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(true)
        .addMigrations(MIGRATION_19_20)
        .build()
```

- [ ] **Step 5: File locations and localization**

`IosFileLocationsProvider.kt`:

```kotlin
package zed.rainxch.core.data.services

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import zed.rainxch.core.data.local.IosAppDataPaths

class IosFileLocationsProvider(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileLocationsProvider {
    override fun appDownloadsDir(): String = ensure("${IosAppDataPaths.cachesDir()}/KomiStore/Downloads")

    override fun userDownloadsDir(): String = ensure("${IosAppDataPaths.documentsDir()}/Komi Store Downloads")

    override fun setExecutableIfNeeded(path: String) = Unit

    override fun getCacheSizeBytes(): Long = sizeOf(appDownloadsDir().toPath()) + sizeOf(userDownloadsDir().toPath())

    override fun clearCacheFiles(): Boolean = clear(appDownloadsDir().toPath()) && clear(userDownloadsDir().toPath())

    private fun ensure(path: String): String {
        fileSystem.createDirectories(path.toPath())
        return path
    }

    private fun sizeOf(directory: Path): Long =
        fileSystem
            .listRecursively(directory)
            .sumOf { fileSystem.metadataOrNull(it)?.takeIf { metadata -> metadata.isRegularFile }?.size ?: 0L }

    private fun clear(directory: Path): Boolean {
        var allDeleted = true
        for (child in fileSystem.list(directory)) {
            try {
                fileSystem.deleteRecursively(child)
            } catch (_: IOException) {
                allDeleted = false
            }
        }
        return allDeleted
    }
}
```

`IosLocalizationManager.kt`:

```kotlin
package zed.rainxch.core.data.services

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

class IosLocalizationManager : LocalizationManager {
    private var activeTag: String? = null

    override fun getCurrentLanguageCode(): String = activeTag ?: systemTag()

    override fun getPrimaryLanguageCode(): String = getCurrentLanguageCode().substringBefore('-')

    override fun setActiveLanguageTag(tag: String?) {
        activeTag = tag?.trim()?.takeIf { it.isNotEmpty() }
        val defaults = NSUserDefaults.standardUserDefaults
        val selected = activeTag
        if (selected != null) {
            defaults.setObject(listOf(selected), forKey = "AppleLanguages")
        } else {
            defaults.removeObjectForKey("AppleLanguages")
        }
    }

    private fun systemTag(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
}
```

The stored `AppleLanguages` value applies on the next launch; an iOS app cannot relaunch itself, so this matches the decision recorded at the top of this plan.

- [ ] **Step 6: Compile**

```bash
./gradlew --console=plain :core:data:compileKotlinIosSimulatorArm64 2>&1 | grep -E "^e: |FAILED|BUILD" | head -40
```

Expected at this point: errors only about missing `corePlatformModule` and about JVM-only code still present in `core/data` `commonMain` that Tasks 4 and 5 did not cover. Fix any remaining JVM-only API using the same pattern as Task 4. `corePlatformModule` is written in Task 11, so ignore that single error for now.

- [ ] **Step 7: Confirm Android and Desktop are unaffected**

```bash
./gradlew --console=plain :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core/data
git commit -m "feat(ios): add iOS http client, storage, paths and localization" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 11: iOS system services and the Koin platform module

**Files:**
- Create: `core/data/src/commonMain/kotlin/zed/rainxch/core/data/packages/IosAssetPolicy.kt`
- Create (iosMain, all under `core/data/src/iosMain/kotlin/zed/rainxch/core/data/`): `services/IosDownloader.kt`, `services/IosInstaller.kt`, `services/IosInstallerInfoExtractor.kt`, `services/IosInstallerStatusProvider.kt`, `services/IosAggressiveOemDetector.kt`, `services/IosPackageMonitor.kt`, `services/IosApkInspector.kt`, `services/external/IosExternalAppScanner.kt`, `services/IosUpdateScheduleManager.kt`, `services/IosPendingInstallNotifier.kt`, `services/IosDownloadProgressNotifier.kt`, `utils/IosBrowserHelper.kt`, `utils/IosClipboardHelper.kt`, `utils/IosShareManager.kt`, `utils/IosAppLauncher.kt`, `di/PlatformModule.ios.kt`
- Test: `core/data/src/jvmTest/kotlin/zed/rainxch/core/data/packages/IosAssetPolicyTest.kt`

**Interfaces:**
- Consumes: `PackageArchiveReader` (Task 6), `StreamingFileDownloader`, `DownloadFileName`, `downloadedFilePath` (Task 7), `OkioDigestVerifier` (Task 4), `DownloadedFileStore`, `ShareManager.shareExistingFile` (Task 8), `InstalledAppDao.getAppByPackage` and `deleteByPackageName`, `AssetSelector.choose`, `assetPlatformOf`.
- Produces: `object IosAssetPolicy { val extensionPriority: List<String>; fun isDownloadable(assetName: String): Boolean; fun choosePrimary(assets: List<GithubAsset>): GithubAsset? }` (`.ipa` first, `.apk` second; a Linux-style `.apk` such as `tool-linux-amd64.apk` is not downloadable), and `actual val corePlatformModule` for iOS binding every interface the Android and Desktop modules bind.

- [ ] **Step 1: Write the failing test for the asset policy**

`IosAssetPolicyTest.kt`:

```kotlin
package zed.rainxch.core.data.packages

import zed.rainxch.core.domain.model.account.github.GithubAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAssetPolicyTest {
    private fun asset(
        name: String,
        size: Long = 1L,
    ) = GithubAsset(
        id = name.hashCode().toLong(),
        name = name,
        contentType = "application/octet-stream",
        size = size,
        downloadUrl = "https://dl/$name",
    )

    @Test
    fun acceptsIpaAndAndroidApkCaseInsensitively() {
        assertTrue(IosAssetPolicy.isDownloadable("App.ipa"))
        assertTrue(IosAssetPolicy.isDownloadable("APP.IPA"))
        assertTrue(IosAssetPolicy.isDownloadable("app-arm64-v8a.apk"))
        assertTrue(IosAssetPolicy.isDownloadable("app-release.apk"))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf("App.dmg", "setup.exe", "app.zip", "notes.txt", "App.ipa.sig", "tool-linux-amd64.apk").forEach {
            assertFalse(IosAssetPolicy.isDownloadable(it), it)
        }
    }

    @Test
    fun prefersIpaOverApkEvenWhenTheApkIsLarger() {
        val chosen = IosAssetPolicy.choosePrimary(listOf(asset("App.apk", size = 100), asset("App.ipa", size = 10)))
        assertEquals("App.ipa", chosen?.name)
    }

    @Test
    fun fallsBackToApkWhenThereIsNoIpa() {
        val chosen = IosAssetPolicy.choosePrimary(listOf(asset("app-x86_64.apk"), asset("app-arm64-v8a.apk")))
        assertEquals("app-arm64-v8a.apk", chosen?.name)
    }

    @Test
    fun returnsNullForAnEmptyList() {
        assertNull(IosAssetPolicy.choosePrimary(emptyList()))
    }
}
```

- [ ] **Step 2: Run and watch it fail**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*IosAssetPolicyTest" 2>&1 | tail -10
```

Expected: compilation FAILS (`Unresolved reference: IosAssetPolicy`).

- [ ] **Step 3: Implement the policy**

`IosAssetPolicy.kt`:

```kotlin
package zed.rainxch.core.data.packages

import zed.rainxch.core.domain.model.account.github.GithubAsset
import zed.rainxch.core.domain.model.repository.DiscoveryPlatform
import zed.rainxch.core.domain.model.system.SystemArchitecture
import zed.rainxch.core.domain.utils.AssetSelector
import zed.rainxch.core.domain.utils.assetPlatformOf

object IosAssetPolicy {
    val extensionPriority: List<String> = listOf(".ipa", ".apk")

    fun isDownloadable(assetName: String): Boolean =
        when (assetPlatformOf(assetName)) {
            DiscoveryPlatform.Ios, DiscoveryPlatform.Android -> true
            else -> false
        }

    fun choosePrimary(assets: List<GithubAsset>): GithubAsset? =
        AssetSelector.choose(
            assets = assets,
            deviceArch = SystemArchitecture.AARCH64,
            extensionPriority = extensionPriority,
        )
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew --console=plain :core:data:jvmTest --tests "*IosAssetPolicyTest" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 5: Write the iOS services**

`services/IosDownloader.kt`:

```kotlin
package zed.rainxch.core.data.services

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import zed.rainxch.core.data.data_source.TokenStore
import zed.rainxch.core.data.download.DownloadFileName
import zed.rainxch.core.data.download.StreamingFileDownloader
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.data.network.createPlatformHttpClient
import zed.rainxch.core.domain.model.installation.DownloadProgress
import zed.rainxch.core.domain.model.settings.ProxyScope
import zed.rainxch.core.domain.network.Downloader
import kotlin.coroutines.cancellation.CancellationException

class IosDownloader(
    private val files: FileLocationsProvider,
    private val tokenStore: TokenStore,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Downloader {
    private val streaming =
        StreamingFileDownloader(
            clientFactory = {
                createPlatformHttpClient(ProxyManager.currentConfig(ProxyScope.DOWNLOAD)).config {
                    followRedirects = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }
            },
            tokenProvider = { githubToken() },
            fileSystem = fileSystem,
        )

    override fun download(
        url: String,
        suggestedFileName: String?,
        bypassMirror: Boolean,
    ): Flow<DownloadProgress> =
        streaming
            .download(url, files.userDownloadsDir(), suggestedFileName)
            .flowOn(Dispatchers.IO)

    override suspend fun saveToFile(
        url: String,
        suggestedFileName: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val name = DownloadFileName.sanitize(suggestedFileName, url)
            streaming.download(url, files.userDownloadsDir(), name).collect { }
            (files.userDownloadsDir().toPath() / name).toString()
        }

    override suspend fun getDownloadedFilePath(fileName: String): String? =
        withContext(Dispatchers.IO) {
            val path = downloadedFilePath(files.userDownloadsDir(), fileName) ?: return@withContext null
            val size = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size ?: 0L
            if (size > 0) path.toString() else null
        }

    override suspend fun cancelDownload(fileName: String): Boolean = streaming.cancel(fileName)

    private suspend fun githubToken(): String? =
        try {
            tokenStore.currentToken()?.accessToken?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
```

`services/IosInstaller.kt`:

```kotlin
package zed.rainxch.core.data.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.local.db.dao.InstalledAppDao
import zed.rainxch.core.data.packages.IosAssetPolicy
import zed.rainxch.core.domain.logging.KomiStoreLogger
import zed.rainxch.core.domain.model.account.github.GithubAsset
import zed.rainxch.core.domain.model.system.SystemArchitecture
import zed.rainxch.core.domain.system.InstallOutcome
import zed.rainxch.core.domain.system.Installer
import zed.rainxch.core.domain.system.InstallerInfoExtractor
import kotlin.coroutines.cancellation.CancellationException

class IosInstaller(
    private val installerInfoExtractor: InstallerInfoExtractor,
    private val files: FileLocationsProvider,
    private val installedAppDao: InstalledAppDao,
    private val scope: CoroutineScope,
    private val logger: KomiStoreLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Installer {
    override suspend fun isSupported(extOrMime: String): Boolean = extOrMime.lowercase().removePrefix(".") in SUPPORTED_EXTENSIONS

    override suspend fun ensurePermissionsOrThrow(extOrMime: String) = Unit

    override suspend fun install(
        filePath: String,
        extOrMime: String,
    ): InstallOutcome = InstallOutcome.COMPLETED

    override fun uninstall(packageName: String) {
        scope.launch {
            try {
                val app = installedAppDao.getAppByPackage(packageName) ?: return@launch
                downloadedFilePath(files.userDownloadsDir(), app.installedAssetName)
                    ?.let { fileSystem.delete(it, mustExist = false) }
                installedAppDao.deleteByPackageName(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to delete download for $packageName: ${e.message}", e)
            }
        }
    }

    override fun isAssetInstallable(assetName: String): Boolean = IosAssetPolicy.isDownloadable(assetName)

    override fun choosePrimaryAsset(assets: List<GithubAsset>): GithubAsset? = IosAssetPolicy.choosePrimary(assets)

    override fun detectSystemArchitecture(): SystemArchitecture = SystemArchitecture.AARCH64

    override fun isObtainiumInstalled(): Boolean = false

    override fun openInObtainium(
        repoOwner: String,
        repoName: String,
        onOpenInstaller: () -> Unit,
    ) = Unit

    override fun isAppManagerInstalled(): Boolean = false

    override fun openInAppManager(
        filePath: String,
        onOpenInstaller: () -> Unit,
    ) = Unit

    override fun getApkInfoExtractor(): InstallerInfoExtractor = installerInfoExtractor

    override fun openApp(packageName: String): Boolean = false

    override fun openWithExternalInstaller(filePath: String) = Unit

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("ipa", "apk")
    }
}
```

`services/IosInstallerInfoExtractor.kt`:

```kotlin
package zed.rainxch.core.data.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zed.rainxch.core.data.packages.PackageArchiveReader
import zed.rainxch.core.domain.model.apk.ApkPackageInfo
import zed.rainxch.core.domain.system.InstallerInfoExtractor

class IosInstallerInfoExtractor(
    private val reader: PackageArchiveReader = PackageArchiveReader(),
) : InstallerInfoExtractor {
    override suspend fun extractPackageInfo(filePath: String): ApkPackageInfo? =
        withContext(Dispatchers.IO) { reader.read(filePath) }
}
```

`services/IosInstallerStatusProvider.kt`:

```kotlin
package zed.rainxch.core.data.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zed.rainxch.core.domain.model.installation.DhizukuAvailability
import zed.rainxch.core.domain.model.installation.RootAvailability
import zed.rainxch.core.domain.model.installation.ShizukuAvailability
import zed.rainxch.core.domain.system.InstallerStatusProvider

class IosInstallerStatusProvider : InstallerStatusProvider {
    override val shizukuAvailability: StateFlow<ShizukuAvailability> =
        MutableStateFlow(ShizukuAvailability.UNAVAILABLE).asStateFlow()

    override val dhizukuAvailability: StateFlow<DhizukuAvailability> =
        MutableStateFlow(DhizukuAvailability.UNAVAILABLE).asStateFlow()

    override val rootAvailability: StateFlow<RootAvailability> =
        MutableStateFlow(RootAvailability.UNAVAILABLE).asStateFlow()

    override fun requestShizukuPermission() = Unit

    override fun requestDhizukuPermission() = Unit

    override fun requestRootPermission() = Unit
}
```

`services/IosAggressiveOemDetector.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.AggressiveOemDetector

class IosAggressiveOemDetector : AggressiveOemDetector {
    override fun isAggressiveOem(): Boolean = false

    override fun isBatteryOptimizationIgnored(): Boolean = true

    override fun openBatteryOptimizationSettings(): Boolean = false
}
```

`services/IosPackageMonitor.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.model.installation.DeviceApp
import zed.rainxch.core.domain.model.installation.SystemPackageInfo
import zed.rainxch.core.domain.system.PackageMonitor

class IosPackageMonitor : PackageMonitor {
    override suspend fun isPackageInstalled(packageName: String): Boolean = false

    override suspend fun getInstalledPackageInfo(packageName: String): SystemPackageInfo? = null

    override suspend fun getAllInstalledPackageNames(): Set<String> = emptySet()

    override suspend fun getAllInstalledApps(): List<DeviceApp> = emptyList()

    override fun canEnumerateInstalledPackages(): Boolean = false
}
```

`services/IosApkInspector.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.model.apk.ApkInspection
import zed.rainxch.core.domain.system.ApkInspector

class IosApkInspector : ApkInspector {
    override suspend fun inspectFile(filePath: String): ApkInspection? = null

    override suspend fun inspectInstalled(packageName: String): ApkInspection? = null
}
```

`services/external/IosExternalAppScanner.kt`:

```kotlin
package zed.rainxch.core.data.services.external

import zed.rainxch.core.domain.system.ExternalAppCandidate
import zed.rainxch.core.domain.system.ExternalAppScanner
import zed.rainxch.core.domain.system.VisiblePackageEstimate

class IosExternalAppScanner : ExternalAppScanner {
    override suspend fun isPermissionGranted(): Boolean = true

    override suspend fun visiblePackageCountEstimate(): VisiblePackageEstimate =
        VisiblePackageEstimate(
            visibleCount = 0,
            invisibleEstimate = 0,
            permissionGranted = true,
        )

    override suspend fun snapshot(): List<ExternalAppCandidate> = emptyList()

    override suspend fun snapshotSingle(packageName: String): ExternalAppCandidate? = null
}
```

`services/IosUpdateScheduleManager.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.UpdateScheduleManager

class IosUpdateScheduleManager : UpdateScheduleManager {
    override fun reschedule(intervalHours: Long) = Unit

    override fun cancel() = Unit
}
```

`services/IosPendingInstallNotifier.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.PendingInstallNotifier

class IosPendingInstallNotifier : PendingInstallNotifier {
    override fun notifyPending(
        packageName: String,
        repoOwner: String,
        repoName: String,
        appName: String,
        versionTag: String,
    ) = Unit

    override fun clearPending(packageName: String) = Unit
}
```

`services/IosDownloadProgressNotifier.kt`:

```kotlin
package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.DownloadProgressNotifier

class IosDownloadProgressNotifier : DownloadProgressNotifier {
    override fun notifyProgress(
        packageName: String,
        appName: String,
        versionTag: String,
        percent: Int?,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ) = Unit

    override fun clearProgress(packageName: String) = Unit
}
```

- [ ] **Step 6: Write the iOS helpers**

`utils/IosBrowserHelper.kt`:

```kotlin
package zed.rainxch.core.data.utils

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import zed.rainxch.core.domain.helpers.BrowserHelper

class IosBrowserHelper : BrowserHelper {
    override fun openUrl(
        url: String,
        onFailure: (error: String) -> Unit,
    ) {
        val target = NSURL.URLWithString(url)
        if (target == null) {
            onFailure("Invalid URL: $url")
            return
        }
        UIApplication.sharedApplication.openURL(
            target,
            options = emptyMap<Any?, Any?>(),
            completionHandler = { opened ->
                if (!opened) onFailure("Could not open $url")
            },
        )
    }
}
```

`utils/IosClipboardHelper.kt`:

```kotlin
package zed.rainxch.core.data.utils

import platform.UIKit.UIPasteboard
import zed.rainxch.core.domain.helpers.ClipboardHelper

class IosClipboardHelper : ClipboardHelper {
    override fun copy(
        label: String,
        text: String,
    ) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun getText(): String? = UIPasteboard.generalPasteboard.string
}
```

`utils/IosShareManager.kt`:

```kotlin
package zed.rainxch.core.data.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import zed.rainxch.core.data.download.DownloadFileName
import zed.rainxch.core.domain.helpers.ShareManager

@OptIn(ExperimentalForeignApi::class)
class IosShareManager(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ShareManager {
    override fun shareText(text: String) {
        present(listOf(text))
    }

    override fun shareFile(
        fileName: String,
        content: String,
        mimeType: String,
    ) {
        val path = NSTemporaryDirectory().toPath() / DownloadFileName.sanitize(fileName, "")
        fileSystem.write(path) { writeUtf8(content) }
        present(listOf(NSURL.fileURLWithPath(path.toString())))
    }

    override fun pickFile(
        mimeType: String,
        onResult: (String?) -> Unit,
    ) {
        onResult(null)
    }

    override fun shareExistingFile(filePath: String): Boolean {
        if (!fileSystem.exists(filePath.toPath())) return false
        return present(listOf(NSURL.fileURLWithPath(filePath)))
    }

    private fun present(items: List<Any>): Boolean {
        val top = topViewController() ?: return false
        val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            popover.sourceRect =
                top.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        }
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

    private fun topViewController(): UIViewController? {
        val scene =
            UIApplication.sharedApplication.connectedScenes
                .firstOrNull { it is UIWindowScene && it.activationState == UISceneActivationStateForegroundActive }
                as? UIWindowScene
        val window = scene?.keyWindow ?: (scene?.windows?.firstOrNull() as? UIWindow)
        var top = window?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }
}
```

`utils/IosAppLauncher.kt`:

```kotlin
package zed.rainxch.core.data.utils

import okio.FileSystem
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.services.FileLocationsProvider
import zed.rainxch.core.domain.helpers.AppLauncher
import zed.rainxch.core.domain.helpers.ShareManager
import zed.rainxch.core.domain.logging.KomiStoreLogger
import zed.rainxch.core.domain.model.installation.InstalledApp

class IosAppLauncher(
    private val files: FileLocationsProvider,
    private val shareManager: ShareManager,
    private val logger: KomiStoreLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : AppLauncher {
    override suspend fun launchApp(installedApp: InstalledApp): Result<Unit> {
        val path = downloadedFilePath(files.userDownloadsDir(), installedApp.installedAssetName)
        if (path == null || !fileSystem.exists(path)) {
            return Result.failure(IllegalStateException("Downloaded file is missing for ${installedApp.appName}"))
        }
        return if (shareManager.shareExistingFile(path.toString())) {
            Result.success(Unit)
        } else {
            logger.warn("Could not present the share sheet for ${installedApp.appName}")
            Result.failure(IllegalStateException("Could not share ${installedApp.appName}"))
        }
    }

    override suspend fun canLaunchApp(installedApp: InstalledApp): Boolean {
        val path = downloadedFilePath(files.userDownloadsDir(), installedApp.installedAssetName) ?: return false
        return fileSystem.exists(path)
    }
}
```

- [ ] **Step 7: Write the Koin platform module**

`di/PlatformModule.ios.kt`:

```kotlin
package zed.rainxch.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module
import zed.rainxch.core.data.local.data_store.createAnnouncementsDataStore
import zed.rainxch.core.data.local.data_store.createDataStore
import zed.rainxch.core.data.local.db.AppDatabase
import zed.rainxch.core.data.local.db.initDatabase
import zed.rainxch.core.data.network.OkioDigestVerifier
import zed.rainxch.core.data.services.FileLocationsProvider
import zed.rainxch.core.data.services.IosAggressiveOemDetector
import zed.rainxch.core.data.services.IosApkInspector
import zed.rainxch.core.data.services.IosDownloadProgressNotifier
import zed.rainxch.core.data.services.IosDownloader
import zed.rainxch.core.data.services.IosFileLocationsProvider
import zed.rainxch.core.data.services.IosInstaller
import zed.rainxch.core.data.services.IosInstallerInfoExtractor
import zed.rainxch.core.data.services.IosInstallerStatusProvider
import zed.rainxch.core.data.services.IosLocalizationManager
import zed.rainxch.core.data.services.IosPackageMonitor
import zed.rainxch.core.data.services.IosPendingInstallNotifier
import zed.rainxch.core.data.services.IosUpdateScheduleManager
import zed.rainxch.core.data.services.LocalizationManager
import zed.rainxch.core.data.services.external.IosExternalAppScanner
import zed.rainxch.core.data.utils.IosAppLauncher
import zed.rainxch.core.data.utils.IosBrowserHelper
import zed.rainxch.core.data.utils.IosClipboardHelper
import zed.rainxch.core.data.utils.IosShareManager
import zed.rainxch.core.domain.helpers.AppLauncher
import zed.rainxch.core.domain.helpers.BrowserHelper
import zed.rainxch.core.domain.helpers.ClipboardHelper
import zed.rainxch.core.domain.helpers.ShareManager
import zed.rainxch.core.domain.network.DigestVerifier
import zed.rainxch.core.domain.network.Downloader
import zed.rainxch.core.domain.system.AggressiveOemDetector
import zed.rainxch.core.domain.system.ApkInspector
import zed.rainxch.core.domain.system.DownloadProgressNotifier
import zed.rainxch.core.domain.system.ExternalAppScanner
import zed.rainxch.core.domain.system.Installer
import zed.rainxch.core.domain.system.InstallerStatusProvider
import zed.rainxch.core.domain.system.PackageMonitor
import zed.rainxch.core.domain.system.PendingInstallNotifier
import zed.rainxch.core.domain.system.UpdateScheduleManager

actual val corePlatformModule =
    module {
        single<Downloader> {
            IosDownloader(
                files = get(),
                tokenStore = get(),
            )
        }

        single<Installer> {
            IosInstaller(
                installerInfoExtractor = IosInstallerInfoExtractor(),
                files = get(),
                installedAppDao = get(),
                scope = get<CoroutineScope>(),
                logger = get(),
            )
        }

        single<FileLocationsProvider> {
            IosFileLocationsProvider()
        }

        single<AggressiveOemDetector> {
            IosAggressiveOemDetector()
        }

        single<PackageMonitor> {
            IosPackageMonitor()
        }

        single<ApkInspector> {
            IosApkInspector()
        }

        single<ExternalAppScanner> {
            IosExternalAppScanner()
        }

        single<LocalizationManager> {
            IosLocalizationManager()
        }

        single<AppDatabase> {
            initDatabase()
        }

        single<DataStore<Preferences>> {
            createDataStore()
        }

        single<DataStore<Preferences>>(qualifier = named("announcements")) {
            createAnnouncementsDataStore()
        }

        single<KSafe>(qualifier = named("tokens")) {
            KSafe(fileName = "ghs_tokens")
        }

        single<KSafe>(qualifier = named("prefs")) {
            KSafe(fileName = "ghs_prefs")
        }

        single<KSafe>(qualifier = named("announcements_cache")) {
            KSafe(fileName = "ghs_announcements")
        }

        single<BrowserHelper> {
            IosBrowserHelper()
        }

        single<DigestVerifier> {
            OkioDigestVerifier()
        }

        single<ClipboardHelper> {
            IosClipboardHelper()
        }

        single<ShareManager> {
            IosShareManager()
        }

        single<AppLauncher> {
            IosAppLauncher(
                files = get(),
                shareManager = get(),
                logger = get(),
            )
        }

        single<InstallerStatusProvider> {
            IosInstallerStatusProvider()
        }

        single<UpdateScheduleManager> {
            IosUpdateScheduleManager()
        }

        single<PendingInstallNotifier> {
            IosPendingInstallNotifier()
        }

        single<DownloadProgressNotifier> {
            IosDownloadProgressNotifier()
        }
    }
```

- [ ] **Step 8: Compile `core/data` for iOS**

```bash
./gradlew --console=plain :core:data:compileKotlinIosSimulatorArm64 2>&1 | grep -E "^e: |FAILED|BUILD" | head -40
```

Expected: `BUILD SUCCESSFUL`. Likely fixes, all inside iOS files: a cinterop name or signature that differs slightly in the Xcode 27 SDK (adjust to what the compiler reports), a `KSafe` constructor parameter name (check the exact constructor in the library and keep `fileName`), an unresolved `okio.openZip`. If `openZip` does not resolve for iOS, STOP and tell the user, as agreed in the spec. If a Ktor import path in `StreamingFileDownloader` differs on the native source set, fix the import.

- [ ] **Step 9: Run the data tests and the JVM regression**

```bash
./gradlew --console=plain :core:data:jvmTest 2>&1 | tail -8
./gradlew --console=plain :core:data:compileKotlinJvm :core:data:compileDebugKotlinAndroid 2>&1 | tail -4
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Run the package-reader tests on the iOS simulator**

The package-reader tests from Task 6 are `commonTest`, so they also run on Kotlin/Native. This is where okio's zip reading and the three parsers are proven on iOS.

```bash
./gradlew --console=plain :core:data:iosSimulatorArm64Test 2>&1 | tail -25
```

Expected: `BUILD SUCCESSFUL` and the `AndroidManifestParserTest`, `PropertyListParserTest`, and `PackageArchiveReaderTest` cases pass. A Native-only failure here (for example a bounds check that behaves differently, or `openZip` rejecting a fixture) is a real defect in the parser or the reader: fix it in `commonMain`, rerun the JVM tests in Step 9, and rerun this step. If the simulator cannot start because of the iOS 17.x runtimes versus the 27.0 SDK, STOP and tell the user.

- [ ] **Step 11: Commit**

```bash
git add core/data
git commit -m "feat(ios): add iOS services and the Koin platform module" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: iOS actuals for feature modules and the full iOS compile gate

**Files:**
- Create: `feature/apps/presentation/src/iosMain/kotlin/zed/rainxch/apps/presentation/import/util/PackageVisibilityRequester.ios.kt`
- Create: `feature/apps/presentation/src/iosMain/kotlin/zed/rainxch/apps/presentation/import/util/ReducedMotionProvider.ios.kt`
- Create: `feature/apps/presentation/src/iosMain/kotlin/zed/rainxch/apps/presentation/import/util/SdkLevelProvider.ios.kt`
- Create: `feature/tweaks/presentation/src/iosMain/kotlin/zed/rainxch/tweaks/presentation/RestartApp.ios.kt`

**Interfaces:**
- Consumes: `rememberPackageVisibilityRequester()`, `rememberSystemReducedMotion()`, `rememberSdkInt()`, `restartAppAfterLanguageChange()` (`expect`).
- Produces: iOS `actual`s. Phase A is complete when every module compiles for `iosSimulatorArm64` and `iosArm64`, and the JVM and Android targets still build and pass their tests.

- [ ] **Step 1: Write the actuals**

`PackageVisibilityRequester.ios.kt`:

```kotlin
package zed.rainxch.apps.presentation.import.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPackageVisibilityRequester(): PackageVisibilityRequester = remember { IosPackageVisibilityRequester }

private object IosPackageVisibilityRequester : PackageVisibilityRequester {
    override suspend fun isGranted(): Boolean = true

    override suspend fun requestOrOpenSettings(): Boolean = true
}
```

`ReducedMotionProvider.ios.kt`:

```kotlin
package zed.rainxch.apps.presentation.import.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun rememberSystemReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
```

`SdkLevelProvider.ios.kt`:

```kotlin
package zed.rainxch.apps.presentation.import.util

import androidx.compose.runtime.Composable

@Composable
actual fun rememberSdkInt(): Int? = null
```

`RestartApp.ios.kt`:

```kotlin
package zed.rainxch.tweaks.presentation

actual fun restartAppAfterLanguageChange() = Unit
```

- [ ] **Step 2: Compile every module for the iOS simulator**

```bash
./gradlew --console=plain compileKotlinIosSimulatorArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -80
```

Expected on the first run: a list of remaining errors in feature modules and in `composeApp`'s `commonMain`. Fix each one with the same rules used so far, then rerun until the output is only `BUILD SUCCESSFUL`:

- A JVM-only API in `commonMain` (`String.format`, `java.*`, `System.*`, `Thread`, `Locale`): replace it with the helper from Task 3, the okio-based helper from Task 4, or `LocalFileSystem`. Keep the behavior identical.
- A missing `actual`: add it in the module's `iosMain` using the Android implementation as the reference and the iOS decisions at the top of this plan.
- A Compose or lifecycle API that exists only on Android or JVM (for example `BackHandler`, `LocalContext`, `Dialog` properties): look at how the Desktop `jvmMain` handles it and add the iOS equivalent as an `expect/actual` in the owning module. List each such case in the commit message.
- Anything that cannot be fixed without changing Android or Desktop behavior: STOP and report it.

- [ ] **Step 3: Compile the device target too**

```bash
./gradlew --console=plain compileKotlinIosArm64 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Phase A regression gate**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
./gradlew --console=plain jvmTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` for both. If Android compilation cannot run because the SDK platform 36 is unavailable (the environment risk in the spec), run only `compileKotlinJvm` and `jvmTest`, and write in the final report that the Android build could not be verified.

- [ ] **Step 5: Commit**

```bash
git add feature composeApp core
git commit -m "feat(ios): add iOS actuals for feature modules and compile every module for iOS" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---
## Phase B: A runnable iOS app

### Task 13: iOS entry point in `composeApp`

**Files:**
- Modify: `composeApp/build.gradle.kts`
- Create: `composeApp/src/iosMain/kotlin/zed/rainxch/githubstore/MainViewController.kt`
- Create: `composeApp/src/iosMain/kotlin/zed/rainxch/githubstore/DeepLinkBridge.kt`
- Create: `composeApp/src/iosMain/kotlin/zed/rainxch/githubstore/IosAppBootstrap.kt`

**Interfaces:**
- Consumes: `fun App(deepLinkUri: String?, onDeepLinkConsumed: () -> Unit, ...)` and `initKoin()` from `composeApp` `commonMain`, `ProxyManager.bootstrap(repository, appScope)`.
- Produces: a static framework named `ComposeApp` for both iOS targets, a Kotlin function `MainViewController(): UIViewController` (Swift name `MainViewControllerKt.MainViewController()`), and `object DeepLinkBridge { val pending: StateFlow<String?>; fun submit(url: String); fun consume() }` (Swift name `DeepLinkBridge.shared`).

- [ ] **Step 1: Configure the framework**

In `composeApp/build.gradle.kts`, add the import next to the existing one:

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
```

and inside the existing `kotlin { ... }` block, after `compilerOptions { ... }`, add:

```kotlin
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
```

- [ ] **Step 2: Write the Kotlin entry point**

`IosAppBootstrap.kt`:

```kotlin
package zed.rainxch.githubstore

import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.GlobalContext
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.domain.repository.ProxyRepository
import zed.rainxch.githubstore.app.di.initKoin

private val started: Unit by lazy {
    initKoin()
    val koin = GlobalContext.get()
    ProxyManager.bootstrap(
        repository = koin.get<ProxyRepository>(),
        appScope = koin.get<CoroutineScope>(),
    )
}

internal fun ensureAppStarted() = started
```

`DeepLinkBridge.kt`:

```kotlin
package zed.rainxch.githubstore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DeepLinkBridge {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun submit(url: String) {
        _pending.value = url
    }

    fun consume() {
        _pending.value = null
    }
}
```

`MainViewController.kt`:

```kotlin
package zed.rainxch.githubstore

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    ensureAppStarted()
    return ComposeUIViewController {
        val deepLink by DeepLinkBridge.pending.collectAsState()
        App(
            deepLinkUri = deepLink,
            onDeepLinkConsumed = { DeepLinkBridge.consume() },
        )
    }
}
```

- [ ] **Step 3: Link the framework**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=$HOME/Library/Android/sdk
cd /Users/mapan/Repo/komi-store
./gradlew --console=plain :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | grep -E "^e: |FAILED|BUILD|warning: .*duplicate" | head -30
ls composeApp/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework | head
```

Expected: `BUILD SUCCESSFUL` and the framework directory listing contains `ComposeApp` and `Headers` (or `Modules`). A linker error about a missing system library (for example `-lsqlite3`) means the Swift host or the framework needs an extra `linkerOpts`: add `linkerOpts("-lsqlite3")` inside `binaries.framework { ... }` and rerun. A duplicate-symbol error between two libraries: report it to the user with the exact symbols.

- [ ] **Step 4: Confirm the other targets still build**

```bash
./gradlew --console=plain :composeApp:compileKotlinJvm :composeApp:compileDebugKotlinAndroid 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add composeApp
git commit -m "feat(ios): add composeApp iOS entry point and static framework" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: Xcode project and first launch in the simulator

**Files:**
- Create: `iosApp/project.yml`
- Create: `iosApp/iosApp/iOSApp.swift`
- Create: `iosApp/iosApp/ContentView.swift`
- Create: `iosApp/iosApp/Assets.xcassets/Contents.json`
- Create: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` and `icon-1024.png`
- Create (generated, committed): `iosApp/iosApp.xcodeproj`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `MainViewControllerKt.MainViewController()`, `DeepLinkBridge.shared.submit(url:)`.
- Produces: an app target `iosApp` with bundle id `zed.rainxch.githubstore`, display name `Komi Store`, the `githubstore` URL scheme, `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace` set so `Documents/Komi Store Downloads` is visible in the Files app, and a pre-build script that runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`.

- [ ] **Step 1: Write the XcodeGen spec**

`iosApp/project.yml`:

```yaml
name: iosApp
options:
  bundleIdPrefix: zed.rainxch
  deploymentTarget:
    iOS: "15.0"
  createIntermediateGroups: true
settings:
  base:
    SWIFT_VERSION: "5.0"
    MARKETING_VERSION: "1.9.3"
    CURRENT_PROJECT_VERSION: "22"
    ENABLE_USER_SCRIPT_SANDBOXING: NO
targets:
  iosApp:
    type: application
    platform: iOS
    sources:
      - path: iosApp
    info:
      path: Info.plist
      properties:
        CFBundleDisplayName: Komi Store
        CFBundleShortVersionString: $(MARKETING_VERSION)
        CFBundleVersion: $(CURRENT_PROJECT_VERSION)
        UILaunchScreen: {}
        UISupportedInterfaceOrientations:
          - UIInterfaceOrientationPortrait
          - UIInterfaceOrientationLandscapeLeft
          - UIInterfaceOrientationLandscapeRight
        UIFileSharingEnabled: true
        LSSupportsOpeningDocumentsInPlace: true
        CADisableMinimumFrameDurationOnPhone: true
        ITSAppUsesNonExemptEncryption: false
        CFBundleURLTypes:
          - CFBundleURLName: zed.rainxch.githubstore
            CFBundleURLSchemes:
              - githubstore
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: zed.rainxch.githubstore
        ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon
        FRAMEWORK_SEARCH_PATHS: $(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
        OTHER_LDFLAGS: $(inherited) -framework ComposeApp
    preBuildScripts:
      - name: Build Kotlin framework
        basedOnDependencyAnalysis: false
        script: |
          if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
            echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED"
            exit 0
          fi
          cd "$SRCROOT/.."
          export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
          ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

- [ ] **Step 2: Write the Swift host**

`iosApp/iosApp/iOSApp.swift`:

```swift
import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    DeepLinkBridge.shared.submit(url: url.absoluteString)
                }
        }
    }
}
```

`iosApp/iosApp/ContentView.swift`:

```swift
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
```

- [ ] **Step 3: Add the asset catalog and icon**

```bash
mkdir -p iosApp/iosApp/Assets.xcassets/AppIcon.appiconset
cat > iosApp/iosApp/Assets.xcassets/Contents.json <<'EOF'
{
  "info" : { "author" : "xcode", "version" : 1 }
}
EOF
cat > iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json <<'EOF'
{
  "images" : [
    { "filename" : "icon-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
EOF
sips -z 1024 1024 composeApp/src/jvmMain/resources/logo/app_icon.png --out iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png
```

Expected: `sips` prints the output path. If the source icon is missing, use any square PNG the project already ships for the Android launcher (search `find . -name "ic_launcher*.png" -not -path "*/build/*"`), and tell the user which one was used.

- [ ] **Step 4: Ignore Xcode user data**

Append to `.gitignore`:

```
# Xcode
xcuserdata/
DerivedData/
*.xcworkspace/xcuserdata/
```

- [ ] **Step 5: Generate the project and build for the simulator**

```bash
(cd iosApp && xcodegen generate)
SIM=$(xcrun simctl list devices available | awk '/-- iOS 17.4 --/{f=1;next} /-- /{f=0} f && /iPhone 15 Pro \(/{print; exit}' | grep -oE '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}')
echo "simulator: $SIM"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=$SIM" -derivedDataPath /tmp/komi-derived CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -40
```

Expected: `** BUILD SUCCEEDED **`. This runs Gradle through the pre-build script, so the first build takes several minutes. Typical fixes:

- `framework 'ComposeApp' not found`: confirm `composeApp/build/xcode-frameworks/Debug/iphonesimulator*/ComposeApp.framework` exists after the script phase; if the folder name differs, correct `FRAMEWORK_SEARCH_PATHS` in `project.yml`, regenerate, rebuild.
- `Sandbox: ... deny file-write`: confirm `ENABLE_USER_SCRIPT_SANDBOXING: NO` is in the generated project.
- The destination is rejected because the simulator runtime (iOS 17.x) is older than what the Xcode 27.0 toolchain accepts: STOP and tell the user, as agreed in the spec. Do not download a runtime.

- [ ] **Step 6: Install, launch, and screenshot**

```bash
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b
xcrun simctl install "$SIM" /tmp/komi-derived/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch --console "$SIM" zed.rainxch.githubstore > /tmp/komi-launch.log 2>&1 &
sleep 12
xcrun simctl io "$SIM" screenshot /tmp/komi-home.png
tail -30 /tmp/komi-launch.log
```

Then view `/tmp/komi-home.png` with the image viewer tool (Read it). Expected: the Komi Store home screen with the bottom navigation, and repository cards loading or loaded. If the app exits immediately, read `/tmp/komi-launch.log` and the newest file in `~/Library/Logs/DiagnosticReports` (or `xcrun simctl spawn "$SIM" log show --last 2m --predicate 'process == "iosApp"'`) and fix the crash. The most likely causes are a missing Koin binding (the error names the type; add the binding to `PlatformModule.ios.kt`) and a Compose resource that is not bundled (check that the pre-build script completed).

- [ ] **Step 7: Prove deep links reach the app**

```bash
xcrun simctl openurl "$SIM" "githubstore://search"
sleep 3
xcrun simctl io "$SIM" screenshot /tmp/komi-search.png
xcrun simctl openurl "$SIM" "githubstore://repo/Aidoku/Aidoku"
sleep 8
xcrun simctl io "$SIM" screenshot /tmp/komi-details.png
```

Read both images. Expected: the search screen, then the details screen for `Aidoku/Aidoku` with its README and a primary action button.

- [ ] **Step 8: Commit**

```bash
git add iosApp .gitignore
git commit -m "feat(ios): add Xcode project and host app" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 15: Treat iOS as mobile in shared UI and hide Android-only settings

**Files:**
- Modify: `feature/home/presentation/src/commonMain/kotlin/zed/rainxch/home/presentation/HomeRoot.kt`
- Modify: `feature/feed/presentation/src/commonMain/kotlin/zed/rainxch/feed/presentation/FeedRoot.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/Installation.kt`
- Modify: `feature/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/FeedbackBottomSheet.kt`
- Modify: `feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt`
- Modify: `feature/search/presentation/src/commonMain/kotlin/zed/rainxch/search/presentation/SearchViewModel.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/AppsRoot.kt`, `AppsViewModel.kt`, `components/AppsTopbar.kt`
- Modify: `feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/components/sections/Header.kt`

**Interfaces:**
- Consumes: `isMobile()`, `isAndroid()`, `isIos()`, `Platform.isMobile`.
- Produces: pull-to-refresh and the mobile feedback sheet on iOS, no "link copied" toast after the iOS share sheet (matching Android), an iOS Settings "Updates" group showing only "Include pre-releases" and "Skipped updates", and none of the Android-only entry points on iOS: no "add by link" button, no Keep Android Open banner, no Obtainium export, import, rescan or add-from-starred menu items (only "Export apps" remains in the Apps overflow menu), and no "Inspect APK" button on the Details page.

- [ ] **Step 1: Apply the edits**

```bash
python3 -I - <<'PY'
import pathlib

def edit(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    for old, new, count in pairs:
        assert s.count(old) == count, f"{path}: expected {count} of {old[:70]!r}, found {s.count(old)}"
        s = s.replace(old, new)
    p.write_text(s)

def swap_import(path, old_import, new_import):
    p = pathlib.Path(path)
    s = p.read_text()
    body = s.replace(old_import + "\n", "")
    still_used = old_import.rsplit(".", 1)[1] + "()" in body
    if old_import + "\n" not in s:
        raise AssertionError(f"{path}: missing {old_import}")
    if still_used:
        s = s.replace(old_import + "\n", old_import + "\n" + new_import + "\n", 1)
    else:
        s = s.replace(old_import + "\n", new_import + "\n", 1)
    p.write_text(s)

feature = "feature"
home = f"{feature}/home/presentation/src/commonMain/kotlin/zed/rainxch/home/presentation/HomeRoot.kt"
edit(home, [("            if (isAndroid()) {\n                KomiPullToRefresh(", "            if (isMobile()) {\n                KomiPullToRefresh(", 1)])
swap_import(home, "import zed.rainxch.core.domain.isAndroid", "import zed.rainxch.core.domain.isMobile")

feed = f"{feature}/feed/presentation/src/commonMain/kotlin/zed/rainxch/feed/presentation/FeedRoot.kt"
edit(feed, [("        if (isAndroid()) {\n            KomiPullToRefresh(", "        if (isMobile()) {\n            KomiPullToRefresh(", 1)])
swap_import(feed, "import zed.rainxch.core.domain.isAndroid", "import zed.rainxch.core.domain.isMobile")

tweaks = f"{feature}/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/TweaksRoot.kt"
edit(tweaks, [("                    if (isAndroid()) {\n                        installsSection(", "                    if (isMobile()) {\n                        installsSection(", 1)])
swap_import(tweaks, "import zed.rainxch.core.domain.isAndroid", "import zed.rainxch.core.domain.isMobile")

sheet = f"{feature}/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/feedback/components/FeedbackBottomSheet.kt"
edit(sheet, [("    if (isAndroid()) {\n        KomiSheet(", "    if (isMobile()) {\n        KomiSheet(", 1)])
swap_import(sheet, "import zed.rainxch.core.domain.isAndroid", "import zed.rainxch.core.domain.isMobile")

installation = f"{feature}/tweaks/presentation/src/commonMain/kotlin/zed/rainxch/tweaks/presentation/components/sections/Installation.kt"
p = pathlib.Path(installation)
s = p.read_text()
install_start = s.index("fun installSectionContent(")
guard = "    if (isDesktop()) return\n"
guard_at = s.index(guard, install_start)
s = s[:guard_at] + "    if (!isAndroid()) return\n" + s[guard_at + len(guard):]

block_start = s.index("            SettingsRow(\n                title = stringResource(Res.string.update_check_enabled_title),")
block_end_marker = "                onIntervalSelected = { onAction(TweaksAction.OnUpdateCheckIntervalChanged(it)) },\n            )\n"
block_end = s.index(block_end_marker, block_start) + len(block_end_marker)
block = s[block_start:block_end]
indented = "".join(("    " + line if line.strip() else line) for line in block.splitlines(keepends=True))
s = s[:block_start] + "            if (isAndroid()) {\n" + indented + "            }\n" + s[block_end:]
if "import zed.rainxch.core.domain.isAndroid\n" not in s:
    s = s.replace("import zed.rainxch.core.domain.isDesktop\n", "import zed.rainxch.core.domain.isAndroid\nimport zed.rainxch.core.domain.isDesktop\n", 1)
p.write_text(s)

details = f"{feature}/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt"
edit(details, [(
    "                if (platform != Platform.ANDROID) {\n                    _events.send(DetailsEvent.OnMessage(getString(Res.string.link_copied_to_clipboard)))",
    "                if (!platform.isMobile) {\n                    _events.send(DetailsEvent.OnMessage(getString(Res.string.link_copied_to_clipboard)))",
    1,
)])

search = f"{feature}/search/presentation/src/commonMain/kotlin/zed/rainxch/search/presentation/SearchViewModel.kt"
edit(search, [(
    "                    if (platform != Platform.ANDROID) {\n                        _events.send(SearchEvent.OnMessage(getString(Res.string.link_copied_to_clipboard)))",
    "                    if (!platform.isMobile) {\n                        _events.send(SearchEvent.OnMessage(getString(Res.string.link_copied_to_clipboard)))",
    1,
)])
print("ok")
PY
git diff --stat
```

- [ ] **Step 1b: Hide the Android-only entry points on the Apps and Details pages**

```bash
python3 -I - <<'PY'
import pathlib

def edit(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    for old, new, count in pairs:
        assert s.count(old) == count, f"{path}: expected {count} of {old[:70]!r}, found {s.count(old)}"
        s = s.replace(old, new)
    p.write_text(s)

def add_import(path, line):
    p = pathlib.Path(path)
    s = p.read_text()
    if line + "\n" in s:
        return
    lines = s.split("\n")
    last = max(i for i, l in enumerate(lines) if l.startswith("import "))
    lines.insert(last + 1, line)
    p.write_text("\n".join(lines))

apps = "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation"
is_ios = "import zed.rainxch.core.domain.isIos"

edit(f"{apps}/AppsRoot.kt", [(
    "        floatingActionButton = {\n            KomiFab(\n                icon = Icons.Default.Add,\n                contentDescription = stringResource(Res.string.add_by_link),\n                onClick = {\n                    onAction(AppsAction.OnAddByLinkClick)\n                },\n            )\n        },\n",
    "        floatingActionButton = {\n            if (!isIos()) {\n                KomiFab(\n                    icon = Icons.Default.Add,\n                    contentDescription = stringResource(Res.string.add_by_link),\n                    onClick = {\n                        onAction(AppsAction.OnAddByLinkClick)\n                    },\n                )\n            }\n        },\n",
    1,
)])
add_import(f"{apps}/AppsRoot.kt", is_ios)

edit(f"{apps}/components/AppsTopbar.kt", [
    ("                KomiDropdown(\n                    entries = persistentListOf(\n                        KomiMenuItem(\n                            id = \"export_apps\",",
     "                val menuEntries =\n                    persistentListOf(\n                        KomiMenuItem(\n                            id = \"export_apps\",", 1),
    ("                    ),\n                    onSelect = { item ->\n                        when (item.id) {\n                            \"export_apps\" -> onAction(AppsAction.OnExportApps)",
     "                    )\n                KomiDropdown(\n                    entries =\n                        if (isIos()) {\n                            menuEntries.filter { it.id == \"export_apps\" }.toImmutableList()\n                        } else {\n                            menuEntries\n                        },\n                    onSelect = { item ->\n                        when (item.id) {\n                            \"export_apps\" -> onAction(AppsAction.OnExportApps)", 1),
])
add_import(f"{apps}/components/AppsTopbar.kt", is_ios)
add_import(f"{apps}/components/AppsTopbar.kt", "import kotlinx.collections.immutable.toImmutableList")

edit(f"{apps}/AppsViewModel.kt", [
    ("showKaoBanner = !dismissed", "showKaoBanner = !dismissed && !isIos()", 1),
])
add_import(f"{apps}/AppsViewModel.kt", is_ios)

header = "feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/components/sections/Header.kt"
edit(header, [
    ("                    if (canInspectApk) {\n                        InspectApkButton(",
     "                    if (canInspectApk && !isIos()) {\n                        InspectApkButton(", 1),
])
add_import(header, is_ios)
print("ok")
PY
```

Expected: `ok`. If a count assertion fails, open the file, find the equivalent code, and apply the same change by hand; the intent is: nothing Android-only is reachable from the iOS UI.

Expected: `ok` and a diff touching only the seven files above. If an `assert` fails, the surrounding text differs from what this plan saw: open the file, find the equivalent line, and apply the same one-line change by hand.

- [ ] **Step 2: Compile every target**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid compileKotlinIosSimulatorArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
```

Expected: `BUILD SUCCESSFUL`. An `unused import` warning is fine; an unresolved `isMobile` means the import swap did not apply in that file, so add `import zed.rainxch.core.domain.isMobile` by hand.

- [ ] **Step 3: Rebuild, relaunch, and check Settings and Home**

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=$SIM" -derivedDataPath /tmp/komi-derived CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -3
xcrun simctl terminate "$SIM" zed.rainxch.githubstore 2>/dev/null || true
xcrun simctl install "$SIM" /tmp/komi-derived/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch "$SIM" zed.rainxch.githubstore
sleep 10
xcrun simctl openurl "$SIM" "githubstore://tweaks"
sleep 4
xcrun simctl io "$SIM" screenshot /tmp/komi-tweaks.png
```

Read `/tmp/komi-tweaks.png`. Expected: no "Installer type", "Auto update", or battery sections; the Updates group shows only "Include pre-releases" and "Skipped updates". Scroll is not possible from the command line, so if the Updates group is below the fold, take the screenshot after `xcrun simctl openurl "$SIM" "githubstore://tweaks"` on an iPad simulator (`iPad Pro (12.9-inch)` in the iOS 17.4 list) which shows more of the page, and say in the report that the lower part was checked on iPad.

- [ ] **Step 4: Check that Home and Search have content for `DiscoveryPlatform.Ios`**

```bash
xcrun simctl openurl "$SIM" "githubstore://home"
sleep 8
xcrun simctl io "$SIM" screenshot /tmp/komi-home2.png
xcrun simctl openurl "$SIM" "githubstore://search"
sleep 4
xcrun simctl io "$SIM" screenshot /tmp/komi-search2.png
```

Read both. Expected: Home shows repository cards for iOS apps. If Home or Search is empty, the spec's named risk applies: read the code paths that return `null` for `DiscoveryPlatform.Ios` (`CachedRepositoriesDataSourceImpl.toApiSlug`, `SearchRepositoryImpl` platform slug) and confirm they fall back to another data source. If they do not and the screens stay empty, STOP and ask the user whether iOS should default to the "All" platform filter.

- [ ] **Step 5: Commit**

```bash
git add feature
git commit -m "feat(ios): show mobile UI and hide Android-only settings on iOS" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Phase C: Download and "Downloaded" page

### Task 16: Platform-aware labels and strings

**Files:**
- Create: `core/presentation/src/commonMain/kotlin/zed/rainxch/core/presentation/utils/PlatformLabels.kt`
- Modify: `core/presentation/src/commonMain/composeResources/values/strings.xml`, `values-zh-rCN/strings-zh-rCN.xml`, `values-zh-rTW/strings-zh-rTW.xml`
- Modify: `feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/components/SmartInstallButton.kt`, `components/AppHeader.kt`, `components/ReleaseAssetsPicker.kt`, `DetailsRoot.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/components/AppItemCard.kt`, `CompactAppRow.kt`, `PendingUninstallSheet.kt`

**Interfaces:**
- Consumes: `isIos()`.
- Produces: `fun installedRes(): StringResource`, `fun uninstallRes(): StringResource`, `fun installingRes(): StringResource`, `fun installLatestRes(): StringResource`, `fun installVersionRes(): StringResource` (takes one `%1$s`), `fun openRes(): StringResource`, `fun openAppRes(): StringResource` in `zed.rainxch.core.presentation.utils`. Each returns the existing string on Android and Desktop and the iOS string on iOS. New strings: `downloaded`, `delete_download`, `saving_download`, `download_latest`, `download_version`, `export_download`, `download_deleted`, `download_again`.

- [ ] **Step 1: Add the strings**

```bash
python3 -I - <<'PY'
import pathlib

root = pathlib.Path("core/presentation/src/commonMain/composeResources")
strings = {
    "values": {
        "downloaded": "Downloaded",
        "delete_download": "Delete",
        "saving_download": "Saving",
        "download_latest": "Download latest",
        "download_version": "Download %1$s",
        "export_download": "Export",
        "download_deleted": "Deleted",
        "download_again": "Download again",
    },
    "values-zh-rCN": {
        "downloaded": "已下载",
        "delete_download": "删除",
        "saving_download": "保存中",
        "download_latest": "下载最新版本",
        "download_version": "下载 %1$s",
        "export_download": "导出",
        "download_deleted": "已删除",
        "download_again": "重新下载",
    },
    "values-zh-rTW": {
        "downloaded": "已下載",
        "delete_download": "刪除",
        "saving_download": "儲存中",
        "download_latest": "下載最新版本",
        "download_version": "下載 %1$s",
        "export_download": "匯出",
        "download_deleted": "已刪除",
        "download_again": "重新下載",
    },
}
file_names = {
    "values": "strings.xml",
    "values-zh-rCN": "strings-zh-rCN.xml",
    "values-zh-rTW": "strings-zh-rTW.xml",
}
for folder, entries in strings.items():
    path = root / folder / file_names[folder]
    text = path.read_text(encoding="utf-8")
    for name in entries:
        assert f'name="{name}"' not in text, f"{folder}: {name} already exists"
    lines = "".join(f'    <string name="{name}">{value}</string>\n' for name, value in entries.items())
    assert "</resources>" in text
    text = text.replace("</resources>", lines + "</resources>", 1)
    path.write_text(text, encoding="utf-8")
print("ok")
PY
```

Expected: `ok`. The `$` in `%1$s` is written literally into the XML, which is what Compose resources expect.

- [ ] **Step 2: Add the label helpers**

`PlatformLabels.kt`:

```kotlin
package zed.rainxch.core.presentation.utils

import org.jetbrains.compose.resources.StringResource
import zed.rainxch.core.domain.isIos
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.delete_download
import zed.rainxch.githubstore.core.presentation.res.download_latest
import zed.rainxch.githubstore.core.presentation.res.download_version
import zed.rainxch.githubstore.core.presentation.res.downloaded
import zed.rainxch.githubstore.core.presentation.res.export_download
import zed.rainxch.githubstore.core.presentation.res.install_latest
import zed.rainxch.githubstore.core.presentation.res.install_version
import zed.rainxch.githubstore.core.presentation.res.installed
import zed.rainxch.githubstore.core.presentation.res.installing
import zed.rainxch.githubstore.core.presentation.res.open
import zed.rainxch.githubstore.core.presentation.res.open_app
import zed.rainxch.githubstore.core.presentation.res.saving_download
import zed.rainxch.githubstore.core.presentation.res.uninstall

fun installedRes(): StringResource = if (isIos()) Res.string.downloaded else Res.string.installed

fun uninstallRes(): StringResource = if (isIos()) Res.string.delete_download else Res.string.uninstall

fun installingRes(): StringResource = if (isIos()) Res.string.saving_download else Res.string.installing

fun installLatestRes(): StringResource = if (isIos()) Res.string.download_latest else Res.string.install_latest

fun installVersionRes(): StringResource = if (isIos()) Res.string.download_version else Res.string.install_version

fun openRes(): StringResource = if (isIos()) Res.string.export_download else Res.string.open

fun openAppRes(): StringResource = if (isIos()) Res.string.export_download else Res.string.open_app
```

- [ ] **Step 3: Use them at every call site**

`AppItemCard.kt` line with `Res.string.open` is handled in Task 18 together with the "Deleted" state, so it is skipped here.

```bash
python3 -I - <<'PY'
import pathlib, re

pkg = "zed.rainxch.githubstore.core.presentation.res"
helpers_import = "import zed.rainxch.core.presentation.utils.{}"

def apply(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    used_helpers = set()
    for old, new, helper, count in pairs:
        assert s.count(old) == count, f"{path}: expected {count} of {old!r}, found {s.count(old)}"
        s = s.replace(old, new)
        used_helpers.add(helper)
    for helper in sorted(used_helpers):
        line = helpers_import.format(helper) + "\n"
        if line not in s:
            lines = s.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            lines.insert(last + 1, line.rstrip("\n"))
            s = "\n".join(lines)
    for resource in {"installed", "uninstall", "installing", "install_latest", "install_version", "open", "open_app"}:
        if f"Res.string.{resource}" not in s.replace(f"Res.string.{resource}_", ""):
            s = s.replace(f"import {pkg}.{resource}\n", "")
    p.write_text(s)

d = "feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation"
a = "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation"

apply(f"{d}/components/SmartInstallButton.kt", [
    ("stringResource(Res.string.install_latest)", "stringResource(installLatestRes())", "installLatestRes", 1),
    ("stringResource(Res.string.install_version, displaySelected ?: normSelected)", "stringResource(installVersionRes(), displaySelected ?: normSelected)", "installVersionRes", 2),
    ("stringResource(Res.string.installing)", "stringResource(installingRes())", "installingRes", 1),
    ("stringResource(Res.string.uninstall)", "stringResource(uninstallRes())", "uninstallRes", 1),
    ("stringResource(Res.string.open_app)", "stringResource(openAppRes())", "openAppRes", 1),
])
apply(f"{d}/components/AppHeader.kt", [("else -> Res.string.installed", "else -> installedRes()", "installedRes", 1)])
apply(f"{d}/components/ReleaseAssetsPicker.kt", [
    ("if (allInstalled) Res.string.installed else", "if (allInstalled) installedRes() else", "installedRes", 1),
    ("AssetOwnership.VariantStatus.INSTALLED -> Res.string.installed", "AssetOwnership.VariantStatus.INSTALLED -> installedRes()", "installedRes", 1),
])
apply(f"{d}/DetailsRoot.kt", [("stringResource(Res.string.uninstall)", "stringResource(uninstallRes())", "uninstallRes", 1)])

apply(f"{a}/components/CompactAppRow.kt", [
    ("stringResource(Res.string.open)", "stringResource(openRes())", "openRes", 1),
    ("stringResource(Res.string.uninstall)", "stringResource(uninstallRes())", "uninstallRes", 1),
])
apply(f"{a}/components/PendingUninstallSheet.kt", [("stringResource(Res.string.uninstall)", "stringResource(uninstallRes())", "uninstallRes", 1)])
apply(f"{a}/components/AppItemCard.kt", [
    ("stringResource(Res.string.installing)", "stringResource(installingRes())", "installingRes", 1),
    ("stringResource(Res.string.uninstall)", "stringResource(uninstallRes())", "uninstallRes", 1),
])
print("ok")
PY
git diff --stat
```

Expected: `ok`. Each helper replaces exactly the number of occurrences listed; a count mismatch means the file changed since this plan was written, so open it, locate the occurrence, and apply the same replacement by hand.

- [ ] **Step 4: Compile**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid compileKotlinIosSimulatorArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
```

Expected: `BUILD SUCCESSFUL`. On Android and Desktop the helpers return the original resources, so no visible change.

- [ ] **Step 5: Commit**

```bash
git add core feature
git commit -m "feat(ios): show download wording instead of install wording on iOS" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 17: Details page records downloads and exports files on iOS

**Files:**
- Modify: `feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt`
- Modify: `feature/details/data/src/commonMain/kotlin/zed/rainxch/details/data/system/InstallationManagerImpl.kt`
- Modify: `composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt`

**Interfaces:**
- Consumes: `Platform.tracksInstalledApps`, `Platform.tracksDownloadedFiles`, `DownloadedFileStore`, `AppLauncher`, `ApkPackageInfo`.
- Produces: `DetailsViewModel` gains constructor parameters `appLauncher: AppLauncher` and `downloadedFileStore: DownloadedFileStore` (after `localFileSystem`). On iOS the download is recorded in the database like an Android install, `.ipa` files are validated like `.apk` files, a record whose file is gone is ignored by the Details page, and "Open" exports the file through the share sheet. When the extracted package info has a blank `appName`, the repository name is stored.

- [ ] **Step 1: Apply the edits**

```bash
python3 -I - <<'PY'
import pathlib

def edit(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    for old, new, count in pairs:
        assert s.count(old) == count, f"{path}: expected {count} of {old[:80]!r}, found {s.count(old)}"
        s = s.replace(old, new)
    p.write_text(s)

vm = "feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt"
edit(vm, [
    # downgrade warning only applies where installs are tracked
    ("                platform == Platform.ANDROID\n            ) {\n                val isDowngrade =",
     "                platform.tracksInstalledApps\n            ) {\n                val isDowngrade =", 1),
    # signing-key override saves the record on tracked platforms
    ("                if (platform == Platform.ANDROID) {\n                    saveInstalledAppToDatabase(\n                        apkInfo = warning.pendingApkInfo,",
     "                if (platform.tracksInstalledApps) {\n                    saveInstalledAppToDatabase(\n                        apkInfo = warning.pendingApkInfo,", 1),
    # orchestrator completion saves the record on tracked platforms
    ("                    if (platform == Platform.ANDROID) {\n                        val filePath = entry.filePath",
     "                    if (platform.tracksInstalledApps) {\n                        val filePath = entry.filePath", 1),
    # ipa files carry package metadata like apk files do
    ('        val isApk = ext == "apk"\n', '        val hasPackageMetadata = ext == "apk" || ext == "ipa"\n', 1),
    ("        if (isApk) {\n", "        if (hasPackageMetadata) {\n", 1),
    # direct install path
    ("        if (platform == Platform.ANDROID && validatedApkInfo != null) {\n            saveInstalledAppToDatabase(",
     "        if (platform.tracksInstalledApps && validatedApkInfo != null) {\n            saveInstalledAppToDatabase(", 1),
    ("        } else if (platform != Platform.ANDROID) {\n            viewModelScope.launch {\n                _events.send(DetailsEvent.OnMessage(getString(Res.string.installer_saved_downloads)))",
     "        } else if (!platform.tracksInstalledApps || platform.tracksDownloadedFiles) {\n            viewModelScope.launch {\n                _events.send(DetailsEvent.OnMessage(getString(Res.string.installer_saved_downloads)))", 1),
    # export instead of launch on iOS
    ("    private fun openApp() {\n        val installedApp = _state.value.installedApp ?: return\n        val launched = installer.openApp(installedApp.packageName)\n",
     "    private fun openApp() {\n        val installedApp = _state.value.installedApp ?: return\n"
     "        if (platform.tracksDownloadedFiles) {\n"
     "            viewModelScope.launch {\n"
     "                if (appLauncher.launchApp(installedApp).isFailure) {\n"
     "                    _events.send(\n"
     "                        DetailsEvent.OnMessage(\n"
     "                            getString(Res.string.failed_to_open_app, installedApp.appName),\n"
     "                        ),\n"
     "                    )\n"
     "                }\n"
     "            }\n"
     "            return\n"
     "        }\n"
     "        val launched = installer.openApp(installedApp.packageName)\n", 1),
    # a record whose file is gone behaves as not downloaded
    ("                            val dbApps = installedAppsRepository.getAppsByRepoId(repo.id)\n",
     "                            val dbApps =\n                                installedAppsRepository\n                                    .getAppsByRepoId(repo.id)\n                                    .filter { downloadedFileStore.isFilePresent(it) }\n", 1),
    ("                .collect { apps ->\n\n                    val primaryAssetName = _state.value.primaryAsset?.name",
     "                .collect { allApps ->\n                    val apps = allApps.filter { downloadedFileStore.isFilePresent(it) }\n\n                    val primaryAssetName = _state.value.primaryAsset?.name", 1),
    # constructor
    ("    private val localFileSystem: LocalFileSystem,\n    private val packageNameParam: String? = null,\n",
     "    private val localFileSystem: LocalFileSystem,\n    private val appLauncher: AppLauncher,\n    private val downloadedFileStore: DownloadedFileStore,\n    private val packageNameParam: String? = null,\n", 1),
    ("import zed.rainxch.core.domain.system.LocalFileSystem\n",
     "import zed.rainxch.core.domain.system.DownloadedFileStore\nimport zed.rainxch.core.domain.system.LocalFileSystem\n", 1),
])
text = pathlib.Path(vm).read_text()
if "import zed.rainxch.core.domain.helpers.AppLauncher\n" not in text:
    anchor = "import zed.rainxch.core.domain.helpers.BrowserHelper\n"
    assert anchor in text
    text = text.replace(anchor, "import zed.rainxch.core.domain.helpers.AppLauncher\n" + anchor, 1)
    pathlib.Path(vm).write_text(text)

edit("composeApp/src/commonMain/kotlin/zed/rainxch/githubstore/app/di/ViewModelsModule.kt", [
    ("                localFileSystem = get(),\n", "                localFileSystem = get(),\n                appLauncher = get(),\n                downloadedFileStore = get(),\n", 1),
])

manager = "feature/details/data/src/commonMain/kotlin/zed/rainxch/details/data/system/InstallationManagerImpl.kt"
edit(manager, [("                    appName = apkInfo.appName,\n", "                    appName = apkInfo.appName.ifBlank { repo.name },\n", 1)])
print("ok")
PY
```

Expected: `ok`. `Res.string.failed_to_open_app` and `Res.string.installer_saved_downloads` are already imported in `DetailsViewModel`. If the `isApk` replacement reports a count other than 1 for `if (isApk) {`, the helper name is used elsewhere: list it with `grep -n "isApk\b" feature/details/presentation/src/commonMain/kotlin/zed/rainxch/details/presentation/DetailsViewModel.kt`, rename every use of that local `val`, and leave `isApkInspect*` names alone.

- [ ] **Step 2: Compile every target**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid compileKotlinIosSimulatorArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
./gradlew --console=plain jvmTest 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`. On Android and Desktop `tracksInstalledApps`/`tracksDownloadedFiles` have the same truth values the old `platform == ANDROID` checks had (true for Android, false for Desktop, and `tracksDownloadedFiles` is false for both), and `DownloadedFileStore.isFilePresent` returns `true` for both, so nothing changes there.

- [ ] **Step 3: Commit**

```bash
git add feature composeApp
git commit -m "feat(ios): record downloads, validate ipa files and export through the share sheet" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 18: "Deleted" state on the Downloaded page

**Files:**
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/model/AppItem.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/AppsViewModel.kt`
- Modify: `feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/components/AppItemCard.kt`

**Interfaces:**
- Consumes: `DownloadedFileStore.isFilePresent`, `AppsEvent.NavigateToRepo`, the strings `download_deleted` and `download_again`, `openRes()`.
- Produces: `AppItem.isFileMissing: Boolean`. For a missing file the version labels end with ` · Deleted`, the main button reads "Download again", and activating it opens the repository details page (which, from Task 17, treats the record as not downloaded and shows the download button). `AppsViewModel` rechecks file presence every time the screen resumes.

- [ ] **Step 1: Apply the edits**

```bash
python3 -I - <<'PY'
import pathlib

def edit(path, pairs):
    p = pathlib.Path(path)
    s = p.read_text()
    for old, new, count in pairs:
        assert s.count(old) == count, f"{path}: expected {count} of {old[:80]!r}, found {s.count(old)}"
        s = s.replace(old, new)
    p.write_text(s)

base = "feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation"

edit(f"{base}/model/AppItem.kt", [
    ("    val idleVersionLabel: String = \"\",\n", "    val idleVersionLabel: String = \"\",\n    val isFileMissing: Boolean = false,\n", 1),
])

vm = f"{base}/AppsViewModel.kt"
edit(vm, [
    ("                combine(\n                    appsRepository.getApps(),\n                    tweaksRepository.getAppsSortRule(),\n                    TimeZoneChangeSignal.revision,\n                ) { apps, sortStored, _ ->",
     "                combine(\n                    appsRepository.getApps(),\n                    tweaksRepository.getAppsSortRule(),\n                    TimeZoneChangeSignal.revision,\n                    filePresenceRevision,\n                ) { apps, sortStored, _, _ ->", 1),
    ("                                        error = existing?.error,\n                                    ),\n                                )",
     "                                        error = existing?.error,\n                                    ).markFileMissing(!downloadedFileStore.isFilePresent(domainApp)),\n                                )", 1),
    ("            AppsAction.OnLifecycleResume -> {\n                autoCheckForUpdatesIfNeeded()\n",
     "            AppsAction.OnLifecycleResume -> {\n                filePresenceRevision.update { it + 1 }\n                autoCheckForUpdatesIfNeeded()\n", 1),
    ("    private fun openApp(app: InstalledAppUi) {\n        viewModelScope.launch {\n",
     "    private fun openApp(app: InstalledAppUi) {\n"
     "        val fileMissing =\n"
     "            _state.value.apps\n"
     "                .firstOrNull { it.installedApp.packageName == app.packageName }\n"
     "                ?.isFileMissing == true\n"
     "        if (fileMissing) {\n"
     "            viewModelScope.launch {\n"
     "                _events.send(\n"
     "                    AppsEvent.NavigateToRepo(\n"
     "                        repoId = app.repoId,\n"
     "                        sourceHost = app.sourceHost,\n"
     "                        owner = app.repoOwner,\n"
     "                        repo = app.repoName,\n"
     "                        packageName = app.packageName,\n"
     "                    ),\n"
     "                )\n"
     "            }\n"
     "            return\n"
     "        }\n"
     "        viewModelScope.launch {\n", 1),
    ("    private var advancedPreviewJob: Job? = null\n",
     "    private var advancedPreviewJob: Job? = null\n\n    private val filePresenceRevision = MutableStateFlow(0)\n", 1),
    ("    private fun observeKaoBannerDismissed() {",
     "    private suspend fun AppItem.markFileMissing(missing: Boolean): AppItem {\n"
     "        if (!missing) return this\n"
     "        val deleted = getString(Res.string.download_deleted)\n"
     "        return copy(\n"
     "            isFileMissing = true,\n"
     "            versionLabel = \"$versionLabel · $deleted\",\n"
     "            idleVersionLabel = \"$idleVersionLabel · $deleted\",\n"
     "        )\n"
     "    }\n\n"
     "    private fun observeKaoBannerDismissed() {", 1),
    ("    private val localFileSystem: LocalFileSystem,\n) : ViewModel() {",
     "    private val localFileSystem: LocalFileSystem,\n    private val downloadedFileStore: DownloadedFileStore,\n) : ViewModel() {", 1),
    ("import zed.rainxch.core.domain.system.InstallPolicy\n",
     "import zed.rainxch.core.domain.system.DownloadedFileStore\nimport zed.rainxch.core.domain.system.InstallPolicy\n", 1),
])
text = pathlib.Path(vm).read_text()
resource_import = "import zed.rainxch.githubstore.core.presentation.res.download_deleted\n"
wildcard_import = "import zed.rainxch.githubstore.core.presentation.res.*\n"
if wildcard_import not in text and resource_import not in text:
    lines = text.split("\n")
    last = max(i for i, l in enumerate(lines) if l.startswith("import "))
    lines.insert(last + 1, resource_import.rstrip("\n"))
    pathlib.Path(vm).write_text("\n".join(lines))

card = f"{base}/components/AppItemCard.kt"
edit(card, [
    ("label = stringResource(Res.string.open),", "label = stringResource(if (appItem.isFileMissing) Res.string.download_again else openRes()),", 1),
])
text = pathlib.Path(card).read_text()
for line in ("import zed.rainxch.githubstore.core.presentation.res.download_again\n",
             "import zed.rainxch.core.presentation.utils.openRes\n"):
    if line not in text:
        lines = text.split("\n")
        last = max(i for i, l in enumerate(lines) if l.startswith("import "))
        lines.insert(last + 1, line.rstrip("\n"))
        text = "\n".join(lines)
text = text.replace("import zed.rainxch.githubstore.core.presentation.res.open\n", "")
pathlib.Path(card).write_text(text)
print("ok")
PY
```

Expected: `ok`. The `MutableStateFlow` and `update` imports already exist in `AppsViewModel`. If `InstalledAppUi` has no `sourceHost`, `grep -n "sourceHost" feature/apps/presentation/src/commonMain/kotlin/zed/rainxch/apps/presentation/model/InstalledAppUi.kt` showed it does (line 51), so a compile error there means the edit was applied to the wrong function.

- [ ] **Step 2: Compile every target and run tests**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid compileKotlinIosSimulatorArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head -20
./gradlew --console=plain jvmTest 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`. On Android and Desktop `isFilePresent` is always `true`, so `isFileMissing` is always `false` and the button still reads "Open".

- [ ] **Step 3: Commit**

```bash
git add feature
git commit -m "feat(ios): mark downloads whose file was deleted and offer to download again" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 19: End-to-end check on the simulator, documentation, and final regression

**Files:**
- Modify: `iosApp/project.yml` (UI test target)
- Create: `iosApp/iosAppUITests/DownloadFlowUITests.swift`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: evidence that a real `.ipa` downloads to `Documents/Komi Store Downloads`, is recorded, shows on the Downloaded page, can be exported, and recovers from deletion. Documentation of the iOS target for future work.

- [ ] **Step 1: Add a UI test that drives the real flow**

`iosApp/iosAppUITests/DownloadFlowUITests.swift`:

```swift
import XCTest

final class DownloadFlowUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func button(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    func testDownloadsAnIpaAndOffersExport() throws {
        let app = XCUIApplication()
        app.launch()
        app.open(URL(string: "githubstore://repo/Aidoku/Aidoku")!)

        let download = button(app, containing: "Download")
        XCTAssertTrue(download.waitForExistence(timeout: 90), app.debugDescription)
        download.tap()

        let export = button(app, containing: "Export")
        XCTAssertTrue(export.waitForExistence(timeout: 300), app.debugDescription)
        export.tap()

        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), app.debugDescription)
    }
}
```

In `iosApp/project.yml`, add under `targets:` after `iosApp:`:

```yaml
  iosAppUITests:
    type: bundle.ui-testing
    platform: iOS
    sources:
      - path: iosAppUITests
    dependencies:
      - target: iosApp
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: zed.rainxch.githubstore.uitests
        TEST_TARGET_NAME: iosApp
        GENERATE_INFOPLIST_FILE: YES
```

Regenerate and run the test on a clean install:

```bash
(cd iosApp && xcodegen generate)
xcrun simctl uninstall "$SIM" zed.rainxch.githubstore 2>/dev/null || true
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=$SIM" -derivedDataPath /tmp/komi-derived CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppUITests test 2>&1 | tail -40
```

Expected: `** TEST SUCCEEDED **`. If an element is not found, the failure message contains `app.debugDescription`; read it, find how Compose exposes the button (its label text, or a different element type such as `staticTexts`), and adjust only the predicate or element type in the test. If Compose does not expose the buttons to XCUITest at all, STOP and fall back to asking the user to tap "Download" and "Export" in the Simulator while this plan continues with the file checks below.

- [ ] **Step 2: Verify the file on disk**

```bash
DATA=$(xcrun simctl get_app_container "$SIM" zed.rainxch.githubstore data)
ls -la "$DATA/Documents/Komi Store Downloads/"
unzip -l "$DATA/Documents/Komi Store Downloads/Aidoku.ipa" | head -8
```

Expected: `Aidoku.ipa` of roughly 14 MB or more and no `.part-` files, and the `unzip` listing shows a `Payload/` directory.

- [ ] **Step 3: Verify the Downloaded page and the recorded identity**

```bash
DATA=$(xcrun simctl get_app_container "$SIM" zed.rainxch.githubstore data)
xcrun simctl openurl "$SIM" "githubstore://apps"
sleep 5
xcrun simctl io "$SIM" screenshot /tmp/komi-downloaded.png
sqlite3 "$DATA/Library/Application Support/KomiStore/github_store.db" \
  "select packageName, installedVersion, installedAssetName, appName from installed_apps;"
```

Read the screenshot. Expected: an `Aidoku` entry with its version and an "Export" button, no "+" add-by-link button, no Keep Android Open banner, and a database row whose `packageName` is the real bundle identifier from the IPA's `Info.plist` (not `Aidoku/Aidoku` and not blank). If the table name differs, list tables with `.tables` in `sqlite3`.

- [ ] **Step 4: Verify the deleted state and re-download**

```bash
DATA=$(xcrun simctl get_app_container "$SIM" zed.rainxch.githubstore data)
rm "$DATA/Documents/Komi Store Downloads/Aidoku.ipa"
xcrun simctl terminate "$SIM" zed.rainxch.githubstore
xcrun simctl launch "$SIM" zed.rainxch.githubstore
sleep 8
xcrun simctl openurl "$SIM" "githubstore://apps"
sleep 4
xcrun simctl io "$SIM" screenshot /tmp/komi-deleted.png
```

Read the screenshot. Expected: the `Aidoku` entry shows `... · Deleted` and its button reads "Download again". Then rerun the UI test from Step 1 (without the `uninstall`) and rerun Steps 2 and 3. Expected: the file is back, there is still exactly one row for that package, and the entry no longer says "Deleted".

- [ ] **Step 5: Verify an APK is recorded with its Android package name**

Pick any public repository that ships an `.apk` and has a recent stable release:

```bash
curl -s "https://api.github.com/repos/termux/termux-app/releases?per_page=1" | python3 -I -c "import json,sys; r=json.load(sys.stdin)[0]; print(r['tag_name'], [a['name'] for a in r['assets']][:4])"
```

Open `githubstore://repo/termux/termux-app`, download the APK through the UI (or the same UI test with the repository changed), and read the database again. Expected: `packageName` is `com.termux`. If the repository has no suitable asset, choose another and say which one was used.

- [ ] **Step 6: Document the iOS target**

In `CLAUDE.md`, under the `## Build` command block, add after the `./gradlew build` line:

```bash
(cd iosApp && xcodegen generate)                                      # iOS project (committed, regenerate after editing project.yml)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "id=<simulator udid>" build   # iOS simulator
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64              # iOS framework only
```

and add one bullet to `## Key configuration`:

```markdown
- **iOS (`iosApp/`):** `iosArm64` + `iosSimulatorArm64`, static framework `ComposeApp`, Swift host only. `Platform.IOS` is mobile, `DiscoveryPlatform.Ios`, `tracksInstalledApps` and `tracksDownloadedFiles`. "Install" is "download": `IosInstaller.install()` returns `COMPLETED`, `IosDownloader` writes straight to `Documents/Komi Store Downloads` (visible in Files), `PackageArchiveReader` reads the package id from `.apk` (`AndroidManifest.xml`) and `.ipa` (`Info.plist`). `DownloadedFileStore` flags records whose file was deleted; the Downloaded page shows them as "Deleted" with "Download again". Known limits: HTTP proxy does not carry HTTPS on Darwin, proxy credentials are ignored, language change applies on next launch, no background update checks, no notifications.
```

- [ ] **Step 7: Final regression**

```bash
./gradlew --console=plain compileKotlinJvm compileDebugKotlinAndroid compileKotlinIosSimulatorArm64 compileKotlinIosArm64 --continue 2>&1 | grep -E "^e: |FAILED|BUILD" | head
./gradlew --console=plain jvmTest 2>&1 | tail -8
./gradlew --console=plain :core:domain:iosSimulatorArm64Test :core:data:iosSimulatorArm64Test 2>&1 | tail -8
./gradlew --console=plain :composeApp:assembleDebug 2>&1 | tail -5
```

Expected: every command ends `BUILD SUCCESSFUL`. If `assembleDebug` cannot run because Android SDK platform 36 is unavailable, say so explicitly in the final report and keep the `compileDebugKotlinAndroid` result as the Android evidence. Also launch the Desktop app once to confirm it still starts (`./gradlew :composeApp:run`, look for the window, then stop it).

- [ ] **Step 8: Commit**

```bash
git add iosApp CLAUDE.md
git commit -m "test(ios): add download flow UI test and document the iOS target" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---
