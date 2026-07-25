# AxBrowser — Fix Prompt v5
# Re-cloned repo. Identified exact root cause of libytdlp.so not found.

---

## EXACT ROOT CAUSE OF THE ERROR

**Error:** `Cannot run program ".../lib/arm64/libytdlp.so": error=2, No such file or directory`

`error=2` = `ENOENT` — the file path is correct (`nativeLibraryDir`) but the **file was never put there** because the Gradle download task never successfully downloaded the binary.

**Why the Gradle task failed — two bugs in `app/build.gradle.kts`:**

**Bug 1 — WRONG URLS:**
```kotlin
// Current (WRONG):
val arm64Urls = listOf(
    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp",       // Python script, NOT a binary!
    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"  // Linux x86_64, NOT ARM64!
)
```
The correct Android ARM64 binary URL is: `yt-dlp_android` — not `yt-dlp` or `yt-dlp_linux`.

**Bug 2 — `java.net.URL.openStream()` does not reliably follow GitHub CDN redirects:**
GitHub releases use multi-hop HTTP 302 redirects. `java.net.URL.openStream()` uses the JVM default redirect handler which can fail with SSL/TLS negotiation issues during Gradle builds in restricted environments.

**Result:** Gradle task prints "Failed: ..." but does NOT fail the build. `jniLibs/arm64-v8a/libytdlp.so` is never created. Android packages nothing. `nativeLibraryDir` is empty. App runs, tries to exec a file that doesn't exist. `error=2`.

---

## THE FIX — 3 files to change

---

## CHANGE 1 — Rewrite the Gradle download task in `app/build.gradle.kts`

**Replace the entire file** with this:

```kotlin
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// Helper: Download a file with manual redirect following and timeout.
// java.net.URL.openStream() fails on GitHub CDN redirects in many CI/Gradle envs.
// This implementation follows up to 10 redirects manually using HttpURLConnection.
// ─────────────────────────────────────────────────────────────────────────────
fun downloadWithRedirects(urlStr: String, destFile: File): Boolean {
    var currentUrl = urlStr
    var redirects = 0
    val maxRedirects = 10

    while (redirects <= maxRedirects) {
        val connection = URL(currentUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false   // we handle redirects manually
            connection.connectTimeout = 30_000
            connection.readTimeout    = 300_000          // 5 min — binary is ~60 MB
            connection.setRequestProperty("User-Agent", "AxBrowser-Build/1.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.connect()

            val code = connection.responseCode
            when (code) {
                200 -> {
                    // Stream to file
                    connection.inputStream.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val size = destFile.length()
                    if (size < 1_000_000L) {
                        println("  ✗ File too small after download: $size bytes (expected >1MB)")
                        destFile.delete()
                        return false
                    }
                    println("  ✓ Downloaded ${size / (1024 * 1024)}MB → ${destFile.name}")
                    return true
                }
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        println("  ✗ Redirect with no Location header from $currentUrl")
                        return false
                    }
                    currentUrl = if (location.startsWith("http")) location
                                 else "https://github.com$location"
                    redirects++
                    println("  → Redirect $redirects → $currentUrl")
                }
                else -> {
                    println("  ✗ HTTP $code from $currentUrl")
                    return false
                }
            }
        } finally {
            connection.disconnect()
        }
    }
    println("  ✗ Too many redirects for $urlStr")
    return false
}

// ─────────────────────────────────────────────────────────────────────────────
// Download yt-dlp binaries into jniLibs/ before build.
// Packaged as .so so Android extracts them to nativeLibraryDir (executable).
// W^X policy on Android 10+ means filesDir is noexec — nativeLibraryDir is not.
//
// Correct binary URLs (DO NOT CHANGE):
//   ARM64 → yt-dlp_android   (ELF ARM64, covers 99% of physical phones)
//   x86_64 → yt-dlp_linux    (ELF x86_64, covers emulators and Chrome OS)
// ─────────────────────────────────────────────────────────────────────────────
tasks.register("downloadYtDlpBinaries") {
    val arm64File = file("src/main/jniLibs/arm64-v8a/libytdlp.so")
    val x86File   = file("src/main/jniLibs/x86_64/libytdlp.so")

    doLast {
        arm64File.parentFile.mkdirs()
        x86File.parentFile.mkdirs()

        // ── ARM64 (physical Android devices) ──────────────────────────────
        if (!arm64File.exists() || arm64File.length() < 1_000_000L) {
            arm64File.delete()
            println("\n⬇  Downloading yt-dlp ARM64 (yt-dlp_android)...")
            val ok = downloadWithRedirects(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android",
                arm64File
            )
            if (!ok) {
                // Hard-fail: without this binary yt-dlp cannot work on real devices
                error("FATAL: Failed to download yt-dlp ARM64 binary. " +
                      "Check internet connection and try again: ./gradlew downloadYtDlpBinaries")
            }
        } else {
            println("✓  arm64 yt-dlp already present (${arm64File.length() / (1024*1024)}MB)")
        }

        // ── x86_64 (emulators, Chrome OS) ─────────────────────────────────
        if (!x86File.exists() || x86File.length() < 1_000_000L) {
            x86File.delete()
            println("\n⬇  Downloading yt-dlp x86_64 (yt-dlp_linux)...")
            val ok = downloadWithRedirects(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux",
                x86File
            )
            if (!ok) {
                // Non-fatal: emulators are optional
                println("⚠  Warning: x86_64 binary download failed. " +
                        "yt-dlp won't work on emulators but will work on real devices.")
            }
        } else {
            println("✓  x86_64 yt-dlp already present (${x86File.length() / (1024*1024)}MB)")
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn("downloadYtDlpBinaries")
}

// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("axbrowser.android.application")
    id("axbrowser.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.akay.axbrowser"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true   // Forces APK to extract .so to nativeLibraryDir
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(project(":core:core-ui"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":feature:feature-browser"))
    implementation(project(":feature:feature-downloads"))
    implementation(project(":feature:feature-bookmarks"))
    implementation(project(":feature:feature-history"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-filemanager"))
    implementation(project(":feature:feature-videoplayer"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
}
```

---

## CHANGE 2 — Create `.gitignore` to exclude the binary files from git

**Create file:** `.gitignore` in the project root:

```gitignore
# Android
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build/
/captures/
.externalNativeBuild/
.cxx/

# Keystores
*.jks
*.keystore
release.keystore

# yt-dlp binaries — auto-downloaded by Gradle task "downloadYtDlpBinaries"
# Do NOT commit these — they are 60–70 MB each and rebuilt from GitHub releases
app/src/main/jniLibs/arm64-v8a/libytdlp.so
app/src/main/jniLibs/x86_64/libytdlp.so
```

---

## CHANGE 3 — Add manual download script for developers who can't build via Gradle

**Create file:** `scripts/download-ytdlp.sh`

```bash
#!/usr/bin/env bash
# Run this if the Gradle task fails: bash scripts/download-ytdlp.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

ARM64_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
X86_DIR="$PROJECT_ROOT/app/src/main/jniLibs/x86_64"

mkdir -p "$ARM64_DIR" "$X86_DIR"

echo "⬇  Downloading yt-dlp ARM64 (for physical Android devices)..."
curl -L --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 300 \
  "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android" \
  -o "$ARM64_DIR/libytdlp.so"

FILE_SIZE=$(wc -c < "$ARM64_DIR/libytdlp.so")
if [ "$FILE_SIZE" -lt 1000000 ]; then
  echo "✗ Download failed — file too small ($FILE_SIZE bytes)"
  rm "$ARM64_DIR/libytdlp.so"
  exit 1
fi
echo "✓ ARM64 binary: $(du -sh "$ARM64_DIR/libytdlp.so" | cut -f1)"

echo "⬇  Downloading yt-dlp x86_64 (for emulators)..."
curl -L --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 300 \
  "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux" \
  -o "$X86_DIR/libytdlp.so" || echo "⚠  x86_64 download failed (optional)"

echo ""
echo "✓ Done. Now run: ./gradlew assembleDebug"
```

Make executable: `chmod +x scripts/download-ytdlp.sh`

---

## EXECUTION STEPS

Do these in exact order:

```bash
# Step 1: Replace app/build.gradle.kts with the new version above

# Step 2: Create .gitignore in project root

# Step 3: Create scripts/download-ytdlp.sh and make executable

# Step 4: Delete any corrupted/wrong existing binaries
rm -f app/src/main/jniLibs/arm64-v8a/libytdlp.so
rm -f app/src/main/jniLibs/x86_64/libytdlp.so

# Step 5: Clean build cache
./gradlew clean

# Step 6: Run the download task standalone first to verify it works
./gradlew downloadYtDlpBinaries

# Step 7: Check the downloaded file
ls -lh app/src/main/jniLibs/arm64-v8a/libytdlp.so
# Expected output: -rwxr-xr-x ... 60–70M ... libytdlp.so

# Step 8: Full build
./gradlew assembleDebug
```

---

## WHAT TO LOOK FOR IN BUILD OUTPUT

**Success:**
```
> Task :app:downloadYtDlpBinaries
⬇  Downloading yt-dlp ARM64 (yt-dlp_android)...
  → Redirect 1 → https://objects.githubusercontent.com/...
  ✓ Downloaded 67MB → libytdlp.so
⬇  Downloading yt-dlp x86_64 (yt-dlp_linux)...
  → Redirect 1 → https://objects.githubusercontent.com/...
  ✓ Downloaded 52MB → libytdlp.so
```

**Failure — build will now stop with clear error:**
```
> Task :app:downloadYtDlpBinaries FAILED
FAILURE: Build failed with an exception.
* What went wrong: FATAL: Failed to download yt-dlp ARM64 binary.
  Check internet connection and try again: ./gradlew downloadYtDlpBinaries
```

If the Gradle task fails due to network/SSL in your build environment, run the shell script instead:
```bash
bash scripts/download-ytdlp.sh
./gradlew assembleDebug
```

---

## VERIFICATION AFTER INSTALL

```bash
# Check binary is in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libytdlp
# Expected: lib/arm64-v8a/libytdlp.so  (60–70 MB)

# After installing on device, check nativeLibraryDir via ADB
adb shell ls -la /data/app/$(adb shell pm list packages | grep axbrowser | cut -d: -f2 | head -1)*/lib/arm64/
# Expected: libytdlp.so with -rwxr-xr-x permissions
```

**Then on device:**
- [ ] Open browser → visit any site with video
- [ ] Tap floating download button → tap "YT-DLP"
- [ ] Error should NOT be "No such file or directory"
- [ ] Download should show as RUNNING with progress updating

---

## DO NOT CHANGE ANYTHING ELSE

All other files in the repo are correct as of this read:
- `YtDlpSetup.kt` — correct, points to nativeLibraryDir ✓
- `YtDlpEngine.kt` — correct, has try-catch around ProcessBuilder ✓
- `DownloadViewModel.kt` — correct ✓
- `DownloadNotificationManager.kt` — correct ✓
- `AxBrowserApp.kt` — notification channels correct ✓
- `MainActivity.kt` — permission requests correct ✓
- `AndroidManifest.xml` — extractNativeLibs=true, correct ✓
- `BrowserNavHost.kt` — shared DownloadViewModel scoped to Activity ✓
- `BrowserScreen.kt` — correct ✓

**Only change `app/build.gradle.kts`, add `.gitignore`, add `scripts/download-ytdlp.sh`.**
