import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared kernel: types every layer of every feature may use (errors,
// result wrapping, pagination). Pure Kotlin, no Android or library types.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
