plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // The IntelliJ Platform plugin is loaded by the settings-level
    // `org.jetbrains.intellij.platform.settings` companion in settings.gradle.kts. Apply it
    // here without a version so Gradle reuses the already-loaded classpath rather than trying
    // to resolve a second copy.
    id("org.jetbrains.intellij.platform")
}

// IntelliJ requires JVM 21 from 2024.2+, and the project's other modules are on 21 too. Pin to
// match so the plugin classpath is byte-for-byte compatible with the other Spectre modules
// (`:core` etc.) that the plugin pulls in as a regular `implementation` dependency.
kotlin { jvmToolchain(21) }

// Repositories are inherited from settings.gradle.kts (Maven Central, Google, IntelliJ Platform
// + JetBrains Skiko mirror) — the IntelliJ Platform settings plugin makes that work.

// IDE 2026.1.1 bundles its own Jewel + Compose + skiko as platform modules (see
// `<idea-home>/lib/intellij.libraries.{skiko,compose.*}.jar` and
// `intellij.platform.jewel.*.jar`). The IDE loads its bundled skiko native dylib at startup;
// JNI native libraries are loaded once per JVM, so the plugin MUST use the IDE's own skiko
// Java classes (which match the loaded dylib's exported symbols) rather than ship a different
// version from Maven Central.
//
// Previously bundling Compose Multiplatform 1.10.3 (skiko 0.9.37.4) with the plugin crashed
// with `UnsatisfiedLinkError: MetalApiKt.getAdapterMaxTextureSize` because Compose's newer
// skiko Java classes call JNI symbols that don't exist in the IDE's older bundled dylib.
//
// Excluding skiko entirely (without exposing the IDE's) crashed earlier with
// `NoClassDefFoundError: SkiaLayerAnalytics` because the IDE's `intellij.libraries.skiko.jar`
// isn't on plugin classloaders by default.
//
// The canonical fix (per JetBrains' intellij-platform-compose-plugin-template) is
// `bundledModule(...)` references: the IntelliJ Platform Gradle plugin then exposes those
// IDE-bundled jars to the plugin's classloader without packaging them. We therefore drop the
// external Maven Compose/Jewel/skiko deps in favour of the bundled modules.
dependencies {
    // The plugin demonstrates Spectre running INSIDE the IDE process. The in-process automator
    // reads the Compose semantics tree from the tool window panel without leaving the JVM.
    implementation(projects.core)

    // Compose APIs are needed to compile the tool-window content (`@Composable`, `Modifier`,
    // `testTag`, etc.). At runtime the IDE provides them via the bundled
    // `intellij.libraries.compose.*` modules (declared below); these `compileOnly` deps
    // therefore must NOT ship in the plugin distribution — they exist only to satisfy the
    // Kotlin compiler. The `:core` module is `api(compose.ui)` and is already declared as
    // `implementation(projects.core)` above, but its transitive Compose runtime is also
    // stripped from the plugin's runtimeClasspath via the configuration-level exclusion below
    // so the IDE's bundled Compose wins at runtime.
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.ui)
    detektPlugins(libs.compose.rules.detekt)

    // Jewel APIs (compileOnly): `addComposeTab`, `Text`, `DefaultButton`. At runtime the IDE
    // provides them via the bundled `intellij.platform.jewel.*` modules.
    compileOnly(libs.jewel.ideLafBridge) {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "com.jetbrains.intellij.platform")
    }
    compileOnly(libs.jewel.ui) {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    intellijPlatform {
        // From IntelliJ 2025.3 (253) onward, `ideaIC` is no longer published — see plugin
        // diagnostic "use: intellijIdea(...)". Resolves to the unified `idea:idea:<version>`
        // artifact (Ultimate flavour). The plugin's coordinate-translation path needs a
        // literal `String`, hence the eager `.get()`.
        intellijIdea(libs.versions.intellijIdea.get())

        // Expose the IDE's bundled Compose + skiko + Jewel modules to the plugin's classloader.
        // `composeUI()` is the JetBrains-blessed entry point — see
        // https://github.com/JetBrains/intellij-platform-compose-plugin-template. It wires the
        // right set of bundled modules and is paired with `<depends>com.intellij.modules.compose`
        // on the descriptor side. Per-module `bundledModule("intellij.libraries.skiko")` etc. are
        // NOT plugin IDs and cannot be referenced from `<depends>` — that route fails the IDE's
        // plugin loader with "requires plugin 'intellij.libraries.skiko' to be installed".
        @Suppress("UnstableApiUsage") composeUI()
    }
}

// Belt-and-braces: ensure neither Compose Multiplatform nor skiko ever lands in the plugin's
// runtime distribution via `:core`'s transitive `api(compose.ui)`. The IDE's bundled modules
// (declared above) are the single source of truth at runtime; duplicate Compose/skiko jars on
// the plugin's `lib/` would re-introduce the dual-ABI crash.
configurations
    .matching { it.name == "runtimeClasspath" || it.name.startsWith("intellijPlatform") }
    .configureEach {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.skiko")
    }

intellijPlatform {
    pluginConfiguration {
        id = "dev.sebastiano.spectre.sample"
        name = "Spectre IDE Sample"
        version = "0.0.0-DEV"
        // No marketplace publishing — this plugin exists only as a validation surface for #13.
        // Description is the bare minimum the verifier accepts; we deliberately don't generate
        // it from a README the way the upstream template does.
        description = "Sample IntelliJ plugin used to validate Spectre against IDE-hosted Compose."
        vendor { name = "Spectre" }
    }

    // The verifier wants <idea-version> bounds. Pin upper to the same major as the dev IDE so a
    // future IDE bump doesn't silently drop us; we don't ship this anywhere so range fidelity
    // beyond "the IDE we develop against" is wasted complexity.
    pluginVerification {
        // No-op; keep it explicit so the absence is obvious in code review. Verifier runs
        // are opt-in via `./gradlew :sample-intellij-plugin:verifyPlugin`.
    }
}

// Gradle's idea plugin emits the synthetic `:idea` task alongside `runIde` — we want the
// developer entry point to be discoverable.
tasks.named("runIde") { group = "verification" }

// `buildPlugin` (and its dependencies) generate the plugin distribution zip. Strip from `check`
// so the regular CI loop stays fast — a contributor opting into the plugin runs `runIde`
// explicitly. Verification of the plugin descriptor still happens via `verifyPluginStructure`.
tasks.named("check") { dependsOn("verifyPluginStructure") }
