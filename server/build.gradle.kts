plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.core)
    implementation(libs.kotlinx.coroutines.core)
}

