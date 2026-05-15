import java.io.File
import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem
import org.gradle.language.jvm.tasks.ProcessResources

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
    // See `:core`'s build script for the rationale on the shared publish convention. Per-module
    // POM scalars live in this module's own `gradle.properties`. This module publishes the
    // JVM recording API and common implementation only; `:recording-macos` and
    // `:recording-linux` publish the runtime helper resources as separate artifacts.
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

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
//      `build/generated/screenCaptureHelper/native/macos/spectre-screencapture` and wires that
//      directory as generated resources, so the resource path is stable regardless of where
//      SwiftPM lays out its output across versions.
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
val universalHelperNotaryArchive =
    layout.buildDirectory.file("generated/screenCaptureHelperUniversal/SpectreScreenCapture.zip")

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
val universalHelperNotaryArchiveAbsolutePath =
    universalHelperNotaryArchive.get().asFile.absolutePath
val shouldNotarizeScreenCaptureKitHelper =
    providers.gradleProperty("notarizeScreenCaptureKitHelper").isPresent
val notarizationTaskEnabled =
    OperatingSystem.current().isMacOsX && shouldNotarizeScreenCaptureKitHelper
val appleDeveloperIdIdentity =
    providers
        .gradleProperty("appleDeveloperIdIdentity")
        .orElse(providers.gradleProperty("compose.desktop.mac.signing.identity"))
        .orElse(providers.environmentVariable("APPLE_DEVELOPER_IDENTITY"))
val appleNotaryKeychainProfile =
    providers
        .gradleProperty("appleNotaryKeychainProfile")
        .orElse(providers.gradleProperty("compose.desktop.mac.notarization.keychainProfile"))
        .orElse(providers.environmentVariable("APPLE_NOTARY_KEYCHAIN_PROFILE"))
val appleNotaryApiKeyPath =
    providers
        .gradleProperty("appleNotaryApiKeyPath")
        .orElse(providers.environmentVariable("APPLE_NOTARY_API_KEY_PATH"))
val appleNotaryApiKeyId =
    providers
        .gradleProperty("appleNotaryApiKeyId")
        .orElse(providers.environmentVariable("APPLE_NOTARY_API_KEY_ID"))
val appleNotaryApiIssuer =
    providers
        .gradleProperty("appleNotaryApiIssuer")
        .orElse(providers.environmentVariable("APPLE_NOTARY_API_ISSUER"))

fun notarizationInput(provider: Provider<String>, displayName: String): String =
    provider.orNull?.takeIf { it.isNotBlank() }
        ?: when {
            !notarizationTaskEnabled -> ""
            else ->
                throw GradleException(
                    "$displayName is required when -PnotarizeScreenCaptureKitHelper is set. " +
                        "Use Spectre's apple* Gradle properties, the matching APPLE_* " +
                        "environment variables, or the Compose Desktop macOS signing and " +
                        "notarization property names."
                )
        }

val appleDeveloperIdIdentityValue =
    notarizationInput(appleDeveloperIdIdentity, "APPLE_DEVELOPER_IDENTITY")
val appleNotaryKeychainProfileValue = appleNotaryKeychainProfile.orNull?.takeIf { it.isNotBlank() }
val appleNotaryApiKeyPathValue = appleNotaryApiKeyPath.orNull?.takeIf { it.isNotBlank() }
val appleNotaryApiKeyIdValue = appleNotaryApiKeyId.orNull?.takeIf { it.isNotBlank() }
val appleNotaryApiIssuerValue = appleNotaryApiIssuer.orNull?.takeIf { it.isNotBlank() }
val hasAppleNotaryApiKey =
    appleNotaryApiKeyPathValue != null &&
        appleNotaryApiKeyIdValue != null &&
        appleNotaryApiIssuerValue != null
val appleNotaryAuthArguments: List<String> =
    when {
        appleNotaryKeychainProfileValue != null ->
            listOf("--keychain-profile", appleNotaryKeychainProfileValue)
        hasAppleNotaryApiKey ->
            listOf(
                "--key",
                requireNotNull(appleNotaryApiKeyPathValue),
                "--key-id",
                requireNotNull(appleNotaryApiKeyIdValue),
                "--issuer",
                requireNotNull(appleNotaryApiIssuerValue),
            )
        !notarizationTaskEnabled -> emptyList()
        else ->
            throw GradleException(
                "A safe Apple notarization credential source is required when " +
                    "-PnotarizeScreenCaptureKitHelper is set. Configure " +
                    "APPLE_NOTARY_KEYCHAIN_PROFILE for a local notarytool keychain profile, " +
                    "or APPLE_NOTARY_API_KEY_PATH / APPLE_NOTARY_API_KEY_ID / " +
                    "APPLE_NOTARY_API_ISSUER for App Store Connect API key auth."
            )
    }

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

val signScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Codesigns the universal ScreenCaptureKit helper for distribution."
        group = "build"
        enabled = notarizationTaskEnabled
        dependsOn(verifyUniversalScreenCaptureKitHelper)
        commandLine(
            "codesign",
            "--sign",
            appleDeveloperIdIdentityValue,
            "--options",
            "runtime",
            "--timestamp",
            "--force",
            universalHelperAbsolutePath,
        )
        inputs.file(universalHelperBinary)
        outputs.file(universalHelperBinary)
    }

val zipScreenCaptureKitHelperForNotarization by
    tasks.registering(Exec::class) {
        description = "Archives the signed ScreenCaptureKit helper for Apple notarization."
        group = "build"
        enabled = notarizationTaskEnabled
        dependsOn(signScreenCaptureKitHelper)
        commandLine(
            "ditto",
            "-c",
            "-k",
            "--keepParent",
            universalHelperAbsolutePath,
            universalHelperNotaryArchiveAbsolutePath,
        )
        inputs.file(universalHelperBinary)
        outputs.file(universalHelperNotaryArchive)
    }

val notarizeScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Submits the signed ScreenCaptureKit helper archive to Apple notarization."
        group = "build"
        enabled = notarizationTaskEnabled
        dependsOn(zipScreenCaptureKitHelperForNotarization)
        commandLine(
            "xcrun",
            "notarytool",
            "submit",
            universalHelperNotaryArchiveAbsolutePath,
            *appleNotaryAuthArguments.toTypedArray(),
            "--timeout",
            "30m",
            "--wait",
        )
        inputs.file(universalHelperNotaryArchive)
    }

val verifyNotarizedScreenCaptureKitHelper by
    tasks.registering(Exec::class) {
        description = "Verifies the signed and notarized ScreenCaptureKit helper's code signature."
        group = "verification"
        enabled = notarizationTaskEnabled
        dependsOn(notarizeScreenCaptureKitHelper)
        commandLine("codesign", "--verify", "--strict", "--verbose=4", universalHelperAbsolutePath)
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
        dependsOn(
            if (shouldNotarizeScreenCaptureKitHelper) verifyNotarizedScreenCaptureKitHelper
            else verifyUniversalScreenCaptureKitHelper
        )
        from(universalHelperBinary) { rename { "spectre-screencapture" } }
        into(helperResourceDest.get().asFile.parentFile)
    }

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
// Notarization always implies the universal helper; signing/notarizing a host-arch helper would
// be misleading for a distribution build.
val useUniversalHelper =
    providers.gradleProperty("universalHelper").isPresent || shouldNotarizeScreenCaptureKitHelper

// Prebuilt / stub helper switches live up here (rather than co-located with their staging
// task definitions below) because the host-platform `processResources` hookups directly
// below need to see them: when a prebuilt/stub helper is staged, the host build path must
// not also wire itself in or both staging tasks would write the same destination and produce
// a jar inconsistent with what the release CI promised.
val prebuiltMacHelperPath = providers.gradleProperty("prebuiltMacHelperPath")
val prebuiltLinuxHelpersDir = providers.gradleProperty("prebuiltLinuxHelpersDir")
val useStubMacHelperForTesting = providers.gradleProperty("stubMacHelperForTesting").isPresent

tasks.named<ProcessResources>("processTestResources") {
    from(layout.buildDirectory.dir("generated/screenCaptureHelper"))
    from(layout.buildDirectory.dir("generated/waylandHelper"))
    if (prebuiltMacHelperPath.isPresent || useStubMacHelperForTesting) {
        dependsOn(if (useStubMacHelperForTesting) stageStubMacHelper else stagePrebuiltMacHelper)
    } else if (OperatingSystem.current().isMacOsX) {
        dependsOn(
            if (useUniversalHelper) assembleScreenCaptureKitHelperUniversal
            else assembleScreenCaptureKitHelper
        )
    }
    if (prebuiltLinuxHelpersDir.isPresent) {
        dependsOn(stagePrebuiltLinuxHelpers)
    } else if (OperatingSystem.current().isLinux) {
        dependsOn(if (useAllLinuxArches) assembleWaylandHelperAllArches else assembleWaylandHelper)
    }
}

// `-PprebuiltMacHelperPath` / `-PstubMacHelperForTesting` are mutually exclusive with the
// host-platform mac build path: when a pre-built (or stub) helper is staged, the host build
// would only overwrite the same destination and produce a jar inconsistent with the artefact
// promised by the release CI. The host build is silenced when either prebuilt path is set.
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
// Default `assembleWaylandHelper` builds the host-arch helper only — keeps contributor
// builds fast and free of cross-compile toolchain prereqs. The opt-in
// `assembleWaylandHelperAllArches` block below builds both x86_64 and aarch64 from any
// Linux host for distribution. For dev iteration without re-bundling at all,
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

// --- Cross-arch bundling (release distribution) ---------------------------------------------
//
// Opt-in path producing helpers for both x86_64 and aarch64 Linux from any Linux host —
// mirrors the macOS `assembleScreenCaptureKitHelperUniversal` shape (per-arch builds +
// per-arch staging) but without a lipo step (Linux ELF has no fat-binary equivalent; we
// keep the per-arch resource directories and let `WaylandHelperBinaryExtractor` pick at
// runtime).
//
// Cross-compile mechanism: stock rustup target + system cross-linker + apt multi-arch
// libdbus-1 sysroot. Picked over `cross` (would add a Docker-daemon prereq + a privileged
// container to CI) and `cargo-zigbuild` (would need a hand-built libdbus sysroot since
// `dbus-rs` links via pkg-config). The bare-metal recipe is:
//   1. `rustup target add <triple>` for the non-host arch.
//   2. apt: `gcc-<arch>-linux-gnu` (cross linker) + `libdbus-1-dev:<dpkg-arch>`. dpkg
//      multi-arch must be enabled (`dpkg --add-architecture <arch> && apt update`) before
//      installing foreign-arch packages — the CI step in `.github/workflows/ci.yml` does
//      this.
//   3. cargo invocation: `cargo build --release --target=<triple>` with env
//      `CARGO_TARGET_<TRIPLE_UPPER>_LINKER=<arch>-linux-gnu-gcc` and
//      `PKG_CONFIG_PATH_<arch>=/usr/lib/<arch>-linux-gnu/pkgconfig` +
//      `PKG_CONFIG_ALLOW_CROSS=1` so `dbus-rs`'s build script picks the foreign-arch `.pc`
//      files instead of the host's.
//
// Use `:recording:assembleWaylandHelperAllArches` to invoke the cross-arch staging
// explicitly. Distribution invocation: `./gradlew :recording:jar -PallLinuxArches`. CI
// release jobs (when #84 lands the publish pipeline) wire this in as the
// `processResources` dependency to bundle both arches.
val linuxHelperArchitectures = listOf("x86_64", "aarch64")

// Architecture metadata kept together so the per-arch task wiring below stays one
// straight loop rather than three when/lookups.
data class LinuxHelperTarget(
    val arch: String,
    val triple: String,
    val crossLinker: String,
    val pkgConfigLibPath: String,
)

val linuxHelperTargets = linuxHelperArchitectures.map { arch ->
    when (arch) {
        "x86_64" ->
            LinuxHelperTarget(
                arch = "x86_64",
                triple = "x86_64-unknown-linux-gnu",
                crossLinker = "x86_64-linux-gnu-gcc",
                pkgConfigLibPath = "/usr/lib/x86_64-linux-gnu/pkgconfig",
            )
        "aarch64" ->
            LinuxHelperTarget(
                arch = "aarch64",
                triple = "aarch64-unknown-linux-gnu",
                crossLinker = "aarch64-linux-gnu-gcc",
                pkgConfigLibPath = "/usr/lib/aarch64-linux-gnu/pkgconfig",
            )
        else -> error("Unsupported linux helper arch: $arch")
    }
}

val perArchCargoBuildTasks = linuxHelperTargets.map { target ->
    val taskName = "buildWaylandHelper${target.arch.replaceFirstChar { it.uppercase() }}"
    val perArchOutput =
        waylandHelperSource.dir("target/${target.triple}/release").file("spectre-wayland-helper")
    tasks.register<Exec>(taskName) {
        description = "Cross-builds the Wayland helper for ${target.arch}."
        group = "build"
        onlyIf { OperatingSystem.current().isLinux }
        workingDir = waylandHelperSource.asFile
        commandLine("cargo", "build", "--release", "--target", target.triple)
        // Cargo accepts the per-target linker as
        // `CARGO_TARGET_<TRIPLE_UPPER_UNDERSCORED>_LINKER` without needing a
        // .cargo/config.toml. The `_LINKER` form is undocumented for direct env-var
        // use but stable since cargo 1.0 — see rust-lang/cargo issues #4135 and #6133.
        environment(
            "CARGO_TARGET_${target.triple.uppercase().replace("-", "_")}_LINKER",
            target.crossLinker,
        )
        // pkg-config crate's per-target lookup uses the full Rust target triple as the
        // suffix, with hyphens converted to underscores (e.g.
        // PKG_CONFIG_PATH_aarch64_unknown_linux_gnu) — see pkg-config-rs's
        // `targeted_env_var`. The arch-only suffix doesn't match any lookup pattern, so
        // it would silently fall through to plain PKG_CONFIG_PATH and pull host-arch
        // .pc files. PKG_CONFIG_ALLOW_CROSS=1 disables pkg-config's safety guard
        // against cross-arch link config — required by dbus-rs's build.rs.
        environment("PKG_CONFIG_PATH_${target.triple.replace("-", "_")}", target.pkgConfigLibPath)
        environment("PKG_CONFIG_ALLOW_CROSS", "1")
        inputs.dir(waylandHelperSource.dir("src"))
        inputs.file(waylandHelperSource.file("Cargo.toml"))
        outputs.file(perArchOutput)
    }
}

// Per-arch staging copies — one Copy task per arch so the destination layout matches the
// resource path `WaylandHelperBinaryExtractor` probes.
val perArchStageTasks =
    linuxHelperTargets.zip(perArchCargoBuildTasks).map { (target, buildTask) ->
        val taskName = "stageWaylandHelper${target.arch.replaceFirstChar { it.uppercase() }}"
        val perArchOutput =
            waylandHelperSource
                .dir("target/${target.triple}/release")
                .file("spectre-wayland-helper")
        tasks.register<Copy>(taskName) {
            description = "Stages the ${target.arch} Wayland helper into the resources tree."
            group = "build"
            onlyIf { OperatingSystem.current().isLinux }
            dependsOn(buildTask)
            from(perArchOutput) { rename { "spectre-wayland-helper" } }
            into(layout.buildDirectory.dir("generated/waylandHelper/native/linux/${target.arch}"))
        }
    }

val assembleWaylandHelperAllArches by tasks.registering {
    description =
        "Stages the Wayland helper binary for both x86_64 and aarch64 Linux. Opt-in for " +
            "distribution builds (slower than host-arch). Wire into processResources by " +
            "passing -PallLinuxArches at invocation time."
    group = "build"
    onlyIf { OperatingSystem.current().isLinux }
    dependsOn(perArchStageTasks)
}

// Switch `processResources` to either the host-arch staging (default, fast) or the
// cross-arch staging (release). Single-staging-task wiring follows the macOS universal
// helper precedent — see the comment block above the `useUniversalHelper` declaration for
// why `mustRunAfter`-based ordering wouldn't work here.
val useAllLinuxArches = providers.gradleProperty("allLinuxArches").isPresent

// Same mutual-exclusion rule as the mac path: when `-PprebuiltLinuxHelpersDir` provides the
// per-arch wayland binaries, the host-arch cargo build must not also wire itself in — both
// would target `build/generated/waylandHelper/native/linux/<arch>/...` and the host build's
// freshly compiled x86_64 binary would overwrite the prebuilt one shipped by the release CI.
// --- Pre-built native helpers for the release pipeline (#84) --------------------------------
//
// The release CI fans out: a macOS runner builds + signs + notarises the universal SCK
// helper, a Linux runner cross-builds the x86_64 + aarch64 Wayland helpers, and a third
// "publish" job downloads both artefacts and runs the actual Maven Central upload from a
// single host. The publish job's host OS can't rebuild every helper itself, so it stages
// the pre-built ones via the project properties below and overrides the host-platform
// staging task wired above.
//
// Properties (all optional, all independently usable):
//   -PprebuiltMacHelperPath=<file>        — real notarised universal SpectreScreenCapture
//                                           binary (Mach-O fat, arm64 + x86_64) to stage.
//   -PprebuiltLinuxHelpersDir=<dir>       — directory containing
//                                           `x86_64/spectre-wayland-helper` and
//                                           `aarch64/spectre-wayland-helper` to stage.
//   -PstubMacHelperForTesting             — generates a valid Mach-O fat binary stub
//                                           (CAFEBABE + arm64 + x86_64 fat_arch entries)
//                                           in `build/generated/`. Lets the
//                                           publication-shape smoke pass on a Linux dev
//                                           machine where no notarised helper exists.
//                                           Never use for a real release.
//
// When either prebuilt path is set, the corresponding `processResources` dependency below
// becomes the prebuilt staging task instead of the host-built one — so a Linux publish
// runner can produce a recording jar bundling the macOS helper without a Swift toolchain.
// `prebuiltMacHelperPath`, `prebuiltLinuxHelpersDir`, and `useStubMacHelperForTesting` are
// declared earlier in the file (right after the universal-helper switches) so the host-
// platform `processResources` hookups can gate themselves on them — see the comment there
// for why the prebuilt path is mutually exclusive with the host build.

// `rootProject` is not config-cache-serialisable, so resolve to a plain File at config time
// and capture only that into the `from(...)` provider mapping.
val rootProjectDir: File = rootProject.projectDir
val macHelperDestDir: File = helperResourceDest.get().asFile.parentFile

// `enabled = <captured Boolean>` is the configuration-cache-friendly equivalent of
// `onlyIf { propertyVal }`: the value is captured at config time so the runtime task
// state has no lingering reference to the build-script class. (Gradle's config cache
// rejects `onlyIf { ... }` lambdas that capture script-level vals — see Gradle 9 docs
// on "cannot serialize script object references".)
val stagePrebuiltMacHelper by
    tasks.registering(Copy::class) {
        description =
            "Stages a pre-built (typically notarised universal) macOS ScreenCaptureKit " +
                "helper into the resources tree. Sourced from -PprebuiltMacHelperPath. Used " +
                "by the release CI's publish job — its host doesn't rebuild the helper."
        group = "build"
        enabled = prebuiltMacHelperPath.isPresent
        // Resolve absolute paths (like `$RUNNER_TEMP/...` on a GitHub Actions runner) as
        // they are, and treat relative paths as rooted at `rootProjectDir` so a contributor
        // can pass `./local-helpers/...` from their checkout. Inlined rather than going
        // through `File(rootProjectDir, path)` because Java's `File(File, String)` does NOT
        // treat an absolute child as overriding the parent — it concatenates them
        // (`/parent/home/runner/work/_temp/...`), which would silently break the Copy task's
        // `from(...)` resolution against real CI paths.
        from(
            prebuiltMacHelperPath.map { path ->
                val asIs = File(path)
                if (asIs.isAbsolute) asIs else File(rootProjectDir, path)
            }
        ) {
            rename { "spectre-screencapture" }
        }
        into(macHelperDestDir)
    }

// The Mach-O fat stub used by the publication-shape test is a checked-in binary fixture at
// `build-fixtures/stub-mac-helper`. Generating it inside a `doLast { ... }` here would
// otherwise capture the build-script class via the lambda, which Gradle's configuration
// cache rejects ("cannot serialize script object references"). See
// `build-fixtures/stub-mac-helper.README.md` for the layout and a one-liner to regenerate.
val stubMacHelperFixture: File =
    layout.projectDirectory.file("build-fixtures/stub-mac-helper").asFile

val stageStubMacHelper by
    tasks.registering(Copy::class) {
        description =
            "Stages the checked-in Mach-O fat-binary stub into the resources tree for " +
                "publication-shape testing. Wire in via -PstubMacHelperForTesting. " +
                "Never use for a real release — see build-fixtures/stub-mac-helper.README.md."
        group = "build"
        enabled = useStubMacHelperForTesting
        from(stubMacHelperFixture) { rename { "spectre-screencapture" } }
        into(macHelperDestDir)
    }

// Linux helpers don't need a stub equivalent: a Linux publish host can rebuild both arches
// locally (the `assembleWaylandHelperAllArches` path already lives in CI), and the test-on-
// Linux flow exercises real ELF binaries. Stubbing is only useful for the OS-mismatched
// case (Mach-O fat binary on a Linux host).
val linuxHelpersDestDir: File =
    layout.buildDirectory.dir("generated/waylandHelper/native/linux").get().asFile

val stagePrebuiltLinuxHelpers by
    tasks.registering(Copy::class) {
        description =
            "Stages pre-built Linux Wayland helpers from a directory tree. Sourced from " +
                "-PprebuiltLinuxHelpersDir=<dir> which must contain " +
                "`x86_64/spectre-wayland-helper` and `aarch64/spectre-wayland-helper`."
        group = "build"
        enabled = prebuiltLinuxHelpersDir.isPresent
        // Same absolute-path-safe resolution as in `stagePrebuiltMacHelper` above —
        // `$RUNNER_TEMP/linux-helpers` from CI must stay absolute, contributors can pass a
        // relative path rooted at `rootProjectDir`. See the comment block on
        // `stagePrebuiltMacHelper` for the `File(File, String)` gotcha this avoids.
        from(
            prebuiltLinuxHelpersDir.map { path ->
                val asIs = File(path)
                if (asIs.isAbsolute) asIs else File(rootProjectDir, path)
            }
        )
        into(linuxHelpersDestDir)
        // The source directory's expected layout already matches the resource layout, so a
        // plain copy preserves arch subdirectories.
        include("x86_64/spectre-wayland-helper", "aarch64/spectre-wayland-helper")
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
