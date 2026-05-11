import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations

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
// Notarization always implies the universal helper; signing/notarizing a host-arch helper would
// be misleading for a distribution build.
val useUniversalHelper =
    providers.gradleProperty("universalHelper").isPresent || shouldNotarizeScreenCaptureKitHelper

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

// Same pattern as the SCK helper: register the generated resource srcDir unconditionally so
// jar layouts are host-agnostic, but only attach the build task to processResources on
// Linux. macOS/Windows builds will have an empty `native/linux/` directory in the jar — fine,
// the recorder gates on the resource being present and falls back per HelperNotBundledException.
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/waylandHelper"))

// Switch `processResources` to either the host-arch staging (default, fast) or the
// cross-arch staging (release). Single-staging-task wiring follows the macOS universal
// helper precedent — see the comment block above the `useUniversalHelper` declaration for
// why `mustRunAfter`-based ordering wouldn't work here.
val useAllLinuxArches = providers.gradleProperty("allLinuxArches").isPresent

if (OperatingSystem.current().isLinux) {
    tasks.named("processResources") {
        dependsOn(if (useAllLinuxArches) assembleWaylandHelperAllArches else assembleWaylandHelper)
    }
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

// --- Bundled helper artifact verification (R3) -----------------------------------------------
//
// `verifyBundledRecordingHelpers` is the single entry point that asserts the recording jar
// being built actually contains the helpers it claims to. The task's expectations are computed
// from the host OS + project properties at configuration time:
//
//   - macOS host, default                : expect host-arch SCK helper, `lipo -verify_arch
// <host-arch>`.
//   - macOS host + `-PuniversalHelper`   : expect universal SCK helper, `lipo -verify_arch arm64
// x86_64`.
//   - Linux host, default                : expect host-arch Wayland helper, JVM-side ELF parse.
//   - Linux host + `-PallLinuxArches`    : expect both x86_64 + aarch64 Wayland helpers, JVM-side
// ELF
//                                          parse for each.
//   - Other hosts (Windows etc.)         : no expectations, task is a no-op success.
//
// Verification only — the task depends on `processResources` and inspects whatever was already
// staged. It deliberately does NOT depend on `lipoScreenCaptureKitHelper` /
// `assembleScreenCaptureKitHelperUniversal` / `assembleWaylandHelperAllArches` directly, so
// running `./gradlew check` on macOS without `-PuniversalHelper` does not trigger a universal
// build. The same project-property switches that drive `processResources` (above) drive the
// task's expectations in parallel.
//
// The Linux ELF parse is JVM-side (no `readelf` dependency, works on every dev host where this
// task might run for sanity-checking). The macOS check shells out to `lipo` because lipo only
// runs on macOS anyway, and the existing `verifyUniversalScreenCaptureKitHelper` pattern
// proves it. ELF magic + `e_machine` is the entirety of the parse — see the constants in
// `VerifyBundledRecordingHelpers` for the per-arch machine fields.

/**
 * Reports which helper artifacts should be in the recording jar and verifies each. Expectations are
 * configured at task-registration time from the host OS + project properties; the action itself
 * just walks the list and fails fast on the first missing or wrong-arch helper.
 */
abstract class VerifyBundledRecordingHelpers
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {

    /**
     * Directory `processResources` writes into. Helper paths are resolved relative to this.
     * `@Optional` because hosts that produce no helpers (Windows; macOS/Linux with
     * `processResources` skipped because no inputs landed) may not create the directory at all. The
     * action below treats an absent directory as a fail iff there are real expectations, and a
     * clean no-op when expectations are empty.
     */
    @get:Optional @get:InputDirectory abstract val resourcesDir: DirectoryProperty

    /**
     * macOS architectures expected in `native/macos/spectre-screencapture`. Empty list means "no
     * macOS expectation" (e.g. running on Linux/Windows). One arch = host-arch build; two arches =
     * universal build (the order matters only for the error message — `lipo -verify_arch` requires
     * all listed arches to be present).
     */
    @get:Input abstract val expectedMacOsArchs: ListProperty<String>

    /**
     * Linux architectures expected in `native/linux/<arch>/spectre-wayland-helper`. Empty means "no
     * Linux expectation". `["x86_64"]` or `["aarch64"]` = host-arch; `["x86_64", "aarch64"]` =
     * `-PallLinuxArches`.
     */
    @get:Input abstract val expectedLinuxArches: ListProperty<String>

    @TaskAction
    fun verify() {
        val macOsArchs = expectedMacOsArchs.get()
        val linuxArches = expectedLinuxArches.get()
        if (macOsArchs.isEmpty() && linuxArches.isEmpty()) {
            logger.lifecycle(
                "verifyBundledRecordingHelpers: no helpers expected on this host — nothing to verify."
            )
            return
        }
        val resources = resourcesDir.orNull?.asFile
        if (resources == null || !resources.isDirectory) {
            throw GradleException(
                "Recording helper verification failed: expected resources directory does not " +
                    "exist (${resourcesDir.orNull?.asFile}). processResources must have run with " +
                    "the helper staging tasks for this host wired in."
            )
        }
        val errors = mutableListOf<String>()
        if (macOsArchs.isNotEmpty()) {
            verifyMacOsHelper(macOsArchs)?.let(errors::add)
        }
        for (arch in linuxArches) {
            verifyLinuxHelper(arch)?.let(errors::add)
        }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "Recording helper verification failed:\n" + errors.joinToString("\n") { "  - $it" }
            )
        }
    }

    private fun verifyMacOsHelper(expectedArchs: List<String>): String? {
        val helperPath = "native/macos/spectre-screencapture"
        val file = resourcesDir.file(helperPath).get().asFile
        if (!file.isFile) {
            return "Expected macOS helper missing at $helperPath (resolved to ${file.absolutePath})"
        }
        // `lipo -verify_arch <arch>...` exits 0 only if EVERY listed arch is present in the
        // binary. Works for both thin (single-arch) and fat (universal) binaries. The macOS
        // universal helper task already uses this — see verifyUniversalScreenCaptureKitHelper.
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(
                buildList {
                    add("lipo")
                    add(file.absolutePath)
                    add("-verify_arch")
                    addAll(expectedArchs)
                }
            )
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            return "lipo -verify_arch ${expectedArchs.joinToString(" ")} failed for " +
                "${file.absolutePath}: exit=${result.exitValue} stdout=${stdout.toString().trim()} " +
                "stderr=${stderr.toString().trim()}"
        }
        return null
    }

    private fun verifyLinuxHelper(expectedArch: String): String? {
        val relative = "native/linux/$expectedArch/spectre-wayland-helper"
        val file = resourcesDir.file(relative).get().asFile
        if (!file.isFile) {
            return "Expected Linux helper missing at $relative (resolved to ${file.absolutePath})"
        }
        val expectedMachine =
            ELF_MACHINE_BY_ARCH[expectedArch]
                ?: return "Unknown expected Linux arch '$expectedArch' for $relative"
        val header = ByteArray(ELF_HEADER_PREFIX_BYTES)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < ELF_HEADER_PREFIX_BYTES) {
                return "File too short to be ELF ($file)"
            }
            raf.readFully(header)
        }
        if (
            header[0].toInt() and 0xff != 0x7F ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte()
        ) {
            return "Not an ELF file (bad magic) at ${file.absolutePath}"
        }
        // ELF spec: e_machine is a 2-byte little-endian field at offset 18.
        val machine =
            (header[ELF_E_MACHINE_OFFSET].toInt() and 0xff) or
                ((header[ELF_E_MACHINE_OFFSET + 1].toInt() and 0xff) shl 8)
        if (machine != expectedMachine) {
            return "Expected e_machine=0x${expectedMachine.toString(16)} ($expectedArch) " +
                "but ELF reports 0x${machine.toString(16)} at ${file.absolutePath}"
        }
        return null
    }

    companion object {
        private const val ELF_HEADER_PREFIX_BYTES: Int = 20
        private const val ELF_E_MACHINE_OFFSET: Int = 18
        // e_machine constants from the ELF spec. Stable for the lifetime of the ABI.
        private const val EM_X86_64: Int = 0x3E
        private const val EM_AARCH64: Int = 0xB7
        private val ELF_MACHINE_BY_ARCH: Map<String, Int> =
            mapOf("x86_64" to EM_X86_64, "aarch64" to EM_AARCH64)
    }
}

val verifyBundledRecordingHelpers by
    tasks.registering(VerifyBundledRecordingHelpers::class) {
        description =
            "Verifies the recording jar contains the native helpers it should, per host OS and " +
                "project-property selection (-PuniversalHelper, -PallLinuxArches). Inspection " +
                "only — does not trigger universal SCK or all-arches Wayland builds."
        group = "verification"
        // Read whatever processResources staged. Depending on the project flags, that's either
        // host-arch or the wider distribution build — the expectations below stay in sync.
        dependsOn(tasks.named("processResources"))
        resourcesDir.set(layout.buildDirectory.dir("resources/main"))

        val os = OperatingSystem.current()
        // macOS expectations. `useUniversalHelper` (declared above) is true when
        // `-PuniversalHelper` or `-PnotarizeScreenCaptureKitHelper` is set. The host-arch case
        // uses `lipo -verify_arch <host-arch>`; lipo handles thin binaries fine.
        val macOsArchs: List<String> =
            when {
                !os.isMacOsX -> emptyList()
                useUniversalHelper -> universalArchitectures
                else ->
                    listOf(
                        if (System.getProperty("os.arch").lowercase() == "aarch64") "arm64"
                        else "x86_64"
                    )
            }
        expectedMacOsArchs.set(macOsArchs)

        // Linux expectations. `useAllLinuxArches` is true when `-PallLinuxArches` is set.
        val linuxArches: List<String> =
            when {
                !os.isLinux -> emptyList()
                useAllLinuxArches -> linuxHelperArchitectures
                else -> listOf(linuxRustHostArch())
            }
        expectedLinuxArches.set(linuxArches)
    }

tasks.named("check") { dependsOn(verifyBundledRecordingHelpers) }
