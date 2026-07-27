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
| Agent attach + contract corpus (live UI) | Linux Xvfb + macOS desktop | **Windows desktop** (interactive session) |
| Agent **inject** attach | Linux + macOS e2e | **Windows** inject fixture (no preinstalled core) |
| Launch-and-attach e2e | Linux + macOS | **Windows** direct `java` (and Gradle if claimed) |
| CLI daemon + live fixture | Linux + macOS | **Windows** release-shaped CLI binary |
| Daemon-owned window **recording** | macOS e2e when TCC allows; heavy unit mocks elsewhere | **macOS** live record; **Windows** WGC if display allows |
| macOS `SpectreCaptureHelper.app` + TCC row | Helper build on PR; notary on **tag** | Extract from jar; `permissions check`; one SCK capture |
| Windows multi-file WGC helper | Packaging contract in Gradle | Extract from jar; region/window smoke |
| Stock IntelliJ **inject** (no spectre-core in IDE) | Recipe + instrumented ide-uitest only | Manual recipe when that path is in release notes |
| Popup `compose.layers.type=WINDOW` on Windows | Skipped (upstream skiko) | Do not claim; optional recheck after runtime bumps |

## Environments

| Host | Role |
| --- | --- |
| **macOS** (dev machine) | Primary: library, agent, CLI, SCK helper, permissions |
| **Windows** (`ssh mattone` or equivalent headed box) | Agent attach/inject/launch, CLI package, WGC helper |
| **Linux** (Hyper-V VM from Windows host, or native) | Agent attach under Xvfb or real display; package smoke; Wayland only if session is real |

Use an interactive desktop on Windows for agent/CLI UI smokes. Headless
`windows-latest` is **not** a substitute.

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
| B9 | Windows | Agent attach (core) | Same as B5 on headed desktop |
| B10 | Windows | Agent inject | Same as B6 — **does not ship “three OS agent” without this** |
| B11 | Windows | Launch-and-attach | Direct `java` launch + attach (Gradle optional) |
| B12 | Windows | CLI package + attach | Packaged `spectre` (or `packageWindowsX64`) attaches to fixture |
| B13 | Windows | WGC helper | Multi-file extract from jar; one region or window capture if display allows |
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

## Recipes (common hard cells)

### Agent inject (Linux / macOS / Windows)

Same intent as `AgentInjectAttachIntegrationTest`:

1. Build `spectre-agent-runtime` with nested `META-INF/spectre/inject-runtime.jar`.
2. Start the inject fixture **without** `spectre-core` on the child classpath
   (`InjectComposeFixtureMain` / classpath strip helpers in `:agent` tests).
3. Target JVM: `-XX:+EnableDynamicAgentLoading`, non-headless where UI is required.
4. Attacher: `AgentAttach.attach(pid, AttachOptions(agentJarPath = …))`.
5. Assert fixture window, non-empty nodes / test tag, clean detach.
6. Prefer stderr line that core was injected (not found preinstalled).

On Windows, prefer running the real test class with a **local-only** OS gate
override for smoke; do not commit enabling Windows on headless hosted CI without
a headed runner story.

### Launch-and-attach

`LaunchAndAttach` / `spectre launch` with a short-lived Compose fixture or
`java -jar`. Assert readiness stages complete and attach returns a usable
automator. Separate failures: process death vs attach vs empty tree.

### macOS Capture Helper

1. Consume `spectre-recording-macos` from Maven Local or the release jar.
2. Confirm tree is `native/macos/SpectreCaptureHelper.app/...`, not a bare Mach-O.
3. `spectre permissions check` / `request` as needed; Settings row should name
   **Spectre Capture Helper**.
4. One still or short window/region capture via library or CLI.

### Windows WGC helper

1. Open `spectre-recording-windows` jar; confirm dual-arch multi-file layout
   (see packaging contract in `buildSrc` / [Publishing](PUBLISHING.md)).
2. Extract and run a short region or window capture
   (`:recording:runWindowsGraphicsCaptureRegionSmoke` or equivalent consumer path).

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
