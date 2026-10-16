<h1 align="center">
  <img src="docs/assets/spectre-logo.png" alt="Spectre" width="200" />
  <br />
  Spectre
</h1>

Spectre is a Kotlin library that automates running Compose Desktop UIs.
It reads the semantics tree, sends mouse and keyboard events, and records the screen.
It works with standalone applications and Compose surfaces hosted by IntelliJ or Jewel.

`RobotDriver.synthetic()` sends AWT events by default. Pass `RobotDriver()` when a test
needs real operating system input through `java.awt.Robot`.

> [!IMPORTANT]
> Spectre is pre-1.0. The [stability policy](docs/STABILITY.md) covers stable APIs.
> Experimental APIs, including HTTP transport, may change between releases.
> Read the [security notes](docs/SECURITY.md) before enabling cross-JVM control or recording
> where an application handles untrusted input.

Spectre supports macOS, Windows, Linux Xorg, and Linux Wayland.
On Wayland, a separate Rust helper in `recording/native/linux/` manages portal consent,
the PipeWire file descriptor, and `gst-launch-1.0`. The JVM recorder exchanges JSON
with the helper through standard input and output. The macOS Swift helper also runs
in a separate process.

> [!NOTE]
> **Linux support is best-effort.** Routine validation covers Ubuntu 22.04 on one machine.
> The Xorg run checks input, popups, HiDPI, and `x11grab`. The GNOME/Mutter Wayland run
> checks portal recording. Other distributions, compositors such as KDE/Plasma,
> sway, and wlroots, window managers, and Ubuntu versions have no routine coverage.
> For problems on untested systems, open an issue with the distribution, compositor,
> and session type.

## Documentation

User guide and API documentation: **<https://spectre.sebastiano.dev>**.

Install a release from Maven Central. For changes that have not shipped, use the current
checkout as a Gradle composite build:
[Installation](https://spectre.sebastiano.dev/guide/installation/).

Use [Getting started](https://spectre.sebastiano.dev/guide/getting-started/) to write a test.
[The automator](https://spectre.sebastiano.dev/guide/automator/) explains the API model.

Agent skill:

```shell
npx skills add rock3r/spectre --skill spectre
```

## Modules

- `core` — semantics tree, selectors, coordinate mapping, synthetic-or-Robot input.
- `server` — embedded HTTP transport (Ktor) for cross-JVM access. **Experimental**; see
  [`docs/SECURITY.md`](docs/SECURITY.md) for the trust model.
- `recording` — region capture, window-targeted video capture, and native still
  window screenshots where available (ScreenCaptureKit on macOS, Windows Graphics Capture
  on Windows, X11 region fallback on Linux). `AutoRecorder` / `AutoScreenshotter` pick per call. See
  [`docs/RECORDING-LIMITATIONS.md`](docs/RECORDING-LIMITATIONS.md).
- `recording-macos` — runtime-only macOS ScreenCaptureKit helper resources for native
  window/region recording and still screenshots.
- `recording-linux` — runtime-only Linux helper resources for native Xorg and Wayland capture.
- `recording-windows` — runtime-only Windows Graphics Capture helper resources for native
  window/region recording and still screenshots. Runtime machines need Windows 10 version
  1903+, .NET 8 Desktop Runtime, and Windows App Runtime 1.8; contributors building the
  helper need the .NET 8 SDK.
- `agent` — experimental local attach transport for driving a running Compose JVM from a
  separate process (preinstalled `spectre-core` preferred; inject when the target has no core).
- `agent-runtime` — loadable Java-agent runtime jar used by `agent` when attaching to a target JVM.
- `testing` — JUnit 5 extension and JUnit 4 rule.
- `sample-desktop` — Compose Desktop app for manual smokes.
- `sample-intellij-plugin` — unpublished IntelliJ plugin with a Jewel tool window for
  IDE-hosted tests.

## Run the samples

Standalone Compose Desktop app:

```shell
./gradlew :sample-desktop:run
```

For the IntelliJ plugin, use `Tools → Run Spectre Against the Sample Tool Window`, or pass
`-PspectreAutorun=true` to fire the action on project open:

```shell
./gradlew :sample-intellij-plugin:runIde
```

## Quality checks

```shell
./gradlew check        # tests + Detekt + Compose Rules + ktfmt
./gradlew ktfmtFormat  # rewrite Kotlin / .gradle.kts in place
```

Two additional checks run outside `:check`:

- `:sample-intellij-plugin:uiTest` — boots IntelliJ via `intellij-ide-starter`, installs the
  plugin, fires `RunSpectreAction`, and asserts every tagged Compose node shows up in
  `idea.log`.
- `:recording:check` on macOS — covers the Swift ScreenCaptureKit helper contract.

## Supported JVMs

The supported runtime set includes JBR 21, JBR 25, and Temurin LTS at the toolchain major.
JBR 21 is the local development default. The scheduled
[runtime matrix](.github/workflows/runtime-matrix.yml) also runs before a release.
Pull request CI uses Temurin 21. See the [runtime support tiers](docs/STABILITY.md#jvm-runtime-support-tiers)
and [version pins](.github/jbr-pins.env).

## CI

- [`ci.yml`](.github/workflows/ci.yml) — `:check` on Linux, every PR.
- [`macos-check.yml`](.github/workflows/macos-check.yml) — `:check` on macOS, broad path filter.
- [`windows.yml`](.github/workflows/windows.yml) — `:check` on Windows, broad path filter.
- [`macos.yml`](.github/workflows/macos.yml) — Swift helper build + `:recording:check`, gated
  on `recording/**` and `recording-macos/**`.
- [`ide-uitest.yml`](.github/workflows/ide-uitest.yml) — IDE-hosted UI test on macOS + Windows,
  gated on plugin / core / recording changes. `out/ide-tests/{installers,cache}` is cached
  between runs.
- [`validation-windows.yml`](.github/workflows/validation-windows.yml) —
  `:sample-desktop:validationTest*` on Windows, gated on `sample-desktop/**`. JUnit-XML-driven
  verifier so a Gradle/Compose protocol flake on shutdown can't hide a real failure.
- [`validation-linux.yml`](.github/workflows/validation-linux.yml) — same validation matrix
  on Linux under `xvfb-run` (real Xorg, no compositor in the loop), gated on the same
  `sample-desktop/**` filter shape.
- [`runtime-matrix.yml`](.github/workflows/runtime-matrix.yml) — weekly + release-gated
  `{JBR 21, JBR 25, Temurin LTS} × {macOS, Linux, Windows}` with contract corpus, agent
  attach (incl. mixed vanilla↔JBR on Linux), and Linux recording smoke under xvfb.

For consumer projects, see [Running on CI](docs/guide/ci.md) for the required test-JVM flags:
`java.awt.headless=false` for AWT, `skiko.renderApi=SOFTWARE_COMPAT` on GPU-less Linux runners,
and the macOS `apple.awt.UIElement=true` trade-offs for synthetic typing vs. clipboard paste.

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
