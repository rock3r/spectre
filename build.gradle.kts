import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
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

tasks.named("check") { dependsOn("detekt", "ktfmtCheck") }

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    exclude(*generatedSourceExcludes)
}

subprojects {
    apply(plugin = "base")

    group = rootProject.group
    version = rootProject.version

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
    }

    tasks
        .matching { it.name.startsWith("ktfmtCheck") || it.name.startsWith("ktfmtFormat") }
        .configureEach { (this as? SourceTask)?.exclude(*generatedSourceExcludes) }

    // --- Maven Central publishing convention (#84) -------------------------------------------
    //
    // Library modules (:core, :server, :recording, :testing) apply `com.vanniktech.maven.publish`
    // in their own build scripts. This block fires only when the plugin is present, so samples
    // never accidentally pick up a `publish` task — the issue requires the artefact set to be
    // exactly the four library modules.
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
//   6. Asserts `:recording` stays API/common-only while `:recording-macos` and
//      `:recording-linux` carry the expected platform helper resources.
//
// Local invocation:
//   ./gradlew verifyMavenLocalPublication \
//       -PstubMacHelperForTesting \
//       -PallLinuxArches
//
// CI invocation (publish job, after downloading the artefacts):
//   ./gradlew verifyMavenLocalPublication \
//       -PprebuiltMacHelperPath=<path> \
//       -PprebuiltLinuxHelpersDir=<dir>
//
// Verification only — it does not republish or mutate state. Safe to run repeatedly.
val publishedLibraryProjects =
    listOf(":core", ":server", ":recording", ":recording-macos", ":recording-linux", ":testing")

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

// Recording jar entries we expect to find — keep in sync with
// `VerifyBundledRecordingHelpers`'s expectations so a contradiction between the two surfaces
// as a duplicate failure rather than one silently passing.
val expectedRecordingHelperEntriesByProject: Map<String, List<String>> =
    mutableMapOf<String, List<String>>().apply {
        val macOsExpected =
            providers.gradleProperty("prebuiltMacHelperPath").isPresent ||
                providers.gradleProperty("stubMacHelperForTesting").isPresent ||
                org.gradle.internal.os.OperatingSystem.current().isMacOsX
        if (macOsExpected) {
            put(":recording-macos", listOf("native/macos/spectre-screencapture"))
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
                listOf(
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
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        entriesInJar += entries.nextElement().name
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
                    for (path in expectedHelpersByProject[moduleName].orEmpty()) {
                        if (path !in entriesInJar) {
                            errors +=
                                "$moduleName: published jar (${jar.name}) is missing " +
                                    "expected helper entry `$path`"
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
