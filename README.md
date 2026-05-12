<h1 align="center">
  <img src="docs/assets/spectre-logo.png" alt="Spectre" width="200" />
  <br />
  Spectre
</h1>

A Kotlin library for driving Compose Desktop UIs from automated tests. Reads the semantics
tree, drives mouse and keyboard input — either real OS-level events via `java.awt.Robot` or
synthetic AWT events dispatched straight into the window hierarchy (`RobotDriver.synthetic(...)`,
useful when tests run in parallel and can't fight over OS focus) — and records the screen,
against IDE-hosted Compose surfaces (IntelliJ, Jewel) and standalone desktop apps alike.

> [!IMPORTANT]
> **Heads up: this repo is currently a lot of vibe slop.** Large parts were written with
> heavy AI pair-programming and haven't yet had a proper human review pass end-to-end.
> I'll be going through it manually to read, audit, and tighten things up. Until then,
> treat design and implementation choices as "looks plausible, not yet hand-audited" and
> expect follow-up commits to rework bits once I've actually read them.

macOS, Windows, Linux Xorg, and Linux Wayland. The Wayland path goes through a small
out-of-process Rust helper (`recording/native/linux/`) that owns the xdg-desktop-portal
handshake, the PipeWire FD lifetime, and the `gst-launch-1.0` subprocess; the JVM-side
recorder talks to it over stdin/stdout via a tiny JSON protocol. Same out-of-process
architecture as the macOS Swift helper.

> [!NOTE]
> **Linux support is best-effort.** Routine validation runs on one machine: Ubuntu 22.04,
> exercising the Xorg session (input + popup + HiDPI + `x11grab`) and the GNOME / mutter
> Wayland session (the portal-based recording path). Other distros, compositors (KDE /
> Plasma, sway, wlroots), window managers, and Ubuntu versions aren't covered. Reports
> and PRs widening the coverage are very welcome — open an issue with your distro /
> compositor / session combo and we'll work through it.

## Documentation

User guide and API documentation: **<https://spectre.sebastiano.dev>**.

Start at [Getting started](https://spectre.sebastiano.dev/guide/getting-started/) for the
shape of a Spectre test, or [The automator](https://spectre.sebastiano.dev/guide/automator/)
for the mental model.

## Modules

- `core` — semantics tree, selectors, coordinate mapping, Robot-backed input.
- `server` — embedded HTTP transport (Ktor) for cross-JVM access. **Experimental**; see
  [`docs/SECURITY.md`](docs/SECURITY.md) for the trust model.
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
with IntelliJ 2026.1). Any JDK 21+ works for the non-IDE modules. CI runs on Temurin 21.

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

The user guide and these reference pages are also published as a single browseable site
at <https://spectre.sebastiano.dev>.

- [Architecture](docs/ARCHITECTURE.md)
- [Testing](docs/TESTING.md)
- [Conventions](docs/CONVENTIONS.md)
- [Static analysis](docs/STATIC-ANALYSIS.md)
- [Recording limitations](docs/RECORDING-LIMITATIONS.md)
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) — original
  design notes.
