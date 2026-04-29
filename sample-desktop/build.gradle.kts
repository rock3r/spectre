import org.gradle.internal.os.OperatingSystem
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
// on `spectre.sample.fixture.uiElement` (which mirrors `apple.awt.UIElement` so JVM-level
// invocations not going through this build script — IDE / CI — still gate correctly) and skips
// itself when the flag is on; everything else runs as normal.
//
// Both flags collapse to a single override: if the user passes EITHER
// `-Dapple.awt.UIElement=...` or `-Dspectre.sample.fixture.uiElement=...` to Gradle, that value
// drives BOTH worker-JVM properties. That way
// `./gradlew :sample-desktop:validationTest -Dspectre.sample.fixture.uiElement=false`
// actually exercises the typeText path (the worker JVM gets `apple.awt.UIElement=false` too,
// which restores NSPasteboard access). Defaults to "true" when neither is set.
val uiElementMode: Provider<String> =
    providers
        .systemProperty("spectre.sample.fixture.uiElement")
        .orElse(providers.systemProperty("apple.awt.UIElement"))
        .orElse("true")
val applyValidationJvmArgs: Test.() -> Unit = {
    systemProperty("apple.awt.UIElement", uiElementMode.get())
    systemProperty("spectre.sample.fixture.uiElement", uiElementMode.get())
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

// Manual diagnostic — opens a JFrame containing a ComposePanel, prints per-monitor scale +
// LocalDensity + boundsInWindow chain on each Reprint click. Used to gather Windows DPI
// scaling data for issue #21 (validate HiDpiMapper across 100/125/150/200% per-monitor DPI).
// Lives in the test source set so it can pull in test infra freely without affecting the
// production application bundle.
tasks.register<JavaExec>("runWindowsHiDpiDiagnostic") {
    group = "verification"
    description = "Boots a Compose JFrame and prints DPI scale + density on each Reprint click."
    onlyIf { OperatingSystem.current().isWindows }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.sample.WindowsHiDpiDiagnostic")
}

// Manual smoke for `RobotDriver` on Windows (#20). Drives a Compose window through real
// java.awt.Robot input — counter clicks, clipboard typeText, clearAndTypeText, Ctrl+S
// shortcut — and reports PASS/FAIL per scenario. Mirrors the diagnostic shape so future
// platform smokes can land alongside.
tasks.register<JavaExec>("runWindowsRobotSmoke") {
    group = "verification"
    description = "Drives a Compose window via real RobotDriver on Windows; PASS/FAIL per scenario."
    onlyIf { OperatingSystem.current().isWindows }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.sample.WindowsRobotSmoke")
}
