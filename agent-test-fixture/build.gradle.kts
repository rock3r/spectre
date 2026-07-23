plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // NOT applying `mavenPublish` — this module exists only as a target for
    // `:agent`'s integration tests. It must never be published.
}

kotlin { jvmToolchain(21) }

dependencies {
    // `:core` so the agent can find ComposeAutomator on the fixture's classpath. Compose
    // Desktop deps for the actual UI we put up. Stays minimal — one window, two nodes,
    // tagged for the integration test's assertions.
    implementation(projects.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    detektPlugins(libs.compose.rules.detekt)
}

// `./gradlew :agent-test-fixture:run` is intended for manual verification and for the
// launch-and-attach Gradle-path e2e (`LaunchAndAttachGradleIntegrationTest`). Direct
// ProcessBuilder spawns in other agent tests still set their own JVM flags.
// The `compose.desktop.application` block wires up the `run` task with the same JVM args
// Compose Desktop would inject for a packaged native distribution.
compose.desktop {
    application {
        mainClass = "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt"
        // So agent attach works without relying on JEP 451 stderr warnings when the
        // launch harness drives `:agent-test-fixture:run` (Gradle cannot inject JVM args
        // from outside the build).
        jvmArgs += listOf("-XX:+EnableDynamicAgentLoading", "-Djava.awt.headless=false")
    }
}
