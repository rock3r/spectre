import java.util.jar.Attributes
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // GradleUp Shadow — builds the fat agent runtime JAR (`spectre-agent-runtime-*-all.jar`)
    // that ends up loaded into target JVMs via `VirtualMachine.loadAgent(...)`. Manifest
    // attributes (Agent-Class / Premain-Class) are configured on the `shadowJar` task below.
    alias(libs.plugins.shadow)
    // NB: `mavenPublish` is intentionally NOT applied. Per the issue #153 workshop plan
    // (Q-1 resolution), `:agent` is unpublished for v1 — developers consume it via
    // `mavenLocal` or a direct project dependency until the UX stabilises. A follow-up
    // issue tracks Central publishing.
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    // `:core` is `compileOnly` by design: the agent module references Spectre's automator
    // types (`ComposeAutomator`, `AutomatorNode`, …) at compile time so we can reflect on
    // them in a type-checked way, but the runtime instances live in the *target* JVM's
    // classloader after attach. Bundling `:core` into the agent fat JAR would defeat the
    // thin-agent design — see plan UC-1 / E-1 in
    // `.plans/2026-05-22-issue-153-agent-attach-workshop.md`. The fat-jar assertion task
    // below enforces this.
    compileOnly(projects.core)
    compileOnly(libs.kotlinx.coroutines.core)

    // Wire-protocol serialization. CBOR is the only kotlinx-serialization format used by
    // the agent transport; JSON is intentionally not on the agent runtime classpath.
    implementation(libs.kotlinx.serialization.cbor)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(projects.core)
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Expose the freshly-built fat agent JAR to AgentAttachIntegrationTest. The test reads
    // this system property and skips itself (via JUnit `assumeFalse`) if it's not set.
    // Wired through the shadowJar provider so the test sees the same artifact that
    // `:agent:check` produced.
    val shadowJarFile =
        tasks
            .named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
            .flatMap { it.archiveFile }
    inputs.file(shadowJarFile)
    dependsOn("shadowJar")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Ddev.sebastiano.spectre.agent.runtimeJar=${shadowJarFile.get().asFile.absolutePath}"
            )
        }
    )
}

// ---------------------------------------------------------------------------------------
// Shadow fat-jar configuration for the agent runtime artifact.
//
// `shadowJar` produces `agent/build/libs/agent-<version>-all.jar`. The manifest carries
// the Java Agent attributes; `Can-Redefine-Classes` / `Can-Retransform-Classes` are
// explicitly `false` because Spectre's agent only bootstraps an in-target IPC server —
// it does not instrument bytecode.
//
// The fat JAR includes:
//   - the module's compiled classes (`dev.sebastiano.spectre.agent.*`)
//   - kotlinx-serialization-cbor + kotlinx-serialization-core + kotlinx-io
//
// It must NOT include (verified by `verifyAgentJarContents`):
//   - `dev.sebastiano.spectre.core.*`  (`compileOnly` keeps it out)
//   - `androidx.compose.*` / `org.jetbrains.compose.*`  (Compose is not declared)
//   - `org.jetbrains.skiko.*`           (Skiko is not declared)
//   - `kotlin.*` from `kotlin-stdlib`   (resolved from target — see exclusion below)
//   - `kotlinx.coroutines.*`            (declared compileOnly above)
// ---------------------------------------------------------------------------------------

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    manifest {
        attributes(
            mapOf(
                Attributes.Name.MANIFEST_VERSION.toString() to "1.0",
                "Agent-Class" to "dev.sebastiano.spectre.agent.runtime.SpectreAgent",
                "Premain-Class" to "dev.sebastiano.spectre.agent.runtime.SpectreAgent",
                "Can-Redefine-Classes" to "false",
                "Can-Retransform-Classes" to "false",
                "Can-Set-Native-Method-Prefix" to "false",
            )
        )
    }
    // The Kotlin stdlib is provided by the target JVM (Spectre `:core` pulls it in;
    // Compose-instrumented apps already have it). Bundling our copy risks classloader
    // surprises if the target has a different version. Same logic for coroutines: declared
    // `compileOnly` above so it isn't pulled in here, but the exclude is defence-in-depth.
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
    // Minimize the JAR; agents should be small to keep load-time cost low.
    minimize {
        // kotlinx-serialization-cbor needs serializers discovered reflectively; keep
        // them all rather than risking minimize stripping a transitively-used type.
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-cbor:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core:.*"))
    }
}

// Verification task that asserts the thin-agent invariants on the produced fat JAR.
// Runs as part of `:agent:check` so regressions are caught before any push. Plan
// requirement AC-1 / R-2.
val verifyAgentJarContents by tasks.registering {
    description =
        "Asserts the agent fat JAR contains only the thin-agent payload: no Compose, " +
            "Skiko, Kotlin stdlib, or Spectre core classes leaked in via Shadow."
    group = "verification"
    val shadowJarTask = tasks.named("shadowJar")
    dependsOn(shadowJarTask)
    val jarFile = shadowJarTask.map { (it.outputs.files.singleFile) }
    inputs.file(jarFile)
    doLast {
        val jar = jarFile.get()
        val forbiddenPrefixes =
            listOf(
                "androidx/compose/",
                "org/jetbrains/compose/",
                "org/jetbrains/skiko/",
                "dev/sebastiano/spectre/core/",
                "kotlin/Pair.class", // sentinel: any class directly under `kotlin/` means
                // the stdlib leaked in.
            )
        val leaks = mutableListOf<String>()
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (forbiddenPrefixes.any { entry.name.startsWith(it) }) {
                    leaks += entry.name
                }
            }
        }
        if (leaks.isNotEmpty()) {
            throw GradleException(
                "Agent fat JAR contains forbidden classes — the thin-agent invariant " +
                    "is broken. See `.plans/2026-05-22-issue-153-agent-attach-workshop.md` " +
                    "UC-1 / E-1. Leaks:\n" +
                    leaks.sorted().joinToString("\n") { "  - $it" }
            )
        }
        logger.lifecycle(
            "verifyAgentJarContents: agent fat JAR is thin (${jar.name}, " +
                "${jar.length() / 1024} KiB)."
        )
    }
}

tasks.named("check") { dependsOn(verifyAgentJarContents) }

// ---------------------------------------------------------------------------------------
// `attachSpike` — manual M-1 verification entry point. Wraps the standalone `AttachSpike`
// main so a contributor can run `./gradlew :agent:attachSpike -Ppid=<PID>` from a worktree
// and see the agent attach to a running Spectre-instrumented JVM. The agent's diagnostic
// stderr lands in the *target* JVM's console, not this task's output.
//
// This task is NOT wired into `:check`. It exists for human-driven verification while M-1
// is the active milestone. Automated cross-JVM attach tests land in M-7/M-8.
// ---------------------------------------------------------------------------------------

tasks.register<JavaExec>("attachSpike") {
    description =
        "M-1 verification: attaches the Spectre agent fat JAR to a running JVM identified " +
            "by -Ppid=<pid>. Output appears in the target JVM's stderr."
    group = "verification"
    dependsOn("shadowJar")

    // The pid is taken per-invocation via `-Ppid=<pid>`, which would invalidate the
    // configuration cache on every run anyway. Wrapping the args lookup in a
    // CommandLineArgumentProvider SAM also tries to capture the build-script class,
    // which the cache can't serialize. Opting this task out is honest about its nature
    // and simpler than the abstract-class workaround.
    notCompatibleWithConfigurationCache("attachSpike reads -Ppid=<pid> dynamically per invocation.")

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.sebastiano.spectre.agent.spike.AttachSpike")

    val shadowJarFile =
        tasks
            .named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
            .flatMap { it.archiveFile }

    doFirst {
        val pid =
            providers.gradleProperty("pid").orNull
                ?: throw GradleException(
                    "attachSpike requires -Ppid=<target JVM pid>. Find the target via " +
                        "`jps` (look for the main class you want to attach to)."
                )
        args = listOf(pid, shadowJarFile.get().asFile.absolutePath)
    }
}
