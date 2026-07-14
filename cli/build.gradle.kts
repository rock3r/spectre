plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(projects.agent)
    implementation(projects.recording)
    runtimeOnly(projects.agentRuntime)
    runtimeOnly(projects.recordingLinux)
    runtimeOnly(projects.recordingMacos)
    runtimeOnly(projects.recordingWindows)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk.server)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(projects.agentTestFixture)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty(
        "spectre.cli.testRuntimeClasspath",
        sourceSets.test.get().runtimeClasspath.asPath,
    )
}
