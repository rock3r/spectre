plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktfmt)
    // See `:core`'s build script for the rationale on the shared publish convention. Per-module
    // POM scalars live in this module's own `gradle.properties`.
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    api(projects.core)
    api(libs.kotlinx.serialization.json)

    // ktor-server-core is `api` because installSpectreRoutes uses
    // io.ktor.server.application.Application
    // in its public signature — consumers calling the extension must see that type on their
    // compile classpath. The remaining server-side bits (content-negotiation plugin, JSON
    // serializer) are wiring details and stay `implementation`.
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.json)
    // Client side uses CIO as the engine factory. ktor-server-cio is intentionally not pulled
    // in: installSpectreRoutes is engine-agnostic, so consumers bring their own server engine.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.kotlinx.coroutines.core)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testHost)
    // ktor-server-cio is only needed for HttpComposeAutomatorE2ETest, which boots a real
    // CIO-backed server on an ephemeral port to exercise the client class over an actual
    // TCP socket. Production callers bring their own server engine.
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.serialization.json)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
