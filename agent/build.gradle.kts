import javax.inject.Inject
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    // Wire-protocol serialization. CBOR is the only kotlinx-serialization format used by
    // the agent transport; JSON is intentionally not on the agent runtime classpath.
    implementation(libs.kotlinx.serialization.cbor)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(projects.core)
    // Shared automator contract corpus + capability matrix (#198).
    testImplementation(projects.testing)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutines.test)

    // `:agent-test-fixture` is a non-publishable Compose Desktop app whose `main()` puts
    // up a window with known testTags. `AgentAttachIntegrationTest` spawns it as a child
    // JVM via `java -cp ... ComposeFixtureMainKt` to exercise the agent against real
    // semantics-tree data (non-empty windows, findByTestTag matches, DTO bounds, click).
    // Pulled in as a test dep so its classes + the Compose runtime end up on the test
    // JVM's `java.class.path`, which we then forward to the spawned child.
    testImplementation(projects.agentTestFixture)
}

val spikeSourceSet = sourceSets.create("spike")

tasks.withType<Jar>().configureEach {
    includeEmptyDirs = false
    exclude("dev/sebastiano/spectre/agent/spike/**")
}

abstract class AttachSpikeArgumentProvider @Inject constructor() : CommandLineArgumentProvider {
    @get:org.gradle.api.tasks.Input abstract val pid: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val runtimeJar: org.gradle.api.file.RegularFileProperty

    override fun asArguments(): Iterable<String> {
        val targetPid =
            pid.orNull
                ?: throw GradleException(
                    "attachSpike requires -Ppid=<target JVM pid>. Find the target via " +
                        "`jps` (look for the main class you want to attach to)."
                )
        return listOf(targetPid, runtimeJar.get().asFile.absolutePath)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Expose the freshly-built agent runtime JAR to AgentAttachIntegrationTest. The test reads
    // this system property and skips itself (via JUnit `assumeFalse`) if it's not set.
    // Wired to `:agent-runtime:jar` so tests use the same Java-agent payload that publishing
    // exposes as `spectre-agent-runtime`.
    val runtimeJarFile =
        project(":agent-runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(runtimeJarFile)
    dependsOn(runtimeJarFile)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Ddev.sebastiano.spectre.agent.runtimeJar=${runtimeJarFile.get().asFile.absolutePath}"
            )
        }
    )
}

// ---------------------------------------------------------------------------------------
// `attachSpike` — manual verification entry point. Wraps a standalone helper from the
// non-published `spike` source set so a contributor can run
// `./gradlew :agent:attachSpike -Ppid=<PID>` from a worktree and see the agent attach to a
// running Spectre-instrumented JVM. The agent's diagnostic stderr lands in the *target* JVM's
// console, not this task's output.
//
// This task is NOT wired into `:check`; it exists for human-driven verification.
// ---------------------------------------------------------------------------------------

tasks.register<JavaExec>("attachSpike") {
    description =
        "Attaches the Spectre agent runtime JAR to a running JVM identified " +
            "by -Ppid=<pid>. Output appears in the target JVM's stderr."
    group = "verification"
    val runtimeJarFile =
        project(":agent-runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(runtimeJarFile, spikeSourceSet.classesTaskName)

    classpath = spikeSourceSet.runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.agent.spike.AttachSpike")
    argumentProviders.add(
        objects.newInstance<AttachSpikeArgumentProvider>().apply {
            pid.set(providers.gradleProperty("pid"))
            runtimeJar.set(runtimeJarFile)
        }
    )
}
