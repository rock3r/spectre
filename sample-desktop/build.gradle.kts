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

// Compose Desktop reads `compose.layers.type` once at composition init, so popup-layer-variant
// coverage needs a separate JVM per layer mode. Each task below filters to
// `PopupLayerVariantsValidationTest` and sets the layer-type system property for the worker.
// `validationTest` (above) covers the default `OnSameCanvas` path, so we only register the
// non-default variants here — and aggregate them under `validationTestPopupLayers`.
val popupLayerVariantTaskName =
    "dev.sebastiano.spectre.sample.validation.PopupLayerVariantsValidationTest"

fun popupLayerTask(suffix: String, layerType: String) =
    tasks.register("validationTestLayer$suffix", Test::class) {
        description = "Runs PopupLayerVariantsValidationTest with -Dcompose.layers.type=$layerType."
        group = "verification"
        testClassesDirs = sourceSets["validation"].output.classesDirs
        classpath = sourceSets["validation"].runtimeClasspath
        useJUnitPlatform { includeTags() } // accept all
        forkEvery = 1
        filter { includeTestsMatching("$popupLayerVariantTaskName") }
        systemProperty("compose.layers.type", layerType)
    }

val validationTestLayerComponent = popupLayerTask("Component", "COMPONENT")

// `OnWindow` (Compose Desktop's `compose.layers.type=WINDOW`) is intentionally NOT registered
// here yet. Compose builds OnWindow popups inside an internal `JLayeredPaneWithTransparencyHack`
// rather than a `ComposePanel`, so neither `WindowTracker` nor `SemanticsReader` can surface
// the popup's semantics owner without a deeper rework. Tracked separately as a follow-up — see
// https://github.com/rock3r/spectre/issues/7 for the deferred checklist item.

@Suppress("UNUSED_VARIABLE")
val validationTestPopupLayers by tasks.registering {
    description =
        "Runs PopupLayerVariantsValidationTest under each Compose layer type Spectre " +
            "currently supports (OnSameCanvas via :validationTest, OnComponent via this task)."
    group = "verification"
    dependsOn(validationTestLayerComponent)
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
