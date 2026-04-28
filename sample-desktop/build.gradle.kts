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

// Mark every validation-driven worker JVM as a macOS UI element. AppKit treats UI-element
// processes as background-only — no Dock icon, no menu bar, and (the part the user cares about)
// no focus-grab when a window is shown. The synthetic input driver dispatches AWT events
// directly so it doesn't need the window in the foreground.
//
// Trade-off: macOS restricts NSPasteboard access for UI-element processes, so the
// clipboard-driven `typeText` validation can't run under this flag. That single test is gated
// on `spectre.sample.fixture.uiElement` and skips itself when the flag is on; everything else
// runs as normal.
//
// Both flags fall through to the Gradle JVM's own system properties when set explicitly, so
// `./gradlew :sample-desktop:validationTest -Dapple.awt.UIElement=false
// -Dspectre.sample.fixture.uiElement=false` actually disables UI-element mode for one run and
// lets the typeText assertion execute. Without the override we default to "true" and stay quiet.
val uiElementOverride: Provider<String> =
    providers.systemProperty("apple.awt.UIElement").orElse("true")
val spectreUiElementFlagOverride: Provider<String> =
    providers.systemProperty("spectre.sample.fixture.uiElement").orElse(uiElementOverride)
val applyValidationJvmArgs: Test.() -> Unit = {
    systemProperty("apple.awt.UIElement", uiElementOverride.get())
    systemProperty("spectre.sample.fixture.uiElement", spectreUiElementFlagOverride.get())
}

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
        applyValidationJvmArgs()
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
        applyValidationJvmArgs()
    }

val validationTestLayerComponent = popupLayerTask("Component", "COMPONENT")
val validationTestLayerWindow = popupLayerTask("Window", "WINDOW")

// Default layer (`OnSameCanvas`) gets its own task so the aggregate `validationTestPopupLayers`
// below can guarantee SAME_CANVAS coverage on its own — running just the aggregate must NOT
// silently skip the default mode.
val validationTestLayerSameCanvas =
    tasks.register("validationTestLayerSameCanvas", Test::class) {
        description =
            "Runs PopupLayerVariantsValidationTest with the default OnSameCanvas layer mode."
        group = "verification"
        testClassesDirs = sourceSets["validation"].output.classesDirs
        classpath = sourceSets["validation"].runtimeClasspath
        useJUnitPlatform()
        forkEvery = 1
        filter { includeTestsMatching(popupLayerVariantTaskName) }
        applyValidationJvmArgs()
    }

@Suppress("UNUSED_VARIABLE")
val validationTestPopupLayers by tasks.registering {
    description =
        "Runs PopupLayerVariantsValidationTest under all three Compose layer types " +
            "(OnSameCanvas, OnComponent, OnWindow)."
    group = "verification"
    dependsOn(
        validationTestLayerSameCanvas,
        validationTestLayerComponent,
        validationTestLayerWindow,
    )
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
