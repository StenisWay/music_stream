import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Pairing with the home server, login/logout and server settings.
// Layers by package: domain (models, repository contracts, use cases),
// data (APIs, DTOs, repository implementations), presentation (Compose + ViewModels).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Optional bundled server from the git-ignored server.properties (see
// server.properties.example). Missing file = normal pairing flow.
val bundledServer = Properties().apply {
    rootProject.file("server.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun bundled(key: String) = "\"" + bundledServer.getProperty(key, "").trim() + "\""

android {
    namespace = "dev.musicstream.feature.session"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "SERVER_HOSTS", bundled("server.hosts"))
        buildConfigField("String", "SERVER_PORT", bundled("server.port"))
        buildConfigField("String", "SERVER_PIN", bundled("server.pin"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
