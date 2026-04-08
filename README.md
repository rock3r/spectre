# Spectre

Bootstrap repository for a Compose Desktop automator focused on real-time desktop automation,
recording, and profiling workflows.

This repo is intentionally at the scaffold stage. The project structure is ready for the spike,
but the automation features themselves are not implemented yet.

## Modules

- `core`: desktop automation primitives and shared model
- `server`: future cross-JVM transport layer
- `recording`: future recording and capture integration
- `testing`: future JUnit-facing helpers
- `sample-desktop`: small desktop app kept around for manual verification during the spike

## Run the sample app

```shell
./gradlew :sample-desktop:run
```

## Quality checks

```shell
./gradlew check
./gradlew detekt
./gradlew ktfmtCheck
./gradlew build
```

`ktfmtFormat` is available when you want the formatter to rewrite Kotlin and Gradle Kotlin DSL
files for you.

Compose-bearing modules also run the upstream Compose Rules Detekt plugin, so `detekt` and
`check` cover both general Kotlin issues and Compose-specific API/layout guidance.

## CI

GitHub Actions runs a simple CI workflow on pull requests and on pushes to `main`. The workflow is
defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) and executes `./gradlew check`,
which now includes tests, Detekt 2.x, Compose Rules through Detekt, and ktfmt verification.

## Reference plan

- [Bootstrap notes](./docs/bootstrap-plan.md)
- [Static analysis](./docs/STATIC-ANALYSIS.md)
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8)
