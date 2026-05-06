plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.linkzhe.shortdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.linkzhe.shortdownloader"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
