# Installation

!!! warning "No published artifacts yet"
    Spectre is currently `0.1.0-SNAPSHOT` and isn't published to Maven Central or any
    other public repository. The Gradle build also doesn't apply `maven-publish`, so
    `./gradlew publishToMavenLocal` is not a working path either. Until a tagged
    release lands, the supported way to consume Spectre is as a Gradle composite build
    (`includeBuild`) — see below. Once releases start, this page will be updated with
    artifact coordinates.

## Requirements

- **JDK 21 or newer.** JBR 21 is the project's dev-loop default; JBR 25 is exercised via
  the IDE-hosted UI test. Any JDK 21+ works for the non-IDE modules.
- **A Compose Desktop or Compose Multiplatform (desktop target) application.** Spectre
  reads Compose's semantics tree, so the UI under test must be a real Compose surface.
- **Platform-specific recording dependencies** if you plan to use the recording module
  — see [Recording](recording.md) for the per-OS prerequisites.

## Consume as a composite build

Clone Spectre next to the project that wants to use it:

```shell
git clone https://github.com/rock3r/spectre.git
```

In the consuming project's `settings.gradle.kts`, include Spectre's checkout as a
composite build:

```kotlin
includeBuild("../spectre")
```

In the consuming project's `build.gradle.kts`, depend on the modules you need by
project coordinates — Gradle resolves them against the included build:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:core")
    testImplementation("dev.sebastiano.spectre:testing")

    // optional, depending on what you need:
    testImplementation("dev.sebastiano.spectre:recording")
    testImplementation("dev.sebastiano.spectre:server")
}
```

Versions are intentionally omitted: `includeBuild` substitutes the project dependency
into the consumer's classpath using whatever version the included build declares, so
the `0.1.0-SNAPSHOT` you see in `gradle.properties` is implicit.

If you depend on `server`, you also need to add a Ktor server engine yourself — Spectre
intentionally doesn't bundle one. See [Cross-JVM access](cross-jvm.md) for the choice.

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
| `server`    | Embedded HTTP transport (Ktor) and `HttpComposeAutomator` for cross-JVM access. **Experimental**; see [Security notes](../SECURITY.md). |

Most projects only need `core` + `testing`. Add `recording` if you want video output for
test runs, and `server` if your test process needs to reach a UI in a different JVM.

## Next

Continue to [Getting started](getting-started.md) for a worked example.
