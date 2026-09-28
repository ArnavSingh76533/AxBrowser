plugins {
    id("axbrowser.android.library")
    id("axbrowser.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.akay.feature.browser"
}

dependencies {
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":feature:feature-downloads"))
    implementation(project(":feature:feature-bookmarks"))
    implementation(project(":feature:feature-history"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-filemanager"))
    implementation(project(":feature:feature-videoplayer"))
    implementation(project(":feature:feature-pentest"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.webkit)
    implementation(libs.documentfile)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
