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

val windowsFamily = objects.named<OperatingSystemFamily>(OperatingSystemFamily.WINDOWS)

configurations
    .matching { it.name == "apiElements" || it.name == "runtimeElements" }
    .configureEach {
        attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, windowsFamily)
    }

val recordingProject = project(":recording")
val prebuiltWindowsHelperPath = providers.gradleProperty("prebuiltWindowsHelperPath")
val prebuiltWindowsHelpersDir = providers.gradleProperty("prebuiltWindowsHelpersDir")
val shouldVerifyWindowsHelper =
    OperatingSystem.current().isWindows ||
        prebuiltWindowsHelperPath.isPresent ||
        prebuiltWindowsHelpersDir.isPresent

tasks.named<ProcessResources>("processResources") {
    from(recordingProject.layout.buildDirectory.dir("generated/windowsScreenshotHelper"))
    if (prebuiltWindowsHelperPath.isPresent || prebuiltWindowsHelpersDir.isPresent) {
        dependsOn(recordingProject.tasks.named("stagePrebuiltWindowsScreenshotHelper"))
    } else if (OperatingSystem.current().isWindows) {
        dependsOn(recordingProject.tasks.named("assembleWindowsScreenshotHelper"))
    }
}

tasks.register("verifyRecordingWindowsHelper") {
    group = "verification"
    description =
        "Verifies the Windows Graphics Capture helper resource is packaged in spectre-recording-windows."
    enabled = shouldVerifyWindowsHelper
    dependsOn(tasks.named("jar"))

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doLast {
        val jar = jarFile.get().asFile
        val containsHelpers =
            ZipFile(jar).use { zip ->
                val x64 = zip.getEntry("native/windows/x64/spectre-window-capture.exe")
                val arm64 = zip.getEntry("native/windows/arm64/spectre-window-capture.exe")
                x64 != null && x64.size > 0 && arm64 != null && arm64.size > 0
            }
        if (!containsHelpers) {
            throw GradleException(
                "spectre-recording-windows jar is missing non-empty x64 and/or arm64 " +
                    "Windows Graphics Capture helpers"
            )
        }
    }
}

tasks.named("check") { dependsOn("verifyRecordingWindowsHelper") }
