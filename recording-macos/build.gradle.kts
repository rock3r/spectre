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

val macosFamily = objects.named<OperatingSystemFamily>(OperatingSystemFamily.MACOS)

configurations
    .matching { it.name == "apiElements" || it.name == "runtimeElements" }
    .configureEach {
        attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, macosFamily)
    }

val recordingProject = project(":recording")
val prebuiltMacHelperPath = providers.gradleProperty("prebuiltMacHelperPath")
val useStubMacHelperForTesting = providers.gradleProperty("stubMacHelperForTesting").isPresent
val shouldNotarizeScreenCaptureKitHelper =
    providers.gradleProperty("notarizeScreenCaptureKitHelper").isPresent
val useUniversalHelper =
    providers.gradleProperty("universalHelper").isPresent || shouldNotarizeScreenCaptureKitHelper
val shouldVerifyMacosHelper =
    useStubMacHelperForTesting ||
        prebuiltMacHelperPath.isPresent ||
        OperatingSystem.current().isMacOsX

tasks.named<ProcessResources>("processResources") {
    from(recordingProject.layout.buildDirectory.dir("generated/screenCaptureHelper"))
    when {
        useStubMacHelperForTesting -> dependsOn(recordingProject.tasks.named("stageStubMacHelper"))
        prebuiltMacHelperPath.isPresent ->
            dependsOn(recordingProject.tasks.named("stagePrebuiltMacHelper"))
        OperatingSystem.current().isMacOsX && useUniversalHelper ->
            dependsOn(recordingProject.tasks.named("assembleScreenCaptureKitHelperUniversal"))
        OperatingSystem.current().isMacOsX ->
            dependsOn(recordingProject.tasks.named("assembleScreenCaptureKitHelper"))
    }
}

tasks.register("verifyRecordingMacosHelpers") {
    group = "verification"
    description = "Verifies the macOS helper resource is packaged in spectre-recording-macos."
    enabled = shouldVerifyMacosHelper
    dependsOn(tasks.named("jar"))

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doLast {
        val jar = jarFile.get().asFile
        val requiredEntries =
            listOf(
                "native/macos/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture",
                "native/macos/SpectreCaptureHelper.app/Contents/Info.plist",
                "native/macos/SpectreCaptureHelper.app/Contents/PkgInfo",
                "native/macos/SpectreCaptureHelper.app/Contents/Resources/AppIcon.icns",
            )
        ZipFile(jar).use { zip ->
            val missing = requiredEntries.filter { zip.getEntry(it) == null }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "spectre-recording-macos jar is missing SpectreCaptureHelper.app " +
                        "entries: ${missing.joinToString()}"
                )
            }
            // Legacy bare-binary resource must not ship alongside the app bundle.
            if (zip.getEntry("native/macos/spectre-screencapture") != null) {
                throw GradleException(
                    "spectre-recording-macos jar still contains bare native/macos/spectre-screencapture; " +
                        "the helper must ship only as SpectreCaptureHelper.app (#190)"
                )
            }
        }
    }
}

tasks.named("check") { dependsOn("verifyRecordingMacosHelpers") }
