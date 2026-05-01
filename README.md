<h1 align="center">
  <img src="docs/assets/spectre-logo.png" alt="Spectre" width="200" />
  <br />
  Spectre
</h1>

A Kotlin library for driving Compose Desktop UIs from automated tests. Reads the semantics
tree, sends real OS-level mouse and keyboard input via `java.awt.Robot`, and records the
screen — against IDE-hosted Compose surfaces (IntelliJ, Jewel) and standalone desktop apps
alike.

macOS, Windows, and Linux Xorg. Wayland support is **partial**: the
xdg-desktop-portal handshake is wired up and validated end-to-end (#77 stage 2 — recorder
detects the session, opens the portal session, gets a PipeWire stream node from the
compositor), but the encoder spawn needs JVM-to-subprocess file-descriptor inheritance that
isn't built yet. The recorder throws an explicit `UnsupportedOperationException` rather than
producing a 0-byte mp4. [#80](https://github.com/rock3r/spectre/issues/80) tracks the stage-3
work.

## Modules

- `core` — semantics tree, selectors, coordinate mapping, Robot-backed input.
- `server` — embedded HTTP transport (Ktor) for cross-JVM access.
- `recording` — region capture via `ffmpeg`, plus window-targeted capture (ScreenCaptureKit on
  macOS, `gdigrab` on Windows). `AutoRecorder` picks per call. See
  [`docs/RECORDING-LIMITATIONS.md`](docs/RECORDING-LIMITATIONS.md).
- `testing` — JUnit 5 extension and JUnit 4 rule.
- `sample-desktop` — Compose Desktop app for manual smokes.
- `sample-intellij-plugin` — in-tree IntelliJ plugin hosting a Jewel tool window. Unpublished;
  serves as the IDE-hosted test bed.

## Run the samples

Standalone Compose Desktop app:

```shell
./gradlew :sample-desktop:run
```

IntelliJ plugin — then `Tools → Run Spectre Against the Sample Tool Window`, or pass
`-PspectreAutorun=true` to fire the action on project open:

```shell
./gradlew :sample-intellij-plugin:runIde
```

## Quality checks

```shell
./gradlew check        # tests + Detekt + Compose Rules + ktfmt
./gradlew ktfmtFormat  # rewrite Kotlin / .gradle.kts in place
```

Two heavier checks live outside `:check`:

- `:sample-intellij-plugin:uiTest` — boots IntelliJ via `intellij-ide-starter`, installs the
  plugin, fires `RunSpectreAction`, and asserts every tagged Compose node shows up in
  `idea.log`.
- `:recording:check` on macOS — covers the Swift ScreenCaptureKit helper.

## Supported JVMs

JBR 21 is the dev-loop default. JBR 25 also gets exercised via the IDE-hosted test (bundled
with IntelliJ 2026.1). Any JDK 21+ works for the non-IDE modules; CI runs on Temurin 21
because `setup-java`'s JBR 21 entry is missing.

## CI

- [`ci.yml`](.github/workflows/ci.yml) — `:check` on Linux, every PR.
- [`macos-check.yml`](.github/workflows/macos-check.yml) — `:check` on macOS, broad path filter.
- [`windows.yml`](.github/workflows/windows.yml) — `:check` on Windows, broad path filter.
- [`macos.yml`](.github/workflows/macos.yml) — Swift helper build + `:recording:check`, gated
  on `recording/**`.
- [`ide-uitest.yml`](.github/workflows/ide-uitest.yml) — IDE-hosted UI test on macOS + Windows,
  gated on plugin / core / recording changes. `out/ide-tests/{installers,cache}` is cached
  between runs.
- [`validation-windows.yml`](.github/workflows/validation-windows.yml) —
  `:sample-desktop:validationTest*` on Windows, gated on `sample-desktop/**`. JUnit-XML-driven
  verifier so a Gradle/Compose protocol flake on shutdown can't hide a real failure.
- [`validation-linux.yml`](.github/workflows/validation-linux.yml) — same validation matrix
  on Linux under `xvfb-run` (real Xorg, no compositor in the loop), gated on the same
  `sample-desktop/**` filter shape.

## Reference docs

- [Architecture](docs/ARCHITECTURE.md)
- [Testing](docs/TESTING.md)
- [Conventions](docs/CONVENTIONS.md)
- [Static analysis](docs/STATIC-ANALYSIS.md)
- [Recording limitations](docs/RECORDING-LIMITATIONS.md)
- [Bootstrap plan](docs/bootstrap-plan.md)
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — original
  design notes.
