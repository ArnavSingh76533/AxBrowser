import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the

class AxAndroidApplication : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")

            the<BaseAppModuleExtension>().apply {
                compileSdk = 34

                defaultConfig {
                    applicationId = "com.akay.axbrowser"
                    minSdk = 26
                    targetSdk = 34
                    // Release builds pass -PaxVersionName=X.Y.Z -PaxVersionCode=N (see release.yml,
                    // driven by the git tag) so the app's own version actually advances - required
                    // for the in-app update checker to correctly detect "already up to date".
                    versionCode = (target.findProperty("axVersionCode") as? String)?.toIntOrNull() ?: 1
                    versionName = (target.findProperty("axVersionName") as? String) ?: "1.0.0"

                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                signingConfigs {
                    create("stable") {
                        // Same committed keystore used for GitHub Releases (see keystore/README.md).
                        // Used for BOTH build types so every CI build - debug or release - can be
                        // installed in place over the previous one; the default debug keystore is
                        // regenerated fresh on every ephemeral GitHub Actions runner, which was
                        // silently breaking in-place updates for debug builds too.
                        val keystoreFile = rootProject.file("keystore/ci-release.keystore")
                        if (keystoreFile.exists()) {
                            storeFile = keystoreFile
                            storePassword = "axbrowser123"
                            keyAlias = "axbrowser"
                            keyPassword = "axbrowser123"
                        }
                    }
                }

                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        signingConfig = signingConfigs.getByName("stable")
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                    debug {
                        isMinifyEnabled = false
                        applicationIdSuffix = ".debug"
                        signingConfig = signingConfigs.getByName("stable")
                    }
                }

                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }
            }

            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }
}
