plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    api(projects.inputCoordinator)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
