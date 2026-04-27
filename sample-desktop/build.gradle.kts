import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

// Dedicated `validation` source set for end-to-end tests that drive the live sample app
// through Spectre's own ComposeAutomator. Kept separate from `test` so the regular `check`
// task stays fast and headless-CI-friendly; `validationTest` is opt-in (typically run locally
// before a release on a real macOS display).
sourceSets {
    create("validation") {
        kotlin.srcDir("src/validation/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val validationImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

configurations["validationRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation(projects.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.coroutines.swing)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)

    "validationImplementation"(libs.kotlin.testJunit5)
    "validationImplementation"(libs.junit5.api)
    "validationRuntimeOnly"(libs.junit5.engine)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

val validationTest by
    tasks.registering(Test::class) {
        description =
            "Runs the live-environment validation tests (drives the sample app via Spectre)."
        group = "verification"
        testClassesDirs = sourceSets["validation"].output.classesDirs
        classpath = sourceSets["validation"].runtimeClasspath
        useJUnitPlatform()
        // Fork a new JVM for each test class. Compose Desktop's `application {}` installs global
        // EDT and dispatcher state that survives `exitApplication()` — sharing a JVM across
        // classes would silently break the second class's fixture (the latch never trips because
        // a previous shutdown finalises the AWT runtime).
        forkEvery = 1
        // Re-render the picker / spawn the window inside each test method via the fixture's poll
        // loop. The 10-second startupTimeout is enough headroom for cold JVM warmup on CI hardware.
    }

compose.desktop {
    application {
        mainClass = "dev.sebastiano.spectre.sample.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "dev.sebastiano.spectre.sample"
            packageVersion = "1.0.0"
        }
    }
}
