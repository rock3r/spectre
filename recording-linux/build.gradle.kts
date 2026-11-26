import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.nativeplatform.OperatingSystemFamily

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
    runtimeOnly(projects.recording)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

val linuxFamily = objects.named<OperatingSystemFamily>(OperatingSystemFamily.LINUX)

configurations
    .matching { it.name == "apiElements" || it.name == "runtimeElements" }
    .configureEach {
        attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, linuxFamily)
    }

val recordingProject = project(":recording")
val testGnomeWindowIdentity =
    tasks.register<Exec>("testGnomeWindowIdentity") {
        group = "verification"
        description = "Tests strict Xwayland identity matching and one-time handoffs."
        workingDir(recordingProject.layout.projectDirectory)
        commandLine("node", "native/linux/gnome-shell-extension/windowIdentity.test.mjs")
        inputs.files(
            recordingProject.file(
                "native/linux/gnome-shell-extension/spectre-window-identity@sebastiano.dev/windowIdentity.js"
            ),
            recordingProject.file("native/linux/gnome-shell-extension/windowIdentity.test.mjs"),
        )
    }

val prebuiltLinuxHelpersDir = providers.gradleProperty("prebuiltLinuxHelpersDir")
val useAllLinuxArches = providers.gradleProperty("allLinuxArches").isPresent
val shouldVerifyLinuxHelpers =
    prebuiltLinuxHelpersDir.isPresent || OperatingSystem.current().isLinux
val expectedLinuxHelperPaths =
    if (prebuiltLinuxHelpersDir.isPresent || useAllLinuxArches) {
        listOf(
            "native/linux/x86_64/spectre-wayland-helper",
            "native/linux/aarch64/spectre-wayland-helper",
        )
    } else {
        val arch =
            when (System.getProperty("os.arch").orEmpty().lowercase()) {
                "amd64",
                "x86_64",
                "x64" -> "x86_64"
                "aarch64",
                "arm64" -> "aarch64"
                else -> System.getProperty("os.arch").orEmpty().lowercase()
            }
        listOf("native/linux/$arch/spectre-wayland-helper")
    }

tasks.named<ProcessResources>("processResources") {
    from(recordingProject.layout.buildDirectory.dir("generated/waylandHelper"))
    from(recordingProject.layout.projectDirectory.dir("native/linux/gnome-shell-extension")) {
        into("native/linux/gnome-shell-extension")
    }
    when {
        prebuiltLinuxHelpersDir.isPresent ->
            dependsOn(recordingProject.tasks.named("stagePrebuiltLinuxHelpers"))
        OperatingSystem.current().isLinux && useAllLinuxArches ->
            dependsOn(recordingProject.tasks.named("assembleWaylandHelperAllArches"))
        OperatingSystem.current().isLinux ->
            dependsOn(recordingProject.tasks.named("assembleWaylandHelper"))
    }
}

tasks.register("verifyRecordingLinuxHelpers") {
    group = "verification"
    description = "Verifies the Linux helper resources are packaged in spectre-recording-linux."
    enabled = shouldVerifyLinuxHelpers
    dependsOn(tasks.named("jar"))

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val expectedHelpers = expectedLinuxHelperPaths
    val expectedExtensionPaths =
        listOf(
            "native/linux/gnome-shell-extension/spectre-window-identity@sebastiano.dev/metadata.json",
            "native/linux/gnome-shell-extension/spectre-window-identity@sebastiano.dev/extension.js",
            "native/linux/gnome-shell-extension/spectre-window-identity@sebastiano.dev/windowIdentity.js",
        )
    doLast {
        val jar = jarFile.get().asFile
        val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val missing = (expectedHelpers + expectedExtensionPaths).filter { it !in entries }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "spectre-recording-linux jar is missing helper resources: " + missing.joinToString()
            )
        }
        ZipFile(jar).use { zip ->
            val metadata =
                JsonSlurper().parse(zip.getInputStream(zip.getEntry(expectedExtensionPaths[0])))
                    as Map<*, *>
            val supported = (metadata["shell-version"] as List<*>).map(Any?::toString)
            val expected = (45..50).map(Int::toString)
            if (supported != expected) {
                throw GradleException(
                    "GNOME identity extension supports $supported; expected $expected"
                )
            }
            val source =
                zip.getInputStream(zip.getEntry(expectedExtensionPaths[1])).bufferedReader().use {
                    it.readText()
                }
            val requiredAsyncReplies =
                listOf("return_value", "return_dbus_error", ".AccessDenied", ".NotFound")
            val missingReplies = requiredAsyncReplies.filter { it !in source }
            if (missingReplies.isNotEmpty()) {
                throw GradleException(
                    "GNOME identity extension is missing explicit async D-Bus replies: " +
                        missingReplies.joinToString()
                )
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testGnomeWindowIdentity)
    dependsOn("verifyRecordingLinuxHelpers")
}
