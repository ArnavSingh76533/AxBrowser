plugins {
    id("axbrowser.android.library")
    id("axbrowser.compose")
}

android {
    namespace = "com.akay.core.ui"
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.biometric)
    implementation("androidx.fragment:fragment-ktx:1.6.2")
}
