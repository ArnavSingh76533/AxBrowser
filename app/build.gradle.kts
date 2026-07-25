import java.net.HttpURLConnection
import java.net.URL

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
            useLegacyPackaging = true
        }
    }
}

tasks.register("downloadYtDlpBinaries") {
    doLast {
        fun downloadBinary(urlStr: String, destFile: File): Boolean {
            var currentUrl = urlStr
            repeat(15) { hop ->
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout    = 600_000
                    setRequestProperty("User-Agent", "AxBrowser-Gradle/1.0")
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    val code = conn.responseCode
                    when (code) {
                        200 -> {
                            destFile.parentFile.mkdirs()
                            conn.inputStream.use { inp -> destFile.outputStream().use { inp.copyTo(it) } }
                            val mb = destFile.length() / (1024 * 1024)
                            if (destFile.length() < 5_000_000L) {
                                println("  File too small: ${destFile.length()} bytes")
                                destFile.delete()
                                return false
                            }
                            println("  Downloaded ${mb}MB")
                            return true
                        }
                        301, 302, 303, 307, 308 -> {
                            val loc = conn.getHeaderField("Location") ?: return false
                            currentUrl = if (loc.startsWith("http")) loc else "https://github.com$loc"
                            println("  Redirect $hop -> $currentUrl")
                        }
                        else -> {
                            println("  HTTP $code"); return false
                        }
                    }
                } catch (e: Exception) {
                    println("  Exception: ${e.message}")
                    destFile.deleteRecursively()
                    return false
                } finally {
                    conn.disconnect()
                }
            }
            return false
        }

        val arm64 = file("src/main/jniLibs/arm64-v8a/libytdlp.so")
        val x86   = file("src/main/jniLibs/x86_64/libytdlp.so")

        if (!arm64.exists() || arm64.length() < 5_000_000L) {
            arm64.delete()
            println("\nDownloading yt-dlp for ARM64 Android...")
            val arm64Urls = listOf(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android",
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64",
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
            )
            var arm64Ok = false
            for (url in arm64Urls) {
                println("  Trying: $url")
                if (downloadBinary(url, arm64)) { arm64Ok = true; break }
                arm64.delete()
            }
            if (!arm64Ok) {
                error("FATAL: Failed to download yt-dlp ARM64 binary.")
            }
        } else {
            println("ARM64 yt-dlp present (${arm64.length() / (1024*1024)}MB)")
        }

        if (!x86.exists() || x86.length() < 5_000_000L) {
            x86.delete()
            println("\nDownloading yt-dlp for x86_64...")
            val ok = downloadBinary(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux",
                x86
            )
            if (!ok) println("Warning: x86_64 binary download failed (optional)")
        } else {
            println("x86_64 yt-dlp present (${x86.length() / (1024*1024)}MB)")
        }

        println("\n--- jniLibs contents ---")
        println("  arm64-v8a/libytdlp.so : ${if (arm64.exists()) "${arm64.length() / (1024*1024)}MB" else "MISSING"}")
        println("  x86_64/libytdlp.so    : ${if (x86.exists()) "${x86.length() / (1024*1024)}MB" else "MISSING (optional)"}")
        println("------------------------\n")
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn("downloadYtDlpBinaries")
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
