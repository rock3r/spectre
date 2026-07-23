import java.io.File
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import org.gradle.jvm.tasks.Jar as JvmJar

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(projects.agent)

    detektPlugins(libs.compose.rules.detekt)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

val runtimeClasspath = configurations.named("runtimeClasspath")

// Nested inject payload (#209): relocated core + kotlinx, no Compose. Packaged as a resource
// so the thin agent-runtime still forbids exploded core/ classes (verifyAgentRuntimeJarContents).
val injectRuntimeJarTask = project(":agent-inject-runtime").tasks.named("shadowJar")
val injectRuntimeJarFile = injectRuntimeJarTask.map { task -> task.outputs.files.singleFile }

tasks.named<JvmJar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(injectRuntimeJarTask)
    inputs.file(injectRuntimeJarFile)

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

    from({ runtimeClasspath.get().filter(::includeInAgentRuntimeJar).map(::zipTree) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/INDEX.LIST")
    }

    // Nested jar resource — not exploded core classes.
    from(injectRuntimeJarFile) {
        into("META-INF/spectre")
        rename { "inject-runtime.jar" }
    }
}

val verifyAgentRuntimeJarContents by tasks.registering {
    description =
        "Asserts the agent runtime JAR is loadable by the Attach API and stays thin: no " +
            "exploded Compose, Skiko, Kotlin stdlib, coroutines, or Spectre core classes. " +
            "Nested META-INF/spectre/inject-runtime.jar is required for #209 injection."
    group = "verification"

    val jarFile = tasks.named<JvmJar>("jar").flatMap { it.archiveFile }
    dependsOn(jarFile)
    inputs.file(jarFile)

    doLast {
        val jar = jarFile.get().asFile
        ZipFile(jar).use { zip ->
            val manifest =
                zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).use { input ->
                    Manifest(input)
                }
            val attrs = manifest.mainAttributes
            val expectedAgentClass = "dev.sebastiano.spectre.agent.runtime.SpectreAgent"
            require(attrs.getValue("Agent-Class") == expectedAgentClass) {
                "Agent runtime jar is missing Agent-Class=$expectedAgentClass"
            }
            require(attrs.getValue("Premain-Class") == expectedAgentClass) {
                "Agent runtime jar is missing Premain-Class=$expectedAgentClass"
            }

            val injectEntry = zip.getEntry("META-INF/spectre/inject-runtime.jar")
            require(injectEntry != null && injectEntry.size > 0L) {
                "Agent runtime jar missing nested META-INF/spectre/inject-runtime.jar (#209)"
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
                zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { entry -> forbiddenPrefixes.any { entry.startsWith(it) } }
            val leakList = leaks.toList()
            require(leakList.isEmpty()) {
                "Agent runtime jar contains forbidden classes:\n" +
                    leakList.sorted().joinToString("\n") { "  - $it" }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyAgentRuntimeJarContents) }

private fun includeInAgentRuntimeJar(file: File): Boolean {
    val name = file.name
    return when {
        name.startsWith("kotlin-stdlib") -> false
        name.startsWith("kotlin-reflect") -> false
        name.startsWith("kotlinx-coroutines") -> false
        name.startsWith("annotations-") -> false
        // Never explode the inject payload into the agent-runtime root.
        name.startsWith("spectre-agent-inject-runtime") -> false
        name.startsWith("agent-inject-runtime") -> false
        else -> true
    }
}
