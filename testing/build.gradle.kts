plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    // See `:core`'s build script for the rationale on the shared publish convention. Per-module
    // POM scalars live in this module's own `gradle.properties`.
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    api(projects.core)
    // Launch-and-attach JUnit surface (#208) composes over the experimental agent launch API.
    // Kept as `api` so consumers see `LaunchSpec` / `LaunchedSession` types from the extension.
    api(projects.agent)
    // Failure-video (#206) uses AutoRecorder / RecordingHandle; implementation so public API stays
    // FailureVideoConfig/Policy only (not a re-export of the full recording surface).
    implementation(projects.recording)
    // Public runSpectreTest surface exposes CoroutineScope / CoroutineContext; core only
    // implementation()-depends on coroutines, so re-export here for consumers of :testing.
    api(libs.kotlinx.coroutines.core)
    // JUnit 4 and JUnit Jupiter are compileOnly so consumers pick whichever they're already
    // using; the testing module itself only references their public APIs.
    compileOnly(libs.junit4)
    compileOnly(libs.junit5.api)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.junit4)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    // Lets us run the JUnit 4 rule via the JUnit Platform launcher in our own tests.
    testRuntimeOnly(libs.junit5.vintageEngine)
    // Runtime jar path for attach e2es that launch fixtures via the harness.
    testRuntimeOnly(projects.agentRuntime)
    // Do not testRuntimeOnly recording platform helpers here: unit tests inject fake
    // FailureVideoStarters and must not force cargo/Swift/.NET helper builds on check.
    // Live recording uses sample-desktop validation runtimeOnly and :agent testRuntimeOnly.
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
