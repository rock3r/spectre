plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.core)
    testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

