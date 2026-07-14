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
    implementation(libs.kotlinx.serialization.cbor)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty(
        "spectre.cli.testRuntimeClasspath",
        sourceSets.test.get().runtimeClasspath.asPath,
    )
}
