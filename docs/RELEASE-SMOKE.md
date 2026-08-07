# Release smoke

Hosted CI is necessary but not sufficient for a Spectre release. Several
high-value surfaces are **shipped on all three desktop OSes** while full
interactive e2e is **Linux/macOS-only** (or unit-only) on GitHub-hosted runners.
A **scoped pre-tag smoke** on real desktops closes that gap before anyone tags.

This page is part of the release process. Do not tag a release without a written
smoke plan for that version and a completed results table.

Related: [Publishing](PUBLISHING.md), [Capability matrix](guide/capability-matrix.md),
[Stability policy](STABILITY.md), [Testing policy](TESTING.md).

## When it runs

| Phase | What |
| --- | --- |
| **Before tag** | Author the scoped plan (below), run hard cells, paste results into the release PR / issue / chat. **Hard red → fix or drop the claim before tagging.** |
| **Tag / CI** | `release.yml` (runtime matrix, helpers, notarization, `verifyMavenLocalPublication`, Central upload, draft GH release). |
| **After promote** | Central checker + undraft + package channels (see [Publishing](PUBLISHING.md)). Optional quick re-smoke of CLI from the public release archive. |

Smoke does **not** replace green `main` CI or a green release workflow. It
covers paths CI cannot or does not execute fail-closed.

## How to scope a release (every time)

Produce a short plan for **this** version before running anything. Keep it in
the release notes draft, a PR description, or `.plans/<version>-smoke.md`
(gitignored). Minimum contents:

1. **Version** and **base tag** (e.g. `v0.3.0` → `v0.4.0`).
2. **Delta inventory** — features and packaging changes since the last tag:
   ```shell
   git log <previous-tag>..HEAD --oneline
   ```
   Map each user-visible theme to at least one smoke cell (or mark “CI-only /
   unit-covered”).
3. **Baseline regression set** (always include — see [Baseline hard cells](#baseline-hard-cells)).
4. **Delta hard cells** — new or changed paths that lack fail-closed multi-OS
   evidence (especially anything gated `@EnabledOnOs(LINUX, MAC)` while docs
   claim Windows).
5. **Soft cells** — optional; failure does not block the tag if the capability
   matrix already says Experimental / Not yet CI-executed.
6. **Results table** — OS × cell, pass/fail/N/A + one-line note.

### Signals that something must be a hard cell

Add a **hard** physical/desktop cell when **any** of these hold:

- Hosted tests use `@EnabledOnOs(OS.LINUX, OS.MAC)` (or skip Windows) for a
  surface docs claim on Windows.
- `CapabilityMatrix` marks the cell **Not yet CI-executed** or **Experimental**
  while release notes would still market it as “works.”
- New native helper packaging, notarization shape, or CLI distribution layout.
- New default-on test behaviour (e.g. failure artifacts) that consumers hit
  without opt-in.
- New attach/bootstrap path (inject, launch-and-attach, protocol version bump).
- Only unit/mocked tests exist for a CLI/daemon feature that users run live.

Use `testing/.../contract/CapabilityMatrix.kt` and
`docs/guide/capability-matrix.md` as the machine-checkable inventory of claimed
vs unproven cells.

### Permanent CI gaps (always consider for hard smoke)

These are structural, not one-release accidents:

| Surface | Hosted CI | Physical smoke |
| --- | --- | --- |
| CLI package-channel (Homebrew/Scoop) contracts | Structural on every Unix `check` (`python3`); install-semantics when Ruby present or under `CI` (issue #400) | Optional: install Ruby on a clean Linux box and run `./gradlew verifyHomebrewFormulaInstallSemantics` if claiming formula behaviour beyond CI |
| Agent attach + contract corpus (live UI) | Linux Xvfb + macOS desktop; Windows **transport/ACL** unit tests | **Windows headed desktop** (not SSH-only for capture) |
| Agent Windows UI e2e | Opt-in only: `-Pspectre.agent.attachE2e.allowWindows=true` | Run that property on Mattone-class boxes |
| Agent **inject** attach | Linux + macOS e2e | **Windows** inject fixture (no preinstalled core) |
| Launch-and-attach e2e | Linux + macOS | **Windows** direct `java` (and Gradle if claimed) |
| CLI daemon + live fixture | Linux + macOS | **Windows** release-shaped CLI binary |
| Daemon-owned window **recording** | macOS e2e when TCC allows; heavy unit mocks elsewhere | **macOS** live record; **Windows WGC** only from an **interactive console** (SSH sessions fail WGC / black pixels) |
| macOS `SpectreCaptureHelper.app` + TCC row | Helper build on PR; **notary on tag** | Extract from jar; `permissions check`; one SCK capture; notarize remains CI |
| Windows multi-file WGC helper | Packaging contract in Gradle + portal checker | Extract from jar; live capture from interactive console |
| Stock IntelliJ **inject** (no spectre-core in IDE) | Recipe + instrumented ide-uitest only | Manual recipe when that path is in release notes |
| Popup `compose.layers.type=WINDOW` on Windows | Skipped (upstream skiko) | Do not claim; optional recheck after runtime bumps |

**Windows session note:** A box can be “interactive” for console login yet still fail Screen
Capture / WGC under **SSH** (e.g. `0x80070424`, black Skiko pixels). Treat attach (semantics)
as SSH-safe when proven; treat **pixel capture / WGC** as requiring a local interactive
desktop session (RDP console or physical).

## Environments

| Host | Role |
| --- | --- |
| **macOS** (dev machine) | Primary: library, agent, CLI, SCK helper, permissions |
| **Windows** (logged-in desktop; optional `ssh` for non-WGC) | Prefer one-liner [Windows smoke script](#windows-one-liner-script) on the console user session |
| **Linux** (Hyper-V VM from Windows host, or native) | Agent attach under Xvfb or real display; package smoke; Wayland only if session is real |

Headless `windows-latest` is **not** a substitute for B9–B13.

Prefer **release-shaped** artifacts:

```shell
./gradlew check
./gradlew publishToMavenLocal -PVERSION_NAME=<version>-rc.smoke
# helpers: real platform builds where possible; stub only what the host cannot build
./gradlew verifyMavenLocalPublication   # flags per docs/PUBLISHING.md
```

CLI: package the host OS binary (`:cli:package*`) or use the draft release archive
after tag. Prefer the same helper layout consumers extract from Maven jars.

## Baseline hard cells

Run these for **every** release unless a cell is explicitly N/A with reason
(e.g. no Linux display for this cut and no Linux claim change).

| ID | OS | Cell | Pass criteria |
| --- | --- | --- | --- |
| B1 | macOS | `./gradlew check` | Green on release SHA |
| B2 | macOS | Maven Local core + testing consume | Compile/run a one-liner or sample against `-PVERSION_NAME=…` |
| B3 | macOS | Failure artifacts | Failing Spectre JUnit test writes `build/reports/spectre/**` (`capture.json` + PNG) |
| B4 | macOS | Atomic capture | In-process or CLI; `schemaVersion` matches shipped skill/docs |
| B5 | macOS | Agent attach (preinstalled core) | Non-empty windows + known tag; clean detach |
| B6 | macOS | Agent inject | Compose-only target; inject bootstrap; non-empty tree; detach |
| B7 | macOS | CLI daemon fixture | `spectre attach` (or package binary) → tree/capture against live fixture |
| B8 | macOS | Capture helper + TCC | Helper is app-bundle identity; `spectre permissions check`; one screenshot/record |
| B16 | macOS | CLI app seal (packaged) | Release-shaped or Homebrew `Spectre.app`: `codesign --verify --deep --strict` exit 0; `xcrun stapler validate` when notarized; launcher `--help` prints Usage (not silent exit 0 / Gatekeeper “damaged”). Prefer brew install of the formula after undraft when claiming Homebrew. See [#390](https://github.com/rock3r/spectre/issues/390). |
| B9 | Windows | Agent attach (core) | Same as B5; prefer opt-in e2e (recipe below). SSH OK if semantics-only. |
| B10 | Windows | Agent inject | Same as B6 — **does not ship “three OS agent” without this** |
| B11 | Windows | Launch-and-attach | Direct `java` launch + attach (Gradle optional) |
| B12 | Windows | CLI package + attach | Packaged `spectre` (or `packageWindowsX64`) attaches to fixture |
| B13 | Windows | WGC helper | Multi-file extract (SSH OK) **and** live capture from **interactive console** (not SSH) |
| B14 | Linux | Agent attach | Xvfb or real display; non-empty tree |
| B15 | any | Publication shape | `verifyMavenLocalPublication` (and portal checker once deployment exists) |

**Soft (every release if time; never block solely on focus flakes):**

| ID | Cell |
| --- | --- |
| S1 | Agent `typeText` / `pressKey` (known OS-focus flakes; matrix Experimental where noted) |
| S2 | Hot Reload settle e2e (needs HR-enabled target) |
| S3 | Stock IntelliJ inject recipe (manual) |
| S4 | JUnit 4 failure-artifact path (JUnit 5 is the hard path) |
| S5 | Full kill-target mid-record finalize |

## Results table (required)

Copy into the release record:

```text
Version: vX.Y.Z   Base: vA.B.C   SHA: <full>
Operator: <name>   Date: <ISO>

| ID  | OS      | Cell                         | Result | Note |
|-----|---------|------------------------------|--------|------|
| B1  | macOS   | check                        |        |      |
| B5  | macOS   | attach (core)                |        |      |
| B6  | macOS   | inject                       |        |      |
| B9  | Windows | attach (core)                |        |      |
| B10 | Windows | inject                       |        |      |
| …   |         |                              |        |      |
| D1  | …       | <delta cell>                 |        |      |

Hard failures: none | <list>
Soft notes: …
```

Result values: `pass` | `fail` | `n/a` (with reason).  
Empty hard cells or `n/a` for “no display” on a claimed platform **block the tag**.

## Baseline automation

Run the committed baseline runner from the repository root on macOS and Linux:

```shell
python3 scripts/release-smoke.py --version 0.5.0
```

Optional flags:

| Flag | Effect |
| --- | --- |
| `--base v0.4.1` | Record the previous release tag (default: latest git tag) |
| `--skip-check` | Skip `./gradlew check` (records hard `n/a` with reason) |
| `--out-dir PATH` | Report/log directory (default `build/smoke`) |
| `--overall-timeout SECS` | Wall-clock budget for the whole run (default 7200) |
| `--require-all-ids` | Require every stable scenario ID in `scripts/smoke_lib.py` |

On Linux it supplies `xvfb-run -a` when `DISPLAY` is unset and records `environment.displayMode`
as `xvfb-auto` or `real-display:$DISPLAY`. On Windows use the interactive PowerShell runner in the
next section (same stable scenario IDs and `schemaVersion` report shape once parity lands).

### Report artifacts

Every run writes under `build/smoke/` (or `--out-dir`):

| Path | Contents |
| --- | --- |
| `release-smoke.json` | Versioned machine-readable report (`schemaVersion`, full SHA, dirty flag, env, scenario rows) |
| `release-smoke.md` | Markdown results table for the release record |
| `<scenario-id>-<timestamp>.log` | Per-step stdout/stderr |

Report fields of note: `schemaVersion` (currently `1`), `version`, `base`, `sha` (full), `dirty`,
`environment.displayMode`, and `scenarios[]` with stable `id`, `result` (`pass` \| `fail` \| `n/a`),
optional `reason` (required for hard `n/a`), timings, and log path.

**Hard skips are fail-closed:** a hard scenario result of `n/a` without a non-empty `reason` is
treated as `fail`. Soft cells may use `hard: false`.

### Stable scenario IDs

Shared across macOS / Linux / Windows entrypoints (`scripts/smoke_lib.py` → `REQUIRED_SCENARIO_IDS`):

| ID | Cell |
| --- | --- |
| `preflight` | Environment / SHA / clean-tree preflight |
| `check` | `./gradlew check` |
| `junit-live` | Live JUnit failure artifacts/video and atomic capture |
| `agent-attach-core` | Agent attach with preinstalled core |
| `agent-contract-corpus` | Agent contract corpus |
| `agent-inject` | Injected attach without preinstalled core |
| `agent-launch-and-attach` | Launch-and-attach |
| `cli-packaged` | Release-shaped host CLI packaging |
| `cli-native-helper-layout` | Native-helper layout in package |
| `cli-user-flow` | Packaged CLI user flow (ps/attach/tree/input/screenshots/detach) |
| `mcp-sdk-flow` | Packaged MCP via official SDK / strict stdio |
| `host-native-recording` | Host native recording smoke |
| `maven-local-consumer` | Maven Local publication + fresh consumer |

The Unix runner currently wires the baseline subset end-to-end (preflight through
`cli-user-flow` / `mcp-sdk-flow`). Remaining IDs are expanded toward the full matrix in the
#398 harness completion; use `--require-all-ids` only when the full set is registered.

The cross-platform runner covers the stable baseline that should not be reinvented per release:

- environment/SHA/dirty-tree preflight recorded in the report
- the full `check` gate
- live JUnit validation (failure artifacts/video and capture/wait validation), forced with
  `--rerun-tasks --no-build-cache` so cache-only passes cannot skip UI work
- agent attach with preinstalled core and the contract corpus (separate scenario IDs)
- injected attach without core
- release-shaped packaged CLI construction
- packaged CLI fixture user flow + strict packaged MCP initialize / `tools/list` / `list_processes`

Each release still needs delta cells based on `git log <previous-tag>..HEAD`. Add reusable delta
coverage to the runner rather than leaving a one-release command only in chat.

### Manual cells that remain

These cannot currently be made portable and fail-closed by the baseline runner:

- **Windows WGC:** run `windows-release-smoke.ps1` in the logged-in user's native console terminal.
  SSH and even `PsExec -i` can use a service/elevated token that WGC rejects with `0x80070424` or
  `UnauthorizedAccessException`. Do not accept those runs as visual evidence.
- **macOS TCC and release seal:** grant Screen Recording to the actual helper identity, exercise one
  live SCK still/record, then verify the signed release app with `codesign --verify --deep --strict`,
  `spctl`, and `xcrun stapler validate`. A local ad-hoc app is not notarization evidence.
- **Wayland portal:** Xvfb proves X11 only. When Wayland is claimed or changed, run from a real
  Wayland desktop and record portal consent/cancel behaviour.
- **Homebrew/Scoop/public archives:** after the draft artifacts exist, install through the real
  package manager and rerun launcher/MCP smoke. Local packaging does not prove channel metadata.
- **Input focus/lock keys:** real Robot input uses global desktop state. Record focus failures and
  Caps Lock state; restore any modified lock state. Do not silently rerun a case mismatch.

Copy `build/smoke/release-smoke.json` and Windows' `windows-release-smoke.json` into the release
record. A report from a different SHA or user session is not evidence for the release SHA.

## Windows one-liner script

When you have a Windows desktop for a few minutes, run **one** command from the repo
root (interactive logon session preferred). Prefer **PowerShell 7+ (`pwsh`)** when
installed; both hosts need process-scoped `Bypass` under common **Restricted** policy.

**Preferred (pwsh):**

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1
```

**Windows PowerShell 5.1 / stock `powershell.exe`:**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1
```

`Bypass` applies only to that process; it does not weaken machine policy. A bare
`.\scripts\windows-release-smoke.ps1` often fails under the common default
`LocalMachine` **Restricted** policy, and UTF-8 multi-byte punctuation in the
script historically broke WinPS 5.1 parse (the script is kept **ASCII-only** so 5.1
can load it without a BOM).

From an absolute path (either host; adjust the repo path):

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File C:\src\spectre\scripts\windows-release-smoke.ps1
# or:
powershell -NoProfile -ExecutionPolicy Bypass -File C:\src\spectre\scripts\windows-release-smoke.ps1
```

What it does (no second terminal):

1. Opt-in agent UI e2e: attach + inject + launch-and-attach  
   (`-Pspectre.agent.attachE2e.allowWindows=true`, properly quoted for PowerShell)
2. WGC region smoke (`:recording:runWindowsGraphicsCaptureRegionSmoke`)
3. `:cli:packageWindowsX64` then packaged `spectre launch --once --app-name ComposeFixtureMain -- gradlew :agent-test-fixture:run`

Writes `build/smoke/windows-release-smoke.json` and exits non-zero on any failed step.

Flags:

| Flag | Effect |
| --- | --- |
| `-SkipAgentE2e` | Skip Gradle attach/inject/launch tests |
| `-SkipWgc` | Skip region recording smoke |
| `-SkipCli` | Skip package + `spectre launch` |
| `-SkipPackageCli` | Reuse existing `spectre.exe` (still runs launch) |

This is **manual, operator-driven** automation — not hosted CI. Use it so release smoke is
not multi-terminal faff.

## Recipes (common hard cells)

### Agent attach on Windows (product + opt-in e2e)

Product path (all OSes including Windows 10 1803+):

```kotlin
AgentAttach.attach(pid) // %TEMP% UDS + owner ACL on Windows; no named pipes
```

Optional **full UI e2e** on a physical Windows desktop (not hosted CI default):

```shell
# bash / zsh / cmd
./gradlew :agent:test \
  -Pspectre.agent.attachE2e.allowWindows=true \
  --tests '*AgentAttachIntegration*'
```

```powershell
# PowerShell: quote the -P argument (otherwise PS splits on dots after -Pspectre)
./gradlew :agent:test `
  "-Pspectre.agent.attachE2e.allowWindows=true" `
  --tests '*AgentAttachIntegration*'
```

See [Agent attach](guide/agent.md). Do not enable this property on headless
`windows-latest` as a fail-closed gate without a headed runner story.

### Agent inject (Linux / macOS / Windows)

Same intent as `AgentInjectAttachIntegrationTest`:

1. Build `spectre-agent-runtime` with nested `META-INF/spectre/inject-runtime.jar`.
2. Start the inject fixture **without** `spectre-core` on the child classpath
   (`InjectComposeFixtureMain` / classpath strip helpers in `:agent` tests).
3. Target JVM: `-XX:+EnableDynamicAgentLoading`, non-headless where UI is required.
4. Attacher: `AgentAttach.attach(pid, AttachOptions(agentJarPath = …))`.
5. Assert fixture window, non-empty nodes / test tag, clean detach.
6. Prefer stderr line that core was injected (not found preinstalled).

On Windows, attach, inject, and launch UI e2e share
`-Pspectre.agent.attachE2e.allowWindows=true` (or the [one-liner script](#windows-one-liner-script)).

### Launch-and-attach

`LaunchAndAttach` / `spectre launch` with a short-lived Compose fixture or
`java -jar`. Assert readiness stages complete and attach returns a usable
automator. Separate failures: process death vs attach vs empty tree. Covered by the
Windows smoke script’s agent e2e + packaged `spectre launch --once` step.

### macOS Capture Helper

1. Consume `spectre-recording-macos` from Maven Local or the release jar.
2. Confirm tree is `native/macos/SpectreCaptureHelper.app/...`, not a bare Mach-O.
3. `spectre permissions check` / `request` as needed; Settings row should name
   **Spectre Capture Helper**.
4. One still or short window/region capture via library or CLI.

### Windows WGC helper

1. Open `spectre-recording-windows` jar; confirm dual-arch multi-file layout
   (see packaging contract in `buildSrc` / [Publishing](PUBLISHING.md)). Portal
   checker and Gradle both assert multi-file basenames.
2. **Packaging** may be verified over SSH (extract + list files).
3. **Live capture:** prefer the [Windows smoke script](#windows-one-liner-script) or
   `:recording:runWindowsGraphicsCaptureRegionSmoke` on a logged-in desktop. SSH can
   work but sometimes yields WGC `0x80070424` or black frames.

## After a successful smoke

1. Tag only the smoked SHA (`vX.Y.Z`).
2. Watch `release.yml` (matrix, notary, publish).
3. Promote Central + undraft per [Publishing](PUBLISHING.md).
4. Optionally re-run B7/B12 against **public** CLI archives once package channels land.
5. If smoke discovered a permanent evidence gap, update
   `CapabilityMatrix` / the guide rather than leaving docs overselling CI.

## What smoke is not

- Not a full substitute for runtime-matrix or validation workflows.
- Not permission to enable flaky Windows e2e on headless GH runners.
- Not required for every PR — only for **release tags** (and RC tags if you cut them).
- Not a place to re-litigate Experimental APIs; soft-smoke them or keep them out of
  headline release claims.
