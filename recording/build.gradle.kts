import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    // recording is intentionally isolated per docs/ARCHITECTURE.md — it has its own native /
    // ffmpeg boundary and shares no types with core. No projects.core dependency here.
    implementation(libs.kotlinx.coroutines.core)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// --- ScreenCaptureKit helper binary (issue #18) ----------------------------------------------
//
// The JVM-side `ScreenCaptureKitRecorder` ships a small Swift CLI (`recording/native/macos/`)
// that owns the actual `SCStream` + `AVAssetWriter` lifecycle. Reasons for the out-of-process
// shape are written up in `.plans/v2-screencapturekit-bridge.md`. Build pipeline:
//
//   1. `swift build -c release --arch arm64 --arch x86_64` produces a universal binary
//      under `recording/native/macos/.build/apple/Products/Release/SpectreScreenCapture`.
//   2. `assembleScreenCaptureKitHelper` copies it into
//      `src/main/resources/native/macos/spectre-screencapture` so the resource path is stable
//      regardless of where SwiftPM lays out its output across versions.
//   3. `processResources` depends on the assemble task (only on macOS), so the JAR carries
//      the helper transparently and `ScreenCaptureKitRecorder.extractHelper()` finds it via
//      the classloader.
//
// Non-macOS hosts skip the build entirely — the helper is only meaningful on macOS, and the
// JVM-side recorder gates on `os.name` before attempting extraction.
val swiftHelperSource = layout.projectDirectory.dir("native/macos")
// SwiftPM lays its native-arch single-config build at `.build/release/<target>` and the
// xcbuild-driven universal builds at `.build/apple/Products/Release/<target>`. We default to
// the native-arch path because universal builds require full Xcode (the xcbuild framework
// isn't present with Command Line Tools alone). A release task that produces the universal
// binary lands separately when we wire notarization.
val swiftHelperBuildDir = swiftHelperSource.dir(".build/release")
val swiftHelperBinary = swiftHelperBuildDir.file("SpectreScreenCapture")
val helperResourcePath = "native/macos/spectre-screencapture"
val helperResourceDest =
    layout.buildDirectory.file("generated/screenCaptureHelper/$helperResourcePath")

val buildScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Builds the macOS ScreenCaptureKit helper for the host architecture."
        group = "build"
        onlyIf { OperatingSystem.current().isMacOsX }
        workingDir = swiftHelperSource.asFile
        commandLine("swift", "build", "-c", "release")
        inputs.dir(swiftHelperSource.dir("Sources"))
        inputs.file(swiftHelperSource.file("Package.swift"))
        outputs.file(swiftHelperBinary)
    }

val assembleScreenCaptureKitHelper by
    tasks.registering(Copy::class) {
        description = "Stages the ScreenCaptureKit helper binary into the resources tree."
        group = "build"
        onlyIf { OperatingSystem.current().isMacOsX }
        dependsOn(buildScreenCaptureKitHelper)
        from(swiftHelperBinary) { rename { "spectre-screencapture" } }
        into(helperResourceDest.get().asFile.parentFile)
    }

if (OperatingSystem.current().isMacOsX) {
    sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/screenCaptureHelper"))
    tasks.named("processResources") { dependsOn(assembleScreenCaptureKitHelper) }
}

// Manual smoke entry point — opens a JFrame, records it for ~3s via ScreenCaptureKitRecorder,
// prints the resulting file path + size. Lives in the test source set because the helper class
// it drives is `internal` and we don't want to publish it.
tasks.register<JavaExec>("runScreenCaptureKitSmoke") {
    group = "verification"
    description =
        "Boots a JFrame, records it for ~3s via ScreenCaptureKitRecorder, prints output stats."
    onlyIf { OperatingSystem.current().isMacOsX }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorderSmoke")
}
