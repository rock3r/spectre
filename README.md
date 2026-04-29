<h1 align="center">
  <img src="docs/assets/spectre-logo.png" alt="Spectre" width="200" />
  <br />
  Spectre
</h1>

A Kotlin library for driving Compose Desktop UIs from automated tests. Reads the semantics
tree, sends real OS-level mouse and keyboard input via `java.awt.Robot`, records the screen,
and works against IDE-hosted Compose surfaces (IntelliJ, Jewel) the same way as standalone
desktop apps.

Supports macOS and Windows. Linux is on the roadmap.

## Modules

- `core` — semantics tree, selectors, coordinate mapping, Robot-backed input.
- `server` — embedded HTTP transport (Ktor) for cross-JVM access.
- `recording` — region capture via `ffmpeg`, plus window-targeted capture (ScreenCaptureKit on
  macOS, `gdigrab` on Windows). `AutoRecorder` picks per call. See
  [`docs/RECORDING-LIMITATIONS.md`](docs/RECORDING-LIMITATIONS.md).
- `testing` — JUnit 5 extension and JUnit 4 rule.
- `sample-desktop` — Compose Desktop app for manual smokes.
- `sample-intellij-plugin` — IntelliJ plugin hosting a Jewel tool window. Not published; lives
  in-tree as the IDE-hosted test bed.

## Run the samples

Standalone Compose Desktop app:

```shell
./gradlew :sample-desktop:run
```

IntelliJ plugin (then `Tools → Run Spectre Against the Sample Tool Window`):

```shell
./gradlew :sample-intellij-plugin:runIde
```

Add `-PspectreAutorun=true` to fire the action automatically on project open.

## Quality checks

```shell
./gradlew check        # tests + Detekt + Compose Rules + ktfmt
./gradlew ktfmtFormat  # rewrite Kotlin / .gradle.kts in place
```

Two heavier checks are opt-in:

- `:sample-intellij-plugin:uiTest` — boots IntelliJ via `intellij-ide-starter`, installs the
  plugin, fires `RunSpectreAction`, and looks for every tagged Compose node in `idea.log`.
- `:recording:check` on macOS — covers the Swift ScreenCaptureKit helper.

## Supported JVMs

JBR 21 is the dev-loop default. JBR 25 also gets exercised via the IDE-hosted test (it ships
bundled with IntelliJ 2026.1). Any JDK 21+ should work for the non-IDE modules; CI itself
runs on Temurin 21 because `setup-java`'s JBR 21 entry is currently missing.

## CI

- [`ci.yml`](.github/workflows/ci.yml) — `:check` on Linux, every PR.
- [`macos-check.yml`](.github/workflows/macos-check.yml) — `:check` on macOS, broad path filter.
- [`windows.yml`](.github/workflows/windows.yml) — `:check` on Windows, broad path filter.
- [`macos.yml`](.github/workflows/macos.yml) — Swift helper build + `:recording:check`, gated
  on `recording/**`.
- [`ide-uitest.yml`](.github/workflows/ide-uitest.yml) — IDE-hosted UI test on macOS, gated on
  plugin / core / recording changes. `out/ide-tests/{installers,cache}` is cached so warm
  runs are ~30s.

## Reference docs

- [Architecture](docs/ARCHITECTURE.md)
- [Testing](docs/TESTING.md)
- [Conventions](docs/CONVENTIONS.md)
- [Static analysis](docs/STATIC-ANALYSIS.md)
- [Recording limitations](docs/RECORDING-LIMITATIONS.md)
- [Bootstrap plan](docs/bootstrap-plan.md)
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — original
  design notes.
