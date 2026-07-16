import dev.sebastiano.spectre.build.CreateCliRuntimeImage
import dev.sebastiano.spectre.build.PatchStartScripts
import dev.sebastiano.spectre.build.VerifyCliDistributionZip
import dev.sebastiano.spectre.build.VerifyCliRuntimeImage
import dev.sebastiano.spectre.build.VerifyCliShadowJar
import dev.sebastiano.spectre.build.VerifyRoastCliDistribution
import io.github.fourlastor.construo.Target
import io.github.fourlastor.construo.task.jvm.CreateRuntimeImageTask as ConstruoCreateRuntimeImageTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    application
    id("io.github.fourlastor.construo") version "2.1.0"
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

val javaToolchains = extensions.getByType<JavaToolchainService>()
val cliJlinkToolchain =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { launcher -> launcher.metadata.installationPath }

// Construo runs the host jlink against each downloaded target JDK's jmods and then packages
// Roast with that real target JVM. That preserves Spectre's attach/instrumentation support,
// which a GraalVM native image cannot provide.
construo {
    name.set("spectre")
    humanName.set("Spectre")
    mainClass.set(application.mainClass)
    jarTask.set("shadowJar")
    outputDir.set(layout.buildDirectory.dir("construo/distributions"))
    zipFolder.set("spectre-cli-${project.version}")
    jdkRoot.set(cliJlinkToolchain)

    jlink { modules.addAll("jdk.attach", "jdk.unsupported") }

    // Spectre's Roast bundle is a command-line tool, not a macOS UI application. Running its
    // JVM on the first thread blocks command invocation before the CLI can process its args.
    roast { runOnFirstThread.set(false) }

    targets {
        create<Target.Linux>("linuxX64") {
            architecture.set(Target.Architecture.X86_64)
            jdkUrl.set(temurinJdkUrl("x64", "linux", "tar.gz"))
        }
        create<Target.Linux>("linuxArm64") {
            architecture.set(Target.Architecture.AARCH64)
            jdkUrl.set(temurinJdkUrl("aarch64", "linux", "tar.gz"))
        }
        create<Target.MacOs>("macosX64") {
            architecture.set(Target.Architecture.X86_64)
            jdkUrl.set(temurinJdkUrl("x64", "mac", "tar.gz"))
            identifier.set("dev.sebastiano.spectre")
            buildNumber.set(project.version.toString())
            versionNumber.set(project.version.toString())
        }
        create<Target.MacOs>("macosArm64") {
            architecture.set(Target.Architecture.AARCH64)
            jdkUrl.set(temurinJdkUrl("aarch64", "mac", "tar.gz"))
            identifier.set("dev.sebastiano.spectre")
            buildNumber.set(project.version.toString())
            versionNumber.set(project.version.toString())
        }
        create<Target.Windows>("windowsX64") {
            architecture.set(Target.Architecture.X86_64)
            jdkUrl.set(temurinJdkUrl("x64", "windows", "zip"))
            useConsole.set(true)
        }
    }
}

// Construo 2.1.0 scans the unzipped JDK when serializing the configuration cache. The archive
// root is stable for our pinned Temurin release, so use a static input directory that Gradle can
// serialize and that the unzip task creates before jlink runs.
tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImageLinuxX64") {
    targetJdkRoot.set(targetJdkHome("linuxX64"))
}

tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImageLinuxArm64") {
    targetJdkRoot.set(targetJdkHome("linuxArm64"))
}

tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImageMacosX64") {
    targetJdkRoot.set(targetJdkHome("macosX64", macBundle = true))
}

tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImageMacosArm64") {
    targetJdkRoot.set(targetJdkHome("macosArm64", macBundle = true))
}

tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImageWindowsX64") {
    targetJdkRoot.set(targetJdkHome("windowsX64"))
}

// Construo strips native commands from its jlink image. Spectre must retain the `java` launcher:
// the foreground Roast process starts a separate daemon JVM for attach-capable commands.
preserveRuntimeJavaLauncher("linuxX64")

preserveRuntimeJavaLauncher("linuxArm64")

preserveRuntimeJavaLauncher("macosX64", macBundle = true)

preserveRuntimeJavaLauncher("macosArm64", macBundle = true)

preserveRuntimeJavaLauncher("windowsX64")

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
    archiveFileName.set("spectre-cli-${project.version}-${runtimeArchivePlatform()}.zip")
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
        targetOperatingSystem.set(runtimeOperatingSystem())
        targetArchitecture.set(runtimeArchitecture())
    }

tasks.named<Zip>("shadowDistZip") {
    dependsOn(createCliRuntimeImage)
    from(cliRuntimeImage) {
        include("bin/**", "lib/jspawnhelper")
        into("spectre-cli-${project.version}-${runtimeArchivePlatform()}/runtime")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(cliRuntimeImage) {
        exclude("bin/**", "lib/jspawnhelper")
        into("spectre-cli-${project.version}-${runtimeArchivePlatform()}/runtime")
    }
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

val verifyRoastCliDistribution =
    tasks.register<VerifyRoastCliDistribution>("verifyRoastCliDistribution") {
        val hostTarget = hostRoastTarget()
        dependsOn(tasks.named("package${hostTarget.taskSuffix}"))
        artifact.set(
            layout.buildDirectory.file("construo/distributions/spectre-${hostTarget.name}.zip")
        )
        launcherPath.set(hostTarget.launcherPath(project.version.toString()))
        runtimeJavaPath.set(hostTarget.runtimeJavaPath(project.version.toString()))
    }

tasks.assemble { dependsOn(verifyCliShadowJar, verifyCliRuntimeImage) }

tasks.check {
    dependsOn(
        verifyCliShadowJar,
        verifyCliRuntimeImage,
        verifyCliDistributionZip,
        verifyRoastCliDistribution,
    )
}

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

private fun runtimeOperatingSystem(): String =
    when {
        isWindows() -> "Windows"
        System.getProperty("os.name").startsWith("Mac") -> "Mac OS X"
        System.getProperty("os.name").startsWith("Linux") -> "Linux"
        else -> System.getProperty("os.name")
    }

private fun runtimeArchitecture(): String =
    when (System.getProperty("os.arch")) {
        "amd64",
        "x86_64" -> "x86_64"
        "aarch64",
        "arm64" -> "aarch64"
        else -> System.getProperty("os.arch")
    }

private fun runtimeArchivePlatform(): String =
    "${runtimeArchiveOperatingSystem()}-${runtimeArchitecture()}"

private fun runtimeArchiveOperatingSystem(): String =
    when (runtimeOperatingSystem()) {
        "Mac OS X" -> "macos"
        "Windows" -> "windows"
        "Linux" -> "linux"
        else -> error("Unsupported CLI runtime operating system: ${runtimeOperatingSystem()}")
    }

private fun temurinJdkUrl(
    architecture: String,
    operatingSystem: String,
    extension: String,
): String {
    val version = "21.0.11_10"
    return ("https://github.com/adoptium/temurin21-binaries/releases/download/" +
        "jdk-21.0.11%2B10/OpenJDK21U-jdk_${architecture}_${operatingSystem}_hotspot_${version}.${extension}")
}

private fun targetJdkHome(target: String, macBundle: Boolean = false) =
    layout.buildDirectory.dir(
        "construo/jdk/$target/jdk-21.0.11+10" + if (macBundle) "/Contents/Home" else ""
    )

private fun preserveRuntimeJavaLauncher(target: String, macBundle: Boolean = false) {
    val taskSuffix = target.replaceFirstChar(Char::uppercase)
    val javaExecutable = if (target.startsWith("windows")) "java.exe" else "java"
    val createRuntimeImage =
        tasks.named<ConstruoCreateRuntimeImageTask>("createRuntimeImage$taskSuffix")
    val preserveLauncher =
        tasks.register<Copy>("preserveRuntimeJavaLauncher$taskSuffix") {
            dependsOn(createRuntimeImage)
            from(targetJdkHome(target, macBundle).map { it.file("bin/$javaExecutable") })
            into(layout.buildDirectory.dir("construo/runtime-image/cli-$target/bin"))
            filePermissions { unix("rwxr-xr-x") }
        }
    tasks.named("roast$taskSuffix") { dependsOn(preserveLauncher) }
}

private data class RoastTarget(
    val name: String,
    val taskSuffix: String,
    val launcherRelativePath: String,
    val javaExecutable: String = "java",
) {
    fun launcherPath(version: String): String = "spectre-cli-$version/$launcherRelativePath"

    fun runtimeJavaPath(version: String): String =
        listOfNotNull(
                "spectre-cli-$version",
                launcherRelativePath.substringBeforeLast('/', missingDelimiterValue = "").ifBlank {
                    null
                },
                "runtime",
                "bin",
                javaExecutable,
            )
            .joinToString("/")
}

private fun hostRoastTarget(): RoastTarget =
    when (runtimeArchivePlatform()) {
        "linux-x86_64" -> RoastTarget("linuxX64", "LinuxX64", "spectre")
        "linux-aarch64" -> RoastTarget("linuxArm64", "LinuxArm64", "spectre")
        "macos-x86_64" -> RoastTarget("macosX64", "MacosX64", "Spectre.app/Contents/MacOS/spectre")
        "macos-aarch64" ->
            RoastTarget("macosArm64", "MacosArm64", "Spectre.app/Contents/MacOS/spectre")
        "windows-x86_64" -> RoastTarget("windowsX64", "WindowsX64", "spectre.exe", "java.exe")
        else -> error("Unsupported Roast target: ${runtimeArchivePlatform()}")
    }

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
