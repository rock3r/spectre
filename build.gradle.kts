import com.ncorti.ktfmt.gradle.KtfmtExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.SourceTask

plugins {
    base
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
}

group = "dev.sebastiano.spectre"

version = "0.1.0-SNAPSHOT"

val generatedSourceExcludes = arrayOf("**/build/**", "**/generated/**")

configure<KtfmtExtension> { kotlinLangStyle() }

configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    basePath.set(rootProject.layout.projectDirectory)
}

tasks.named("check") { dependsOn("detekt", "ktfmtCheck") }

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    exclude(*generatedSourceExcludes)
}

subprojects {
    apply(plugin = "base")

    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("com.ncorti.ktfmt.gradle") {
        extensions.configure<KtfmtExtension> { kotlinLangStyle() }

        tasks.named("check") { dependsOn("detekt", "ktfmtCheck") }
    }

    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel = true
            basePath.set(rootProject.layout.projectDirectory)
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget = "21"
            exclude(*generatedSourceExcludes)
        }
    }

    tasks
        .matching { it.name.startsWith("ktfmtCheck") || it.name.startsWith("ktfmtFormat") }
        .configureEach { (this as? SourceTask)?.exclude(*generatedSourceExcludes) }
}
