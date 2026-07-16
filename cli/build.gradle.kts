import dev.sebastiano.spectre.build.CreateCliRuntimeImage
import dev.sebastiano.spectre.build.PatchStartScripts
import dev.sebastiano.spectre.build.VerifyCliDistributionZip
import dev.sebastiano.spectre.build.VerifyCliRuntimeImage
import dev.sebastiano.spectre.build.VerifyCliShadowJar
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
}

application {
    applicationName = "spectre"
    mainClass = "dev.sebastiano.spectre.cli.SpectreCliKt"
}

tasks.shadowJar {
    val agentRuntimeJar = project(":agent-runtime").tasks.named<Jar>("jar")
    dependsOn(agentRuntimeJar)
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    // Keep Spectre's entrypoints readable and callable by name while R8 removes dead code from
    // the merged third-party runtime. The nested agent runtime is copied as an opaque resource
    // below, so its reflection-based attach contract stays outside the shrinker's scope.
    minimize {
        r8 {
            keepRules.addAll(
                "-dontobfuscate",
                "-dontoptimize",
                // kotlin-logging ships adapters for optional logging backends. The CLI does not
                // bundle Logback, so R8 must not treat those unused adapter references as an
                // unresolved runtime dependency.
                "-dontwarn ch.qos.logback.classic.**",
                "-keepattributes SourceFile,LineNumberTable",
                "-keep class dev.sebastiano.spectre.cli.** { *; }",
            )
        }
    }
    from(agentRuntimeJar.flatMap { it.archiveFile }) {
        into("spectre")
        rename { "agent-runtime.jar" }
    }
}

val patchShadowStartScripts =
    tasks.register<PatchStartScripts>("patchShadowStartScripts") {
        dependsOn(tasks.named("startShadowScripts"))
        unixScript.set(layout.buildDirectory.file("scriptsShadow/spectre"))
        windowsScript.set(layout.buildDirectory.file("scriptsShadow/spectre.bat"))
    }

tasks.named<CreateStartScripts>("startShadowScripts") {
    // The patch task intentionally modifies this task's generated output before it is zipped.
    // Regenerate it on every distribution build so a changed preflight can never reuse stale
    // launcher contents from a previous build.
    outputs.upToDateWhen { false }
}

tasks.named<Zip>("shadowDistZip") {
    archiveFileName.set("spectre-cli-${project.version}.zip")
    dependsOn(patchShadowStartScripts)
    // PatchStartScripts mutates the generated launcher after Shadow has assembled its copy spec.
    // Recreate the archive so direct release builds cannot reuse an earlier unpatched ZIP.
    outputs.upToDateWhen { false }
}

val verifyCliShadowJar =
    tasks.register<VerifyCliShadowJar>("verifyCliShadowJar") {
        dependsOn(tasks.shadowJar)
        artifact.set(tasks.shadowJar.flatMap { it.archiveFile })
    }

val cliRuntimeImage = layout.buildDirectory.dir("runtime/cli")
val javaToolchains = extensions.getByType<JavaToolchainService>()
val jlinkBinary =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { launcher ->
            launcher.metadata.installationPath.file(
                if (isWindows()) "bin/jlink.exe" else "bin/jlink"
            )
        }

val createCliRuntimeImage =
    tasks.register<CreateCliRuntimeImage>("createCliRuntimeImage") {
        description = "Creates the host jlink runtime image for the Spectre CLI bundle."
        group = "distribution"
        jlinkExecutable.set(jlinkBinary)
        runtimeImage.set(cliRuntimeImage)
    }

tasks.named<Zip>("shadowDistZip") {
    dependsOn(createCliRuntimeImage)
    from(cliRuntimeImage) { into("spectre-cli-${project.version}/runtime") }
}

val verifyCliRuntimeImage =
    tasks.register<VerifyCliRuntimeImage>("verifyCliRuntimeImage") {
        dependsOn(createCliRuntimeImage)
        runtimeImage.set(cliRuntimeImage)
        artifact.set(tasks.shadowJar.flatMap { it.archiveFile })
    }

val verifyCliDistributionZip =
    tasks.register<VerifyCliDistributionZip>("verifyCliDistributionZip") {
        dependsOn(tasks.named("shadowDistZip"))
        artifact.set(tasks.named<Zip>("shadowDistZip").flatMap { it.archiveFile })
    }

tasks.assemble { dependsOn(verifyCliShadowJar, verifyCliRuntimeImage) }

tasks.check { dependsOn(verifyCliShadowJar, verifyCliRuntimeImage, verifyCliDistributionZip) }

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(projects.agent)
    implementation(projects.recording)
    runtimeOnly(projects.agentRuntime)
    runtimeOnly(projects.recordingLinux)
    runtimeOnly(projects.recordingMacos)
    runtimeOnly(projects.recordingWindows)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk.server)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(projects.agentTestFixture)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty(
        "spectre.cli.testRuntimeClasspath",
        sourceSets.test.get().runtimeClasspath.asPath,
    )
    providers.systemProperty("spectre.cli.distributionExecutable").orNull?.let { executable ->
        systemProperty("spectre.cli.distributionExecutable", executable)
    }
}
