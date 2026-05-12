plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
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
    // JUnit 4 and JUnit Jupiter are compileOnly so consumers pick whichever they're already
    // using; the testing module itself only references their public APIs.
    compileOnly(libs.junit4)
    compileOnly(libs.junit5.api)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit4)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    // Lets us run the JUnit 4 rule via the JUnit Platform launcher in our own tests.
    testRuntimeOnly(libs.junit5.vintageEngine)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
