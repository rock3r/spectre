import dev.sebastiano.spectre.build.WindowsGraphicsCaptureHelperPackagingContract
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
        "Verifies the Windows Graphics Capture helper multi-file runtime contract is packaged " +
            "in spectre-recording-windows for x64 and arm64."
    enabled = shouldVerifyWindowsHelper
    dependsOn(tasks.named("jar"))

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doLast {
        val jar = jarFile.get().asFile
        val errors =
            ZipFile(jar).use { zip ->
                WindowsGraphicsCaptureHelperPackagingContract.validateZip(zip)
            }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "spectre-recording-windows jar fails the Windows Graphics Capture helper " +
                    "packaging contract:\n" +
                    errors.joinToString("\n") { "  - $it" }
            )
        }
    }
}

tasks.named("check") { dependsOn("verifyRecordingWindowsHelper") }
