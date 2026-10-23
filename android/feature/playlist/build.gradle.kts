import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Server playlists, the "Me" hub and "add to playlist". Builds on the
// library feature because a playlist is a list of library tracks.
// Layers by package: domain (models, repository contracts, use cases),
// data (APIs, DTOs, repository implementations), presentation (Compose + ViewModels).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.musicstream.feature.playlist"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    api(project(":feature:library"))
    implementation(project(":core:network"))
    implementation(project(":core:playback"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
