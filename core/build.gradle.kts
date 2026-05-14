plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
    // Publication coordinates + POM scalars come from `gradle.properties` — root file owns
    // the shared values, this module's own `gradle.properties` adds `POM_NAME` /
    // `POM_DESCRIPTION` / `POM_ARTIFACT_ID`. Root `subprojects { }` convention wires
    // signing, sources / javadoc jars, and the Central Portal upload target.
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    // compose-ui is `api` because the public AutomatorNode surface exposes its types
    // (Rect, SemanticsNode, Role, etc.) so downstream consumers — sample-desktop, the
    // server module's transport conversions — must be able to compile against them
    // without re-declaring the dependency.
    api(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.tracing.wire)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlin.testJunit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
