import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "Spectre"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()

        // The IntelliJ Platform Gradle plugin (settings variant, applied below) registers an
        // ivy-style index for IDE distribution artifacts here. Settings-level avoids forcing
        // each module to declare it. Other modules ignore the IntelliJ index because their
        // dependencies don't match the index's group, so this is harmless for them.
        intellijPlatform { defaultRepositories() }

        // Jewel pulls `org.jetbrains.skiko:skiko-awt-runtime-all`, which lives only on
        // JetBrains' Compose dev space (not Maven Central). `includeGroup` keeps the lookup
        // scoped to skiko so Maven Central remains the source of truth for everything else.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            mavenContent { includeGroup("org.jetbrains.skiko") }
        }

        // intellij-ide-starter and the Driver API are only published to JetBrains' own
        // releases mirror — the IDE-build-numbered versions we pin (e.g. `261.23567.138`)
        // never make it to Maven Central. Scope the lookup to the two relevant groups so
        // every other module's resolution still goes through Maven Central first. Used by
        // the `:sample-intellij-plugin` UI test only.
        maven("https://www.jetbrains.com/intellij-repository/releases") {
            mavenContent {
                includeGroup("com.jetbrains.intellij.tools")
                includeGroup("com.jetbrains.intellij.driver")
            }
        }

        // Transitive dependency mirror used by intellij-ide-starter (its kodein-di / fus /
        // teamcity-rest deps live here, not Maven Central). Scoped via `includeGroupByRegex`
        // to avoid bleeding into other modules' resolution.
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies") {
            mavenContent {
                includeGroupByRegex("com\\.jetbrains\\..*")
                includeGroupByRegex("org\\.jetbrains\\.intellij\\..*")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Settings-level companion to the project-level `org.jetbrains.intellij.platform` plugin —
    // makes the `intellijPlatform { defaultRepositories() }` block above resolvable.
    //
    // Version pinned here as a literal because Gradle's settings `plugins { }` block doesn't
    // accept script-scope expressions for `version`. Keep the project-level plugin in
    // `sample-intellij-plugin/build.gradle.kts` applied without a version (`id(...)` only) so
    // it inherits this version through the settings classpath — single source of truth.
    id("org.jetbrains.intellij.platform.settings") version "2.10.1"
}

include(":core")

include(":server")

include(":recording")

include(":testing")

include(":sample-desktop")

include(":sample-intellij-plugin")
