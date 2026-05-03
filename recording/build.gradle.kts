import org.gradle.internal.os.OperatingSystem

/**
 * Maps the host's `os.arch` system property to the Rust target-triple architecture name we ship the
 * Wayland helper under. JVM reports `amd64` for x86_64; Rust's targets call it `x86_64`. Same for
 * `aarch64` (consistent with both JVM and Rust). Anything else falls through unchanged — the
 * recorder's [WaylandHelperBinaryExtractor] will surface a HelperNotBundledException naming the
 * unknown arch, which is more useful than a silent layout mismatch.
 */
fun linuxRustHostArch(): String =
    when (val osArch = System.getProperty("os.arch").orEmpty().lowercase()) {
        "amd64",
        "x86_64",
        "x64" -> "x86_64"
        "aarch64",
        "arm64" -> "aarch64"
        else -> osArch
    }

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    // kotlinx.serialization for the Wayland helper's JSON wire protocol (#77 stage 3).
    alias(libs.plugins.kotlinSerialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    // recording is intentionally isolated per docs/ARCHITECTURE.md — it has its own native /
    // ffmpeg boundary and shares no types with core. No projects.core dependency here.
    implementation(libs.kotlinx.coroutines.core)

    // kotlinx.serialization-json for the JVM ↔ spectre-wayland-helper protocol. The helper
    // is a small Rust binary at `recording/native/linux/` that drives the xdg-desktop-portal
    // handshake, holds the PipeWire FD, and spawns `gst-launch-1.0` with the FD inherited.
    // The JVM talks to it over stdin/stdout via newline-delimited JSON. See
    // [WaylandPortalRecorder] for the lifecycle.
    implementation(libs.kotlinx.serialization.json)

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
//   1. `swift build -c release` produces a host-arch binary at
//      `recording/native/macos/.build/release/SpectreScreenCapture`.
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
val swiftHelperBuildDir = swiftHelperSource.dir(".build/release")
val swiftHelperBinary = swiftHelperBuildDir.file("SpectreScreenCapture")
val helperResourcePath = "native/macos/spectre-screencapture"
val helperResourceDest =
    layout.buildDirectory.file("generated/screenCaptureHelper/$helperResourcePath")

// macOS deployment target for the helper. Lines up with `Package.swift`'s
// `platforms: [.macOS(.v13)]`. The triple form (with explicit version) is required by
// `swift build --triple` — bare `arm64-apple-macosx` defaults to the SDK's current macOS,
// which would silently raise the floor on every Xcode update.
val swiftMacosDeploymentTarget = "13.0"

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

// --- Universal binary (release distribution) ------------------------------------------------
//
// Opt-in path that produces an arm64+x86_64 universal binary. Default
// `assembleScreenCaptureKitHelper` stays host-arch so local iteration stays fast — the
// universal build adds two SwiftPM invocations (one per arch) plus a `lipo` step, which
// roughly doubles helper build time.
//
// Approach: per-arch `swift build --triple <arch>-apple-macosx<version>` followed by
// `lipo -create`. This deliberately avoids `swift build --arch arm64 --arch x86_64`, which
// delegates to `xcbuild` and requires a full Xcode install (the framework isn't shipped
// with the Command Line Tools). The per-triple + lipo recipe runs against plain SwiftPM +
// the standard `lipo` tool — both available with Command Line Tools alone.
//
// Use `:recording:assembleScreenCaptureKitHelperUniversal` to invoke explicitly. CI release
// jobs (when we add them) wire this in as a dependency of `processResources` to bundle the
// universal helper instead of the host-arch one.
val universalArchitectures = listOf("arm64", "x86_64")

val perArchSwiftBuildTasks = universalArchitectures.map { arch ->
    val taskName = "buildScreenCaptureKitHelper${arch.replaceFirstChar { it.uppercase() }}"
    val triple = "$arch-apple-macosx$swiftMacosDeploymentTarget"
    // SwiftPM strips the `.0` (and any deployment-target version) from the triple when
    // it builds the output directory name — `arm64-apple-macosx13.0` becomes
    // `arm64-apple-macosx`. The flag still needs the version (otherwise SwiftPM picks
    // up the SDK's current macOS as the floor); the path layout doesn't.
    val outputDirTriple = "$arch-apple-macosx"
    val perArchOutput =
        swiftHelperSource.dir(".build/$outputDirTriple/release").file("SpectreScreenCapture")
    tasks.register<Exec>(taskName) {
        description = "Builds the macOS ScreenCaptureKit helper for $arch."
        group = "build"
        onlyIf { OperatingSystem.current().isMacOsX }
        workingDir = swiftHelperSource.asFile
        commandLine("swift", "build", "-c", "release", "--triple", triple)
        inputs.dir(swiftHelperSource.dir("Sources"))
        inputs.file(swiftHelperSource.file("Package.swift"))
        outputs.file(perArchOutput)
    }
}

// Stage the universal helper under `build/` rather than alongside the per-arch builds in
// `.build/`. Gradle auto-creates output directories when registered via `outputs.file(...)`,
// which lets us avoid a `doFirst { mkdirs() }` — and the closure capture of script-level
// vals that goes with it (Gradle's configuration cache refuses to serialise that).
val universalHelperBinary =
    layout.buildDirectory.file("generated/screenCaptureHelperUniversal/SpectreScreenCapture")

// Precompute everything as plain String/File outside the task action bodies. Capturing
// `swiftHelperSource` / `universalHelperBinary` (which are Gradle Layout types) inside an
// Exec task's `commandLine(...)` trips Gradle's configuration-cache rule against
// serialising script object references.
val perArchOutputFiles = universalArchitectures.map { arch ->
    // Output dir uses the version-stripped triple — see note in the per-arch task block
    // above.
    swiftHelperSource.dir(".build/$arch-apple-macosx/release").file("SpectreScreenCapture")
}
val perArchOutputAbsolutePaths = perArchOutputFiles.map { it.asFile.absolutePath }
val universalHelperAbsolutePath = universalHelperBinary.get().asFile.absolutePath

val lipoScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Combines the per-arch helper builds into a universal arm64+x86_64 binary."
        group = "build"
        onlyIf { OperatingSystem.current().isMacOsX }
        dependsOn(perArchSwiftBuildTasks)
        commandLine(
            buildList {
                add("lipo")
                add("-create")
                add("-output")
                add(universalHelperAbsolutePath)
                addAll(perArchOutputAbsolutePaths)
            }
        )
        inputs.files(perArchOutputFiles)
        outputs.file(universalHelperBinary)
    }

val verifyUniversalScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Sanity-checks that the lipo'd helper is actually fat (arm64 + x86_64)."
        group = "verification"
        onlyIf { OperatingSystem.current().isMacOsX }
        dependsOn(lipoScreenCaptureKitHelper)
        // `lipo -verify_arch <arch>...` exits 0 only if EVERY listed architecture is present
        // in the binary, exit 1 otherwise. We deliberately use this instead of `lipo -info`
        // — the latter exits 0 even for thin (non-fat) binaries, just printing "Non-fat
        // file: ... is architecture: ...", so it would silently let a single-arch helper
        // through to a distribution build.
        commandLine(
            buildList {
                add("lipo")
                add(universalHelperAbsolutePath)
                add("-verify_arch")
                addAll(universalArchitectures)
            }
        )
        inputs.file(universalHelperBinary)
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

val assembleScreenCaptureKitHelperUniversal by
    tasks.registering(Copy::class) {
        description =
            "Stages the universal arm64+x86_64 ScreenCaptureKit helper into resources " +
                "(opt-in for distribution builds; slower than the host-arch task). Wire into " +
                "processResources by passing -PuniversalHelper at invocation time."
        group = "build"
        onlyIf { OperatingSystem.current().isMacOsX }
        dependsOn(verifyUniversalScreenCaptureKitHelper)
        from(universalHelperBinary) { rename { "spectre-screencapture" } }
        into(helperResourceDest.get().asFile.parentFile)
    }

// The generated helper directory is wired into resources unconditionally — even on non-macOS
// hosts. If we gated the srcDir registration on macOS too, a Linux CI build would produce a jar
// with no `native/macos/` entries at all, so a downstream macOS consumer would later hit
// `HelperBinaryExtractor`'s "binary not found" error. Wiring the srcDir always means the jar
// structure is host-agnostic — the helper file just isn't there if the host can't build it.
//
// The actual `assembleScreenCaptureKitHelper` task only runs on macOS (its `onlyIf`), so it's
// only attached to `processResources` when we're on a host that can produce the binary.
//
// For shipping a distribution jar, build on macOS — the README documents this. A future macOS
// CI workflow (#18 follow-up) will guard against accidentally publishing a Linux-built jar.
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/screenCaptureHelper"))

// Whether `processResources` (and therefore `jar` / distribution) bundles the host-arch helper
// or the universal one is controlled by a project property. Two reasons for this design over
// `mustRunAfter`-based ordering:
//   1. `mustRunAfter` only orders tasks that are BOTH in the requested graph; it doesn't pull
//      a task into the graph. So a flow like
//      `:recording:assembleScreenCaptureKitHelperUniversal :recording:jar` would still let
//      `processResources` run after the host-arch staging (because that's its declared
//      dependency) and finalise the jar before universal landed — published jars would
//      ship a thin binary even though universal had been requested.
//   2. Having only ONE staging task in `processResources`'s graph at any time eliminates the
//      "both wrote to the same destination, hope ordering held" race entirely. Either
//      universal or host-arch is the wired-in dependency, never both.
//
// Default invocation: `./gradlew :recording:jar` → host-arch (fast iteration).
// Distribution invocation: `./gradlew :recording:jar -PuniversalHelper` → universal binary.
val useUniversalHelper = providers.gradleProperty("universalHelper").isPresent

if (OperatingSystem.current().isMacOsX) {
    tasks.named("processResources") {
        dependsOn(
            if (useUniversalHelper) assembleScreenCaptureKitHelperUniversal
            else assembleScreenCaptureKitHelper
        )
    }
}

// --- Wayland Rust helper binary (issue #80) -------------------------------------------------
//
// `WaylandPortalRecorder` ships a small Rust CLI (`recording/native/linux/`) that drives the
// xdg-desktop-portal ScreenCast handshake, holds the PipeWire FD, and spawns gst-launch with
// the FD inherited. The shape mirrors the macOS SCK helper above — out-of-process boundary,
// stdin/stdout JSON protocol, jar-bundled per-arch — and the rationale for going native is
// also the same: the JVM-side stage-2 attempt with dbus-java + JNR-POSIX hit a UnixFD-
// unmarshalling bug that wasn't fixable trivially. See #80 comments for the bake-off log.
//
// Build pipeline (Linux only — no-op on macOS/Windows):
//   1. `cargo build --release` produces `target/release/spectre-wayland-helper`.
//   2. `assembleWaylandHelper` copies it into the resource tree at
//      `native/linux/<arch>/spectre-wayland-helper`.
//   3. `processResources` depends on the assemble task on Linux, so the JAR carries the
//      helper transparently and `WaylandHelperBinaryExtractor` finds it via the classloader.
//
// Cross-compilation (arm64 / x86_64) is currently NOT wired up — the host-arch build is
// what ships in the dev-loop jar. Distribution builds will need a per-arch matrix similar
// to the macOS lipo path; tracked as a follow-up. For dev iteration without re-bundling,
// `WaylandHelperBinaryExtractor`'s `SPECTRE_WAYLAND_HELPER` env var override points the
// recorder at `target/release/spectre-wayland-helper` directly.
val waylandHelperSource = layout.projectDirectory.dir("native/linux")
val waylandHelperBinary = waylandHelperSource.dir("target/release").file("spectre-wayland-helper")
val waylandHelperResourceDest =
    layout.buildDirectory.dir("generated/waylandHelper/native/linux/${linuxRustHostArch()}")

val buildWaylandHelper by
    tasks.registering(Exec::class) {
        description = "Builds the Linux Wayland Rust helper for the host architecture."
        group = "build"
        onlyIf { OperatingSystem.current().isLinux }
        workingDir = waylandHelperSource.asFile
        commandLine("cargo", "build", "--release")
        inputs.dir(waylandHelperSource.dir("src"))
        inputs.file(waylandHelperSource.file("Cargo.toml"))
        outputs.file(waylandHelperBinary)
    }

val assembleWaylandHelper by
    tasks.registering(Copy::class) {
        description = "Stages the Wayland helper binary into the resources tree."
        group = "build"
        onlyIf { OperatingSystem.current().isLinux }
        dependsOn(buildWaylandHelper)
        from(waylandHelperBinary) { rename { "spectre-wayland-helper" } }
        into(waylandHelperResourceDest)
    }

// Same pattern as the SCK helper: register the generated resource srcDir unconditionally so
// jar layouts are host-agnostic, but only attach the build task to processResources on
// Linux. macOS/Windows builds will have an empty `native/linux/` directory in the jar — fine,
// the recorder gates on the resource being present and falls back per HelperNotBundledException.
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/waylandHelper"))

if (OperatingSystem.current().isLinux) {
    tasks.named("processResources") { dependsOn(assembleWaylandHelper) }
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

// Manual smoke entry point — opens a JFrame, records it for ~3s via FfmpegRecorder bound to
// the Windows gdigrab backend, prints the resulting file path + size. Mirrors the SCK smoke
// for the Windows recording path (#22). Lives in the test source set so it can use the
// internal FfmpegBackend / FfmpegRecorder.withBackend hooks.
tasks.register<JavaExec>("runFfmpegGdigrabSmoke") {
    group = "verification"
    description = "Boots a JFrame, records it for ~3s via gdigrab, prints output stats."
    onlyIf { OperatingSystem.current().isWindows }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.FfmpegGdigrabSmoke")
}

// Manual smoke for the Linux x11grab path (#75). Counterpart to the gdigrab/SCK smokes.
// Requires a working X display (`DISPLAY` env var) — Wayland-only sessions without XWayland
// will fail at ffmpeg-spawn time, which is the right failure mode for a manual smoke.
tasks.register<JavaExec>("runFfmpegX11GrabSmoke") {
    group = "verification"
    description = "Boots a JFrame, records it for ~3s via x11grab, prints output stats."
    onlyIf { OperatingSystem.current().isLinux }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.FfmpegX11GrabSmoke")
}

// Manual smoke for the Wayland portal + PipeWire path (#77 stage 3). Pops the compositor's
// screen-cast permission dialog on first run; subsequent runs reuse the grant. Linux-only
// — the JVM-side recorder spawns the `spectre-wayland-helper` Rust binary that does the
// portal D-Bus handshake + FD-passing into gst-launch.
//
// For dev iteration without rebuilding the helper jar resource, set `SPECTRE_WAYLAND_HELPER`
// to a locally-built helper path (e.g. `recording/native/linux/target/release/...`).
tasks.register<JavaExec>("runWaylandPortalSmoke") {
    group = "verification"
    description =
        "Boots a JFrame, records it for ~3s via xdg-desktop-portal + gst-launch (PipeWire), " +
            "prints output stats."
    onlyIf { OperatingSystem.current().isLinux }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.portal.WaylandPortalSmoke")
    // Forward helper / gst-launch output (helper inherits stderr from JVM; gst-launch
    // inherits from helper) so the user sees what's happening live.
    standardOutput = System.out
    errorOutput = System.err
}

// Sibling of `runWaylandPortalSmoke` for #87: drives the cursor across the JFrame via
// `java.awt.Robot.mouseMove` during capture so the resulting mp4 can be eyeballed for
// cursor pixels (verifies portal `cursor_mode=Embedded` actually delivers).
tasks.register<JavaExec>("runWaylandPortalCursorSmoke") {
    group = "verification"
    description =
        "Like runWaylandPortalSmoke, but sweeps the cursor across the JFrame during capture " +
            "so the mp4 can be eyeballed for cursor pixels."
    onlyIf { OperatingSystem.current().isLinux }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.portal.WaylandPortalCursorSmoke")
    standardOutput = System.out
    errorOutput = System.err
}

// Window-targeted Wayland portal smoke (#85). Boots a JFrame, captures only the picked
// window via `SourceType.WINDOW` cropped to the window's bounds at start, prints stats.
// Useful eyeball verification: the mp4 should be window-sized (matches the JFrame's pixel
// dimensions, not the monitor's), and contain only the JFrame's pixels with no leakage
// from other apps that happen to be on the desktop.
tasks.register<JavaExec>("runWaylandPortalWindowSmoke") {
    group = "verification"
    description =
        "Boots a JFrame, records it for ~3s via portal `Window` source type cropped to the " +
            "JFrame's bounds, prints output stats. User picks the JFrame in the portal dialog."
    onlyIf { OperatingSystem.current().isLinux }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.recording.portal.WaylandPortalWindowSmoke")
    standardOutput = System.out
    errorOutput = System.err
}
