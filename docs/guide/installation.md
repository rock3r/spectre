# Installation

Spectre publishes four library modules to Maven Central:

| Module | Maven coordinate |
| ------ | ---------------- |
| `core` | `dev.sebastiano.spectre:spectre-core:<version>` |
| `testing` | `dev.sebastiano.spectre:spectre-testing:<version>` |
| `recording` | `dev.sebastiano.spectre:spectre-recording:<version>` |
| `server` | `dev.sebastiano.spectre:spectre-server:<version>` |

!!! note "Before a release tag is published"
    The `main` branch declares `0.1.0-SNAPSHOT`. Until a tagged release has been published
    to Maven Central, consume the checkout as a Gradle composite build (`includeBuild`) or
    publish locally with `./gradlew publishToMavenLocal`.

## Requirements

- **JDK 21 or newer.** JBR 21 is the project's dev-loop default; JBR 25 is exercised via
  the IDE-hosted UI test. Any JDK 21+ works for the non-IDE modules.
- **A Compose Desktop or Compose Multiplatform (desktop target) application.** Spectre
  reads Compose's semantics tree, so the UI under test must be a real Compose surface.
- **Platform-specific recording dependencies** if you plan to use the recording module
  — see [Recording](recording.md) for the per-OS prerequisites.

## Consume from Maven Central

Add the modules you need to the consuming project's `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:spectre-core:<version>")
    testImplementation("dev.sebastiano.spectre:spectre-testing:<version>")

    // Optional, depending on what you need:
    testImplementation("dev.sebastiano.spectre:spectre-recording:<version>")
    testImplementation("dev.sebastiano.spectre:spectre-server:<version>")
}
```

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

## Consume the current checkout

Clone Spectre next to the project that wants to use it:

```shell
git clone https://github.com/rock3r/spectre.git
```

In the consuming project's `settings.gradle.kts`, include Spectre's checkout as a
composite build and map the published module coordinates to the included projects:

```kotlin
includeBuild("../spectre") {
    dependencySubstitution {
        substitute(module("dev.sebastiano.spectre:spectre-core"))
            .using(project(":core"))
        substitute(module("dev.sebastiano.spectre:spectre-testing"))
            .using(project(":testing"))
        substitute(module("dev.sebastiano.spectre:spectre-recording"))
            .using(project(":recording"))
        substitute(module("dev.sebastiano.spectre:spectre-server"))
            .using(project(":server"))
    }
}
```

In the consuming project's `build.gradle.kts`, use the same coordinates as the Maven
Central path — Gradle resolves them against the included build:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:spectre-core")
    testImplementation("dev.sebastiano.spectre:spectre-testing")

    // Optional, depending on what you need:
    testImplementation("dev.sebastiano.spectre:spectre-recording")
    testImplementation("dev.sebastiano.spectre:spectre-server")
}
```

Versions are intentionally omitted: `includeBuild` substitutes the project dependency
into the consumer's classpath using whatever version the included build declares, so
the `0.1.0-SNAPSHOT` declared by Spectre's root Gradle build is implicit.

For local Maven-style testing without a composite build, publish the current checkout to
Maven Local:

```shell
./gradlew publishToMavenLocal
```

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
