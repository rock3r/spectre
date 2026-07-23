import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.shadow)
    // Not published as a stand-alone Central artifact for the spike — nested inside
    // spectre-agent-runtime as META-INF/spectre/inject-runtime.jar. POM fields remain for
    // future publication if M5 promotes injection.
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Full core surface for inject bootstrap. Compose types stay external: the shadow jar
    // excludes Compose / Skiko so the target JVM's classloaders supply them at runtime.
    implementation(projects.core)

    detektPlugins(libs.compose.rules.detekt)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Marker source so the module has a compile output even though payload is mostly core.
tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
    enabled = true
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("spectre-agent-inject-runtime")
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Relocate kotlinx so IDE-shipped coroutines cannot collide with injected core.
    relocate("kotlinx.coroutines", "dev.sebastiano.spectre.inject.relocated.kotlinx.coroutines")
    // atomicfu is pulled transitively; relocate to keep inject payload self-contained.
    relocate("kotlinx.atomicfu", "dev.sebastiano.spectre.inject.relocated.kotlinx.atomicfu")

    dependencies {
        // Compose / Skiko must come from the target — never shade.
        exclude(dependency("org.jetbrains.compose.runtime:.*"))
        exclude(dependency("org.jetbrains.compose.foundation:.*"))
        exclude(dependency("org.jetbrains.compose.ui:.*"))
        exclude(dependency("org.jetbrains.compose.animation:.*"))
        exclude(dependency("org.jetbrains.compose.material3:.*"))
        exclude(dependency("org.jetbrains.compose:.*"))
        exclude(dependency("org.jetbrains.skiko:.*"))
        // Kotlin stdlib is always on the target JVM (and IDE); keep out of inject jar.
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    // Belt-and-suspenders: drop any Compose/Skiko/stdlib entries that slip past dependency exclude.
    exclude("androidx/compose/**")
    exclude("org/jetbrains/compose/**")
    exclude("org/jetbrains/skiko/**")
    exclude("kotlin/**")
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

val verifyInjectRuntimeJarContents by tasks.registering {
    description =
        "Asserts inject runtime jar carries spectre-core + relocated kotlinx and never " +
            "bundles Compose/Skiko."
    group = "verification"

    val jarFile = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    dependsOn(jarFile)
    inputs.file(jarFile)

    doLast {
        val jar = jarFile.get().asFile
        ZipFile(jar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            fun has(prefix: String) = names.any { it.startsWith(prefix) }

            require(has("dev/sebastiano/spectre/core/ComposeAutomator")) {
                "Inject jar missing ComposeAutomator class"
            }
            require(has("dev/sebastiano/spectre/inject/relocated/kotlinx/coroutines/")) {
                "Inject jar missing relocated kotlinx.coroutines " +
                    "(got entries sample: ${names.filter { "coroutines" in it }.take(10)})"
            }
            val forbidden =
                listOf(
                    "androidx/compose/",
                    "org/jetbrains/compose/",
                    "org/jetbrains/skiko/",
                    "kotlinx/coroutines/", // must be relocated, not original package
                )
            val leaks = names.filter { entry -> forbidden.any { entry.startsWith(it) } }
            require(leaks.isEmpty()) {
                "Inject jar contains forbidden entries:\n" +
                    leaks.sorted().joinToString("\n") { "  - $it" }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyInjectRuntimeJarContents, "shadowJar") }

// Default assemble uses shadow jar as the consumable artifact.
configurations.named("default") {
    // Prefer shadow output when other modules depend on this project as files.
}

artifacts {
    add("default", tasks.named<ShadowJar>("shadowJar")) { builtBy(tasks.named("shadowJar")) }
}
