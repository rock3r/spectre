<h1 align="center">
  <img src="docs/assets/spectre-logo.png" alt="Spectre" width="200" />
  <br />
  Spectre
</h1>

JVM-first Kotlin library for automating live Compose Desktop UIs — semantics-tree inspection,
Robot-backed real-OS input, screen recording, and IDE-hosted Jewel surfaces. v1 (cross-platform
core, in-process automator, Robot input, ffmpeg region recording, IntelliJ/Jewel sample plugin)
and v2 (macOS ScreenCaptureKit window-targeted recording via a bundled Swift helper) have shipped.
v3 (Windows) and v4 (Linux) are tracked as platform-specific issues.

## Modules

- `core` — semantics tree reading, owner/root-aware node identity, selectors, coordinate mapping,
  Robot-backed input primitives.
- `server` — opt-in transport layer (planned; scaffolded only).
- `recording` — region capture via `ffmpeg`+`avfoundation` (`FfmpegRecorder`, v1) and
  window-targeted capture on macOS via a bundled Swift ScreenCaptureKit helper
  (`screencapturekit.ScreenCaptureKitRecorder`, v2). `AutoRecorder` picks the right backend
  per call. See [`docs/RECORDING-LIMITATIONS.md`](docs/RECORDING-LIMITATIONS.md) for the
  matrix of when each backend applies.
- `testing` — JUnit 5 extension and JUnit 4 rule (`ComposeAutomatorExtension` /
  `ComposeAutomatorRule`) for tests that drive a real `ComposeAutomator`.
- `sample-desktop` — small Compose Desktop app used as a manual smoke surface for the automator
  primitives.
- `sample-intellij-plugin` — IntelliJ plugin that hosts a Jewel tool window and proves Spectre
  works against IDE-hosted Compose surfaces. Never published — exists as a validation surface.

## Run the desktop sample

```shell
./gradlew :sample-desktop:run
```

## Run the IntelliJ sample

```shell
./gradlew :sample-intellij-plugin:runIde
# Then: Tools → Run Spectre Against the Sample Tool Window
# Discovered semantics tree dumps to idea.log.
```

For a non-interactive smoke that auto-fires the action on project open:

```shell
./gradlew :sample-intellij-plugin:runIde -PspectreAutorun=true
```

## Quality checks

```shell
./gradlew check        # tests + Detekt 2.x + Compose Rules + ktfmt verification
./gradlew ktfmtFormat  # rewrite Kotlin / Gradle Kotlin DSL files in place
```

`:check` is intentionally fast and runs on every PR. Two pieces are opt-in / gated separately:

- **`:sample-intellij-plugin:uiTest`** — IDE-hosted UI test (issue #42, intellij-ide-starter).
  Boots a real IntelliJ Ultimate IDE, installs the locally-built plugin, fires `RunSpectreAction`,
  and asserts every tagged Compose node lands in `idea.log`. Runs in CI via
  [`.github/workflows/ide-uitest.yml`](.github/workflows/ide-uitest.yml) when plugin / core /
  recording sources change.
- **`:recording:check`** on macOS — exercises the Swift ScreenCaptureKit helper. Runs in CI via
  [`.github/workflows/macos.yml`](.github/workflows/macos.yml) when recording sources change.

## Supported JVMs

Spectre is validated on:

- **JBR 21** — JetBrains Runtime 21 (the dev-loop default; the project's Gradle toolchain pins
  JDK 21 and bytecode targets 21).
- **JBR 25** — exercised via the IDE-hosted UI test, which boots IntelliJ Ultimate 2026.1.1
  with its bundled JBR 25.0.2 and runs `core` + plugin code paths inside that runtime.

Other JDKs / vendors at language level 21+ should work — Spectre uses no JBR-only APIs in the
non-IDE modules — but they're not validated and **YMMV**. CI itself runs on Temurin 21
(see [`ci.yml`](.github/workflows/ci.yml)) because the GitHub `setup-java` action's JBR index
is currently missing JBR 21; bytecode equivalence makes that an acceptable proxy.

## CI

- [`ci.yml`](.github/workflows/ci.yml) — `./gradlew check` on every PR (Linux).
- [`macos.yml`](.github/workflows/macos.yml) — Swift helper build + `:recording:check` on macOS,
  gated on `recording/**` changes.
- [`ide-uitest.yml`](.github/workflows/ide-uitest.yml) — IDE-hosted UI test on macOS, gated on
  plugin/core/recording/build changes, with `out/ide-tests/{installers,cache}` cached so warm
  runs are ~30s instead of ~10min.

## Reference docs

- [Architecture](docs/ARCHITECTURE.md) — module map, dependency direction, invariants.
- [Testing](docs/TESTING.md) — TDD flow, contract tests, validation expectations.
- [Conventions](docs/CONVENTIONS.md) — file placement, coding style, git/build workflow.
- [Static analysis](docs/STATIC-ANALYSIS.md) — Detekt, ktfmt, CI quality expectations.
- [Recording limitations](docs/RECORDING-LIMITATIONS.md) — v1 region-capture trade-offs and the
  v2 window-targeted backend's scope.
- [Bootstrap plan](docs/bootstrap-plan.md) — historical context for what shipped in v1+v2 and
  pointers into the v3/v4 tracking issues.
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — original
  external design notes.
