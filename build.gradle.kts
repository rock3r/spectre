import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.sebastiano.spectre.build.WindowsGraphicsCaptureHelperPackagingContract
import java.util.jar.JarFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceTask
import org.w3c.dom.Element

plugins {
    base
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    // Pulled onto the build classpath here so the `pluginManager.withPlugin(...)` convention
    // in `subprojects { }` below can configure it without each library module repeating the
    // boilerplate. Library modules opt in by applying the plugin in their own scripts.
    alias(libs.plugins.mavenPublish) apply false
}

// Coordinates flow through vanniktech-maven-publish's standard gradle.properties keys
// (`GROUP`, `VERSION_NAME`) so the release CI can override `VERSION_NAME` from the git tag
// via `-PVERSION_NAME=...` without rewriting the build script. The release pipeline owns
// this lever; everyday builds inherit the `-SNAPSHOT` from `gradle.properties`.
group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

val generatedSourceExcludes = arrayOf("**/build/**", "**/generated/**")

configure<KtfmtExtension> { kotlinLangStyle() }

configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    basePath.set(rootProject.layout.projectDirectory)
}

// Homebrew/Scoop package-manifest contracts (#283/#284/#400).
//
// Strategy (issue #400): structural/generator checks need only python3 + bash and
// stay on every Unix `./gradlew check`. Ruby install-semantics are a separate
// host-appropriate task so clean Linux without Ruby is not a surprise failure.
// under CI (or when Ruby is present), the semantics task runs; missing Ruby on
// CI fails closed via an actionable preflight in the shell wrapper.
// onlyIf lambdas must not close over the Gradle script object (configuration cache).
val verifyCliPackageManifests by
    tasks.registering(Exec::class) {
        description =
            "Generates CLI package manifests and asserts structural Homebrew/Scoop " +
                "contracts (wrapper bin entry, install-body sync). Requires python3; no Ruby."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-generate-cli-package-manifests.sh")
        onlyIf("Unix host with bash") {
            !System.getProperty("os.name").orEmpty().startsWith("Windows")
        }
        inputs
            .files(
                ".github/scripts/generate-cli-package-manifests.py",
                ".github/scripts/test-generate-cli-package-manifests.sh",
                "Formula/spectre.rb",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        // Always re-run: generator is pure but failures are cheap to catch on every check.
        outputs.upToDateWhen { false }
    }

// Homebrew formula *behavioral* install-semantics (Ruby). Not required on every
// clean Linux contributor host; always required under CI so #283/#284/#390 cannot
// be orphaned by a silent skip.
val verifyHomebrewFormulaInstallSemantics by
    tasks.registering(Exec::class) {
        description =
            "Homebrew formula install-semantics (dual-layout Spectre.app discovery, " +
                "wrapper vs symlink). Requires Ruby; actionable preflight if missing."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-homebrew-formula-install-semantics.sh")
        onlyIf("Unix host with bash, and Ruby present or CI") {
            val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            if (isWindows) {
                false
            } else {
                val ci = !System.getenv("CI").isNullOrBlank()
                val hasRuby =
                    runCatching { ProcessBuilder("ruby", "--version").start().waitFor() == 0 }
                        .getOrDefault(false)
                hasRuby || ci
            }
        }
        inputs
            .files(
                ".github/scripts/generate-cli-package-manifests.py",
                ".github/scripts/test-homebrew-formula-install-semantics.sh",
                ".github/scripts/test-homebrew-formula-install-semantics.rb",
                "Formula/spectre.rb",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

// Regression guard: structural path never requires Ruby; missing Ruby on the
// semantics path fails with a preflight message (#400).
val verifyCliPackageManifestHostDeps by
    tasks.registering(Exec::class) {
        description =
            "Asserts package-manifest host-dep split: no undeclared Ruby on structural " +
                "checks; actionable preflight when Ruby is missing for install-semantics."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-cli-package-manifest-host-deps.sh")
        onlyIf("Unix host with bash") {
            !System.getProperty("os.name").orEmpty().startsWith("Windows")
        }
        inputs
            .files(
                ".github/scripts/test-cli-package-manifest-host-deps.sh",
                ".github/scripts/test-generate-cli-package-manifests.sh",
                ".github/scripts/test-homebrew-formula-install-semantics.sh",
                ".github/scripts/test-homebrew-formula-install-semantics.rb",
                ".github/scripts/generate-cli-package-manifests.py",
                "Formula/spectre.rb",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

val verifyReleaseVersionScript by
    tasks.registering(Exec::class) {
        description = "Runs contract tests for .github/scripts/derive-release-version.sh."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-derive-release-version.sh")
        onlyIf("Unix host with bash") {
            !System.getProperty("os.name").orEmpty().startsWith("Windows")
        }
        inputs
            .files(
                ".github/scripts/derive-release-version.sh",
                ".github/scripts/test-derive-release-version.sh",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

val verifyMacosCliBundleReleaseContract by
    tasks.registering(Exec::class) {
        description =
            "Asserts the release workflow verifies the final extracted macOS CLI archive " +
                "with codesign, stapler, and Gatekeeper."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-verify-macos-cli-bundle.sh")
        onlyIf("Unix host with bash") {
            !System.getProperty("os.name").orEmpty().startsWith("Windows")
        }
        inputs
            .files(
                ".github/workflows/release.yml",
                ".github/workflows/macos-release-artifacts.yml",
                ".github/workflows/notarize-macos.yml",
                ".github/scripts/verify-macos-cli-bundle.sh",
                ".github/scripts/test-verify-macos-cli-bundle.sh",
                ".github/scripts/test-macos-cli-seal-preservation.sh",
                ".github/scripts/test-macos-release-artifact-workflows.sh",
                ".github/scripts/test-macos-roast-dual-package-graph.sh",
                ".github/scripts/inspect-macos-roast-package-dests.init.gradle.kts",
                "docs/PUBLISHING.md",
                "docs/RELEASE-SMOKE.md",
                "Formula/spectre.rb",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

val verifyWindowsReleaseSmokeScript by
    tasks.registering(Exec::class) {
        description =
            "Contract tests for scripts/windows-release-smoke.ps1 (ASCII-only for WinPS 5.1, " +
                "documented Bypass/pwsh one-liners in script + RELEASE-SMOKE.md)."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("bash", ".github/scripts/test-windows-release-smoke-script.sh")
        onlyIf("Unix host with bash") {
            !System.getProperty("os.name").orEmpty().startsWith("Windows")
        }
        inputs
            .files(
                "scripts/windows-release-smoke.ps1",
                "docs/RELEASE-SMOKE.md",
                ".github/scripts/test-windows-release-smoke-script.sh",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

val verifyIdeUiTestWorkflow by
    tasks.registering(Exec::class) {
        description = "Asserts the IDE UI workflow uses reliable direct JetBrains repositories."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("python3", ".github/scripts/test-ide-uitest-workflow.py")
        onlyIf("Host with python3") {
            runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }
                .getOrDefault(false)
        }
        inputs
            .files(
                ".github/workflows/ide-uitest.yml",
                ".github/scripts/test-ide-uitest-workflow.py",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

val verifyReleaseSmokeScripts by
    tasks.registering(Exec::class) {
        description = "Contract tests for cross-platform release-smoke orchestration and MCP stdio."
        group = "verification"
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine("python3", ".github/scripts/test-release-smoke-scripts.py")
        onlyIf("Host with python3") {
            runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }
                .getOrDefault(false)
        }
        inputs
            .files(
                "scripts/release-smoke.py",
                "scripts/smoke_lib.py",
                "scripts/mcp-stdio-smoke.py",
                ".github/scripts/test-release-smoke-scripts.py",
                "docs/RELEASE-SMOKE.md",
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
    }

// buildSrc is not a project of this build for `dependsOn(":buildSrc:…")`, but its tests
// cover the shared Windows helper packaging contract. Invoke them via a nested Gradle run
// on the buildSrc project directory (same pattern as `./gradlew -p buildSrc test`).
val buildSrcUnitTests by
    tasks.registering(Exec::class) {
        group = "verification"
        description =
            "Runs buildSrc unit tests (Windows helper packaging, no-core plugin packaging, and related)."
        workingDir = rootProject.layout.projectDirectory.asFile
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = rootProject.layout.projectDirectory.file(wrapperName).asFile.absolutePath
        // On Windows, Exec launches the .bat directly. On Unix, the shell script is executable.
        commandLine(wrapper, "-p", "buildSrc", "test")
        // Nested Gradle manages its own up-to-date checks; always re-enter so check is honest.
        outputs.upToDateWhen { false }
    }

tasks.named("check") {
    dependsOn(
        "detekt",
        "ktfmtCheck",
        verifyCliPackageManifests,
        verifyHomebrewFormulaInstallSemantics,
        verifyCliPackageManifestHostDeps,
        verifyReleaseVersionScript,
        verifyMacosCliBundleReleaseContract,
        verifyWindowsReleaseSmokeScript,
        verifyIdeUiTestWorkflow,
        verifyReleaseSmokeScripts,
        buildSrcUnitTests,
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    exclude(*generatedSourceExcludes)
}

// The detekt 2.x aggregator (`:detekt`) is a no-op marker that does NOT transitively
// depend on the per-source-set `:detektMain` / `:detektTest` tasks the plugin also
// registers. Without this fan-out, `./gradlew check` would skip the source-set tasks
// where type-resolution-aware rules (UnsafeCallOnNullableType, InjectDispatcher, …)
// actually run, so findings would never reach CI. `tasks.matching` stays lazy so
// modules that don't register a `detektTest` (no test source set) are tolerated.
val perSourceSetDetektNames = setOf("detektMain", "detektTest")

tasks.named("detekt") { dependsOn(tasks.matching { it.name in perSourceSetDetektNames }) }

subprojects {
    apply(plugin = "base")

    group = rootProject.group
    version = rootProject.version

    // Runtime matrix (#216 / epic #215): modules keep `jvmToolchain(21)` for compile, but
    // matrix cells must *execute* Test / JavaExec workers on the provisioned JBR/Temurin.
    // When SPECTRE_MATRIX_JAVA_HOME is set (by `.github/workflows/runtime-matrix.yml`), force
    // the worker JVM onto that home so JBR 25 cells do not silently fall back to a toolchain
    // JDK 21.
    val matrixJavaHome = providers.environmentVariable("SPECTRE_MATRIX_JAVA_HOME")
    val matrixJavaExecutable = matrixJavaHome.map { home ->
        val javaName =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
        file("$home/bin/$javaName").absolutePath
    }
    tasks.withType<Test>().configureEach {
        if (matrixJavaHome.isPresent) {
            // Test.executable is a Property<String> on modern Gradle; setExecutable keeps
            // older accessors happy too.
            setExecutable(matrixJavaExecutable.get())
        }
    }
    tasks.withType<JavaExec>().configureEach {
        if (matrixJavaHome.isPresent) {
            setExecutable(matrixJavaExecutable.get())
        }
    }

    pluginManager.withPlugin("com.ncorti.ktfmt.gradle") {
        extensions.configure<KtfmtExtension> { kotlinLangStyle() }

        tasks.named("check") { dependsOn("detekt", "ktfmtCheck") }
    }

    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel = true
            basePath.set(rootProject.layout.projectDirectory)
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget = "21"
            exclude(*generatedSourceExcludes)
        }

        tasks.named("detekt") { dependsOn(tasks.matching { it.name in perSourceSetDetektNames }) }
    }

    tasks
        .matching { it.name.startsWith("ktfmtCheck") || it.name.startsWith("ktfmtFormat") }
        .configureEach { (this as? SourceTask)?.exclude(*generatedSourceExcludes) }

    // --- Maven Central publishing convention (#84) -------------------------------------------
    //
    // Library modules apply `com.vanniktech.maven.publish` in their own build scripts. This
    // block fires only when the plugin is present, so samples and fixtures never accidentally
    // pick up a `publish` task. Keep the expected artifact set in sync with
    // `publishedLibraryProjects` and docs/PUBLISHING.md.
    //
    // POM scalars (name, description, licence URL, SCM, developer) come from gradle.properties:
    // `POM_*` in the root file is the source of truth for everything shared; per-module
    // `POM_NAME` / `POM_DESCRIPTION` / `POM_ARTIFACT_ID` live alongside each module's script.
    // The plugin reads them automatically — we only need to wire publication target + signing
    // here.
    //
    // Signing only engages when an in-memory GPG key is provided
    // (`ORG_GRADLE_PROJECT_signingInMemoryKey` env or `-PsigningInMemoryKey=...`). Local
    // `publishToMavenLocal` works without any signing config, which keeps the artefact-shape
    // verification flow (see `:verifyMavenLocalPublication` below) friction-free for
    // contributors. The release CI sets the env vars from repo secrets so tag-driven uploads
    // are signed.
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // Sonatype Central Portal endpoint — the s01 OSSRH staging server is being
            // retired (issue #84 notes this). `automaticRelease=false` keeps the deployment
            // in the Central staging "uploaded" state on success so a human can sanity-check
            // the artefacts before promoting. Flip to true once the first published version
            // is in the wild and we trust the pipeline. In vanniktech 0.34+ the `SonatypeHost`
            // arg is gone — Central Portal is the only supported destination.
            publishToMavenCentral(automaticRelease = false)

            // `SourcesJar.Sources()` publishes a sources jar built from each module's main
            // source set. `JavadocJar.Empty()` satisfies Central's gate without a Dokka
            // dependency — the user docs at https://spectre.sebastiano.dev are the real API
            // reference, so an empty javadoc jar is a deliberate, documented trade-off.
            // Dokka HTML can land in a follow-up if/when the API surface stabilises further.
            configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))

            // Signing fires only when the in-memory key is configured. Vanniktech's
            // `signAllPublications()` registers a `Sign...` task on every publication; without
            // the key it fails at task-execution time, which would break `publishToMavenLocal`
            // for contributors. The property check keeps the local path frictionless: CI
            // exports the key + passphrase from repo secrets and signing engages
            // automatically.
            val hasSigningKey =
                providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true
            if (hasSigningKey) {
                signAllPublications()
            }

            // No explicit `pom { ... }` block here: vanniktech auto-populates the POM from
            // the `GROUP` / `VERSION_NAME` / `POM_*` properties in `gradle.properties` (root
            // file for shared values, per-module files for `POM_NAME` / `POM_DESCRIPTION` /
            // `POM_ARTIFACT_ID`). Adding an explicit block here would duplicate entries —
            // verified by `:verifyMavenLocalPublication`'s POM completeness check.
        }
    }
}

// --- Maven Local publication-shape verification (#84) --------------------------------------
//
// `verifyMavenLocalPublication` is the single entry point for "did publishToMavenLocal
// produce the artefact set that Central will accept?". The task:
//
//   1. Depends on every library module's `publishToMavenLocal`.
//   2. Walks `~/.m2/repository/<group>/<artifactId>/<version>/` for each module.
//   3. Asserts each module has main jar + sources jar + javadoc jar + POM + module file.
//   4. Asserts each POM has <name>, <description>, <url>, <licenses>, <scm>, <developers>
//      populated — Central rejects POMs missing any of these.
//   5. Asserts no sources jar contains generated `native/...` helper binaries.
//   6. Asserts `:recording` stays API/common-only while `:recording-macos`,
//      `:recording-linux`, and `:recording-windows` carry the expected platform helper resources.
//
// Local invocation:
//   ./gradlew verifyMavenLocalPublication \
//       -PstubMacHelperForTesting \
//       -PallLinuxArches
//
// CI invocation (publish job, after downloading the artefacts):
//   ./gradlew verifyMavenLocalPublication \
//       -PprebuiltMacHelperPath=<path> \
//       -PprebuiltLinuxHelpersDir=<dir> \
//       -PprebuiltWindowsHelpersDir=<dir>
//
// Verification only — it does not republish or mutate state. Safe to run repeatedly.
val publishedLibraryProjects =
    listOf(
        ":core",
        ":server",
        ":recording",
        ":recording-macos",
        ":recording-linux",
        ":recording-windows",
        ":agent",
        ":agent-runtime",
        ":testing",
    )

// Configure-time inputs captured into vals so the action body is configuration-cache safe
// (no closure capture of Project references). The closure-based task is preferred over a
// typed task class here because kotlin-dsl emits build-script classes as Build_gradle$inner
// types whose instantiation Gradle refuses ("non-static inner class") — registering a
// typed task pulls us into a `buildSrc` plugin, which is overkill for verification logic
// of this size.
val publishGroupId: String = providers.gradleProperty("GROUP").get()
val publishVersion: String = providers.gradleProperty("VERSION_NAME").get()
val publishArtifactIds: Map<String, String> = publishedLibraryProjects.associateWith { path ->
    val sub =
        rootProject.findProject(path) ?: error("Project $path missing from settings.gradle.kts")
    sub.findProperty("POM_ARTIFACT_ID") as? String
        ?: error("$path missing POM_ARTIFACT_ID in its gradle.properties")
}
val mavenLocalRepoRoot: String = "${System.getProperty("user.home")}/.m2/repository"

// Recording jar entries we expect to find. Windows uses
// [WindowsGraphicsCaptureHelperPackagingContract] (shared with
// `:recording-windows:verifyRecordingWindowsHelper`) so both verifiers enforce the same
// multi-file helper runtime contract.
// Windows arches expected when helpers are staged. Legacy -PprebuiltWindowsHelperPath is
// x64-only; release/dir/host builds need both arches.
val expectedWindowsHelperArches: List<String> = run {
    val windowsHelpersDir = providers.gradleProperty("prebuiltWindowsHelpersDir").isPresent
    val windowsHelperPathOnly =
        providers.gradleProperty("prebuiltWindowsHelperPath").isPresent && !windowsHelpersDir
    when {
        windowsHelperPathOnly -> listOf("x64")
        windowsHelpersDir || org.gradle.internal.os.OperatingSystem.current().isWindows ->
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES
        else -> emptyList()
    }
}

val expectedRecordingHelperEntriesByProject: Map<String, List<String>> =
    mutableMapOf<String, List<String>>().apply {
        val macOsExpected =
            providers.gradleProperty("prebuiltMacHelperPath").isPresent ||
                providers.gradleProperty("stubMacHelperForTesting").isPresent ||
                org.gradle.internal.os.OperatingSystem.current().isMacOsX
        if (macOsExpected) {
            // App-bundle layout (#190); bare native/macos/spectre-screencapture is rejected.
            put(
                ":recording-macos",
                listOf(
                    "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
                    "native/macos/SpectreCaptureHelper.app/Contents/Info.plist",
                    "native/macos/SpectreCaptureHelper.app/Contents/PkgInfo",
                    "native/macos/SpectreCaptureHelper.app/Contents/Resources/AppIcon.icns",
                ),
            )
        }
        if (expectedWindowsHelperArches.isNotEmpty()) {
            put(
                ":recording-windows",
                WindowsGraphicsCaptureHelperPackagingContract.requiredJarEntryPaths(
                    expectedWindowsHelperArches
                ),
            )
        }
        val currentOs = org.gradle.internal.os.OperatingSystem.current()
        val crossArchLinux =
            providers.gradleProperty("prebuiltLinuxHelpersDir").isPresent ||
                (currentOs.isLinux && providers.gradleProperty("allLinuxArches").isPresent)
        when {
            crossArchLinux -> {
                put(
                    ":recording-linux",
                    listOf(
                        "native/linux/x86_64/spectre-wayland-helper",
                        "native/linux/aarch64/spectre-wayland-helper",
                    ),
                )
            }
            currentOs.isLinux -> {
                val hostArch =
                    when (System.getProperty("os.arch").orEmpty().lowercase()) {
                        "amd64",
                        "x86_64",
                        "x64" -> "x86_64"
                        "aarch64",
                        "arm64" -> "aarch64"
                        else -> ""
                    }
                if (hostArch.isNotEmpty()) {
                    put(":recording-linux", listOf("native/linux/$hostArch/spectre-wayland-helper"))
                }
            }
        }
    }

val verifyMavenLocalPublication by tasks.registering {
    description =
        "Publishes Spectre library modules to mavenLocal and asserts each " +
            "module's artefact set (jar + sources + javadoc + POM + module) satisfies " +
            "Sonatype Central's gating, with platform helpers isolated in their runtime jars."
    group = "verification"

    dependsOn(publishedLibraryProjects.map { "$it:publishToMavenLocal" })

    // Capture all needed scalars into the closure so the action body doesn't reference
    // `project` / `rootProject` / `logger` at execution time. Gradle's configuration
    // cache rejects "script object references" — including the script-class logger and
    // top-level helper functions — so the doLast body sticks to plain Strings, Files,
    // Maps, and a self-contained walker over the parsed XML DOM.
    val groupPath = publishGroupId.replace('.', '/')
    val version = publishVersion
    val artifactIds = publishArtifactIds
    val repoRoot = file(mavenLocalRepoRoot)
    val expectedHelpersByProject = expectedRecordingHelperEntriesByProject
    val windowsArches = expectedWindowsHelperArches

    doLast {
        val errors = mutableListOf<String>()

        fun firstDirectChild(parent: Element, tag: String): Element? {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == tag) return node
            }
            return null
        }

        fun firstDirectChildText(parent: Element, tag: String): String? =
            firstDirectChild(parent, tag)?.textContent?.trim()

        for ((moduleName, artifactId) in artifactIds) {
            val moduleDir = repoRoot.resolve("$groupPath/$artifactId/$version")
            if (!moduleDir.isDirectory) {
                errors +=
                    "$moduleName: expected mavenLocal directory $moduleDir does not " +
                        "exist. Did publishToMavenLocal run?"
                continue
            }
            val baseName = "$artifactId-$version"
            val expectedArtefacts =
                mutableListOf(
                    "$baseName.jar",
                    "$baseName-sources.jar",
                    "$baseName-javadoc.jar",
                    "$baseName.pom",
                    "$baseName.module",
                )
            for (artefact in expectedArtefacts) {
                if (!moduleDir.resolve(artefact).isFile) {
                    errors += "$moduleName: missing $artefact in $moduleDir"
                }
            }
            // Note: `.md5` / `.sha1` checksums are deliberately NOT asserted here. Central
            // requires them, but `publishToMavenLocal` doesn't generate them — they're
            // produced by the `publishToMavenRepository` task family when uploading. The
            // remote Central upload will reject if they're missing; verifying their
            // presence locally would need a separate `publishAllPublicationsToCustomRepo`
            // wired to a file:// repo, which is more plumbing than the shape gating here
            // is worth.
            val pomFile = moduleDir.resolve("$baseName.pom")
            if (pomFile.isFile) {
                val doc =
                    DocumentBuilderFactory.newInstance()
                        .also {
                            it.isNamespaceAware = false
                            it.setFeature(
                                "http://apache.org/xml/features/disallow-doctype-decl",
                                true,
                            )
                        }
                        .newDocumentBuilder()
                        .parse(pomFile)
                val root = doc.documentElement
                for (tag in listOf("name", "description", "url")) {
                    if (firstDirectChildText(root, tag).isNullOrBlank()) {
                        errors +=
                            "$moduleName: POM is missing <$tag> (or it's blank) in " + pomFile.name
                    }
                }
                val licenseName =
                    firstDirectChild(root, "licenses")
                        ?.let { firstDirectChild(it, "license") }
                        ?.let { firstDirectChildText(it, "name") }
                if (licenseName.isNullOrBlank()) {
                    errors += "$moduleName: POM has no <licenses><license><name> in " + pomFile.name
                }
                val scmUrl = firstDirectChild(root, "scm")?.let { firstDirectChildText(it, "url") }
                if (scmUrl.isNullOrBlank()) {
                    errors += "$moduleName: POM has no <scm><url> in ${pomFile.name}"
                }
                val developerId =
                    firstDirectChild(root, "developers")
                        ?.let { firstDirectChild(it, "developer") }
                        ?.let { firstDirectChildText(it, "id") }
                if (developerId.isNullOrBlank()) {
                    errors +=
                        "$moduleName: POM has no <developers><developer><id> in " + pomFile.name
                }
            }
            for (jarSuffix in listOf(".jar", "-sources.jar")) {
                val jar = moduleDir.resolve("$baseName$jarSuffix")
                if (!jar.isFile) continue
                val entriesInJar = mutableSetOf<String>()
                val entrySizesInJar = mutableMapOf<String, Long>()
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        entriesInJar += entry.name
                        entrySizesInJar[entry.name] = entry.size
                    }
                }
                if (jarSuffix == "-sources.jar") {
                    val nativeSources = entriesInJar.filter { it.startsWith("native/") }
                    if (nativeSources.isNotEmpty()) {
                        errors +=
                            "$moduleName: sources jar (${jar.name}) must not contain native " +
                                "helper resources: ${nativeSources.sorted().joinToString()}"
                    }
                }
                if (jarSuffix == ".jar" && moduleName == ":recording") {
                    val nativeEntries = entriesInJar.filter { it.startsWith("native/") }
                    if (nativeEntries.isNotEmpty()) {
                        errors +=
                            ":recording: API jar (${jar.name}) must not contain native helper " +
                                "resources: ${nativeEntries.sorted().joinToString()}"
                    }
                }
                if (jarSuffix == ".jar") {
                    if (
                        moduleName == ":recording-windows" &&
                            expectedHelpersByProject.containsKey(":recording-windows")
                    ) {
                        // Full multi-file contract (fixed required set + deps.json closure),
                        // shared with `:recording-windows:verifyRecordingWindowsHelper`.
                        ZipFile(jar).use { zip ->
                            for (gap in
                                WindowsGraphicsCaptureHelperPackagingContract.validateZip(
                                    zip,
                                    windowsArches,
                                )) {
                                errors += "$moduleName: published jar (${jar.name}): $gap"
                            }
                        }
                    } else {
                        for (path in expectedHelpersByProject[moduleName].orEmpty()) {
                            if (path !in entriesInJar) {
                                errors +=
                                    "$moduleName: published jar (${jar.name}) is missing " +
                                        "expected helper entry `$path`"
                            } else if ((entrySizesInJar[path] ?: 0L) <= 0L) {
                                errors +=
                                    "$moduleName: published jar (${jar.name}) has empty " +
                                        "helper entry `$path`"
                            }
                        }
                    }
                }
            }
            if (moduleName == ":agent-runtime") {
                val runtimeJar = moduleDir.resolve("$baseName.jar")
                if (runtimeJar.isFile) {
                    JarFile(runtimeJar).use { jar ->
                        val manifest = jar.manifest
                        val attributes = manifest?.mainAttributes
                        val agentClass = attributes?.getValue("Agent-Class")
                        val premainClass = attributes?.getValue("Premain-Class")
                        val expectedAgentClass = "dev.sebastiano.spectre.agent.runtime.SpectreAgent"
                        if (agentClass != expectedAgentClass) {
                            errors +=
                                ":agent-runtime: jar (${runtimeJar.name}) has Agent-Class " +
                                    "'$agentClass', expected '$expectedAgentClass'"
                        }
                        if (premainClass != expectedAgentClass) {
                            errors +=
                                ":agent-runtime: jar (${runtimeJar.name}) has Premain-Class " +
                                    "'$premainClass', expected '$expectedAgentClass'"
                        }
                        val forbiddenPrefixes =
                            listOf(
                                "androidx/compose/",
                                "org/jetbrains/compose/",
                                "org/jetbrains/skiko/",
                                "dev/sebastiano/spectre/core/",
                                "kotlin/Pair.class",
                                "kotlinx/coroutines/",
                            )
                        val leaks =
                            jar.entries()
                                .asSequence()
                                .map { it.name }
                                .filter { entry -> forbiddenPrefixes.any { entry.startsWith(it) } }
                        val leakList = leaks.toList()
                        if (leakList.isNotEmpty()) {
                            errors +=
                                ":agent-runtime: jar (${runtimeJar.name}) contains forbidden " +
                                    "classes: ${leakList.sorted().joinToString()}"
                        }
                    }
                }
            }
        }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "verifyMavenLocalPublication failed:\n" + errors.joinToString("\n") { "  - $it" }
            )
        }
        println(
            "verifyMavenLocalPublication: every module's artefact set looks Central-ready " +
                "(${artifactIds.size} modules, version=$version)."
        )
    }
}
