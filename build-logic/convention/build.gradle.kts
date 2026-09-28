plugins {
    `kotlin-dsl`
}

group = "com.akay.buildlogic"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        register("axAndroidApplication") {
            id = "axbrowser.android.application"
            implementationClass = "AxAndroidApplication"
        }
        register("axAndroidLibrary") {
            id = "axbrowser.android.library"
            implementationClass = "AxAndroidLibrary"
        }
        register("axCompose") {
            id = "axbrowser.compose"
            implementationClass = "AxCompose"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    compileOnly("com.google.dagger:hilt-android-gradle-plugin:2.53.1")
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.0-1.0.21")
}
