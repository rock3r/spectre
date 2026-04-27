plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktfmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(projects.core)
    api(libs.kotlinx.serialization.json)

    // Server runtime is `implementation` so consumers hosting the automator inherit it via
    // `api(projects.core)` + `implementation(projects.server)` only when they actually want the
    // server side. The `installSpectreRoutes` extension and `HttpComposeAutomator` client are
    // both reachable transitively from the public API.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.kotlinx.coroutines.core)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testHost)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.serialization.json)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
