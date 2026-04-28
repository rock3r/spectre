import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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

dependencies {
    // The plugin demonstrates Spectre running INSIDE the IDE process. The in-process automator
    // reads the Compose semantics tree from the tool window panel without leaving the JVM.
    implementation(projects.core)

    // Compose Multiplatform — the tool window content is a regular `@Composable`. We pull
    // `compose.desktop.currentOs` to match what the bundled `ide-laf-bridge` was compiled
    // against; using `compose.foundation` directly would produce a duplicate-runtime classpath.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    detektPlugins(libs.compose.rules.detekt)

    // Jewel's IDE-LaF bridge ships the `ToolWindow.addComposeTab` helper + `SwingBridgeTheme`
    // that turn the IDE's L&F tokens into Compose theme values. Without it, the tool window
    // content would render with hard-coded Material colours that clash with the IDE skin.
    // `jewel-ui` is the matching component module (`Text`, `DefaultButton`, etc.) — the
    // bridge artifact doesn't pull it in transitively (Jewel intends consumers to opt into
    // the component set explicitly).
    implementation(libs.jewel.ideLafBridge) {
        // Strip Jewel's transitive Compose Multiplatform — the IntelliJ Platform classpath
        // already exposes a Compose runtime and the duplicate causes "two compose runtimes"
        // errors at startup. Same exclusion applies to kotlinx.coroutines (the IDE bundles it).
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    implementation(libs.jewel.ui) {
        exclude(group = "org.jetbrains.compose")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    intellijPlatform {
        // `intellijIdeaCommunity` resolves to `com.jetbrains.intellij.idea:ideaIC:<version>`,
        // which is what's published on the JetBrains IntelliJ Repository releases. The bare
        // `intellijIdea(...)` overload tries to resolve `idea:idea`, which doesn't exist there.
        // The plugin's coordinate-translation path needs a literal `String`, not a `Provider`,
        // so we resolve the catalog version eagerly here rather than passing the Provider — that
        // keeps the version centralised in `libs.versions.toml` while still picking the right
        // overload.
        intellijIdeaCommunity(libs.versions.intellijIdea.get())
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit4)
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
