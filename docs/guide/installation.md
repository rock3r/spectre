# Installation

!!! warning "No published artifacts yet"
    Spectre is currently `0.1.0-SNAPSHOT` and isn't published to Maven Central or any
    other public repository. Until a tagged release lands, you have two options:

    1. **Build and consume from a local Maven repository** (recommended for trying it out).
    2. **Include the modules directly** as composite/included builds.

    Once releases start, this page will be updated with the artifact coordinates.

## Requirements

- **JDK 21 or newer.** JBR 21 is the project's dev-loop default; JBR 25 is exercised via
  the IDE-hosted UI test. Any JDK 21+ works for the non-IDE modules.
- **A Compose Desktop or Compose Multiplatform (desktop target) application.** Spectre
  reads Compose's semantics tree, so the UI under test must be a real Compose surface.
- **Platform-specific recording dependencies** if you plan to use the recording module —
  see [Recording](recording.md) for the per-OS prerequisites.

## Build from source

```shell
git clone https://github.com/rock3r/spectre.git
cd spectre
./gradlew publishToMavenLocal
```

That installs the modules under `dev.sebastiano.spectre:<module>:0.1.0-SNAPSHOT` into your
local Maven repository (`~/.m2/repository`).

In the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        // ...your other repositories
    }
}
```

In the consuming project's `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:core:0.1.0-SNAPSHOT")
    testImplementation("dev.sebastiano.spectre:testing:0.1.0-SNAPSHOT")

    // optional, depending on what you need:
    testImplementation("dev.sebastiano.spectre:recording:0.1.0-SNAPSHOT")
    testImplementation("dev.sebastiano.spectre:server:0.1.0-SNAPSHOT")
}
```

You also need the JUnit version that matches the wrapper you'll use:

=== "JUnit 5"

    ```kotlin
    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    }
    ```

=== "JUnit 4"

    ```kotlin
    dependencies {
        testImplementation("junit:junit:4.13.2")
    }
    ```

The `testing` module declares both JUnit dependencies as `compileOnly`, so consumers
pick whichever they're already using.

## Modules at a glance

| Module      | What it gives you                                                                |
| ----------- | -------------------------------------------------------------------------------- |
| `core`      | `ComposeAutomator`, semantics tree reader, selectors, `RobotDriver` for input.    |
| `testing`   | `ComposeAutomatorExtension` (JUnit 5), `ComposeAutomatorRule` (JUnit 4).         |
| `recording` | Region and window-targeted screen capture (`AutoRecorder`, `FfmpegRecorder`, …). |
| `server`    | Embedded HTTP transport (Ktor) and `HttpComposeAutomator` for cross-JVM access.   |

Most projects only need `core` + `testing`. Add `recording` if you want video output for
test runs, and `server` if your test process needs to reach a UI in a different JVM.

## Next

Continue to [Getting started](getting-started.md) for a worked example.
