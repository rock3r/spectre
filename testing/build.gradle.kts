plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.core)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
