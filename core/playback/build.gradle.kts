import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Playback: Media3 ExoPlayer in a MediaSessionService (background audio,
// notification, lock screen, Bluetooth/Android Auto controls). The `api`
// package is the port features use (PlayerController, QueueItem, and the
// PlaybackEventListener extension point); the rest is the Media3 engine.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.musicstream.core.playback"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:network"))

    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
