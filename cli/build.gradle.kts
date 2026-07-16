import dev.sebastiano.spectre.build.PatchStartScripts
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Zip

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
}

tasks.assemble { dependsOn(tasks.shadowJar) }

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
