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
| `--skip-maven-local` | Skip Maven Local publish + consumer (hard `n/a` with reason) |
| `--skip-recording` | Skip host native recording smoke (hard `n/a` with reason) |
| `--preflight-only` | Run only preflight; remaining required IDs are hard `n/a` with reason `preflight-only mode; scenario not executed`. Validates report schema + matrix wiring without a multi-hour GO. **Not a release smoke GO.** |

On Linux it supplies `xvfb-run -a` when `DISPLAY` is unset and records `environment.displayMode`
as `xvfb-auto` or `real-display:$DISPLAY`. On Windows use the interactive PowerShell runner in the
next section (shared stable scenario IDs and `schemaVersion` report shape).

**Xvfb ≠ Wayland:** auto-Xvfb proves the X11 path only. Real portal consent/cancel requires a real
Wayland session (manual cell below).
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
treated as `fail`. Soft cells may use `hard: false`. Missing a required scenario ID is also fail
(Unix: `validate_report(..., required_ids=REQUIRED_SCENARIO_IDS)`; Windows: `RequiredScenarioIds`
completeness check before exit).

### schemaVersion bump policy

`schemaVersion` is defined once in `scripts/smoke_lib.py` as `SCHEMA_VERSION`. The Windows
entrypoint (`scripts/windows-release-smoke.ps1`) reads that constant at report time via
`Get-SmokeSchemaVersion` — do **not** hardcode a parallel integer in the PowerShell script.
**Bump only when report field names or semantics change incompatibly** (rename/remove a field,
change meaning of an existing value). Additive optional fields and new scenario IDs do **not**
require a bump — keep existing field names stable so older report consumers still parse. When you
bump:

1. Update `SCHEMA_VERSION` in `smoke_lib.py` only (Windows picks it up automatically).
2. Extend `validate_report` / contract tests for the new shape.
3. Note the bump in the release record so operators do not compare v1 and v2 rows as identical.

### Adding a scenario ID (per-release delta or permanent baseline)

Do **not** leave a one-release command only in chat. To add a reusable cell:

1. Append a stable kebab-case ID to `REQUIRED_SCENARIO_IDS` in `scripts/smoke_lib.py` (or document
   it as a **soft / delta-only** cell if it is not required on every cut — soft cells use
   `hard: false` and need not be in `REQUIRED_SCENARIO_IDS`).
2. Register the same ID on **both** entrypoints:
   - Unix: `scripts/release-smoke.py` (`run_scenario` / `run_callable_scenario` / explicit
     hard `n/a` with reason).
   - Windows: `scripts/windows-release-smoke.ps1` (`RequiredScenarioIds` array + step that emits
     the row; environment-impossible → hard `n/a` with reason, never silent omit).
3. Extend contract tests:
   - `.github/scripts/test-release-smoke-scripts.py` (`test_required_scenario_ids_are_stable`,
     wiring asserts, any new fail-closed rule).
   - `.github/scripts/test-windows-release-smoke-script.sh` (ID presence + any new policy needles).
4. Document the cell in the stable scenario table above and in the automated-vs-manual matrix.
5. Prefer forcing live UI with `gradle_ui_force_args()` / `--rerun-tasks --no-build-cache` when the
   cell is UI-backed so cache-only cannot fake PASS.

Per-release **delta** cells that will not stay permanent may live in
`.plans/<version>-smoke.md` as manual recipes; promote them into the runner when they repeat.
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
| `mcp-sdk-flow` | Packaged MCP attach/op/detach lifecycle + strict stdio |
| `host-native-recording` | Host native recording smoke |
| `maven-local-consumer` | Maven Local publication + fresh consumer |
| `portal-token-warmup` | Linux Wayland: one interactive ScreenCast grant at run start, then reuse the stored restore token. Hard `n/a` on macOS/Windows/Xvfb. |

The Unix runner registers **every** required ID end-to-end (fail-closed). Environment-impossible
cells must be explicit hard `n/a` with reason — never a silent omit or fake `pass`.

The cross-platform runner covers the stable baseline that should not be reinvented per release:

- environment/SHA/dirty-tree preflight recorded in the report
- the full `check` gate
- live JUnit validation (failure artifacts/video and capture/wait validation), forced with
  `--rerun-tasks --no-build-cache` so cache-only passes cannot skip UI work
- agent attach with preinstalled core, contract corpus, inject, and launch-and-attach
- release-shaped packaged CLI construction + host native-helper layout checks
- packaged CLI fixture user flow (ps/attach/find/input/fail-closed window screenshot/fullscreen/detach)
- packaged MCP via official SDK e2e (**attach → op → detach → session-gone** required for hard
  pass) + strict stdio (version / tools/list including `detach` / unknown-session detach `isError`)
- host native recording smoke (macOS SCK region / Linux X11; Windows WGC via interactive PS script)
- Maven Local `verifyMavenLocalPublication` + fresh consumer jar resolve
- Linux Wayland `portal-token-warmup`: one `:recording:runWaylandPortalSmoke` with a pinned `SPECTRE_WAYLAND_HELPER` + `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR` under `build/smoke/wayland-restore-tokens/`. Approve **Share** + **Remember** once; later Gradle cells inherit that env so they reuse the token instead of prompting again. Xvfb / macOS / Windows record hard `n/a`.

Each release still needs delta cells based on `git log <previous-tag>..HEAD`. Add reusable delta
coverage to the runner rather than leaving a one-release command only in chat.

### Automated vs manual (0.5.0 harness)

| Surface | Automated entrypoint | Manual recipe only |
| --- | --- | --- |
| Preflight / check / agent attach·inject·launch / CLI package / MCP SDK / Maven Local | `release-smoke.py` (macOS/Linux); Windows PS shares IDs | — |
| Live JUnit failure artifacts/video + atomic capture | `release-smoke.py` → `junit-live` | Windows: run on macOS/Linux baseline (hard `n/a` on Windows entrypoint with reason) |
| Host native recording | macOS SCK + Linux X11 in `release-smoke.py`; WGC in Windows PS when **interactive** | SSH WGC is N/A (not PASS) |
| TCC / notarization / app seal | — | macOS recipes below |
| Real Wayland portal | — | Real Wayland session (Xvfb ≠ Wayland) |
| Public Homebrew / Scoop / archive installs | — | After draft release undraft |
| Focus / lock keys / multi-monitor / HiDPI | Soft / env-dependent | Operator notes |
| Stock IntelliJ inject | Soft recipe | Manual when claimed in notes |

### On-demand pre-tag macOS notarization

The tag workflow notarizes the macOS helper and CLI bundles, but B8/B16 need release-signed
artifacts **before** the tag. Run the artifact-only workflow against the intended release SHA after
that SHA has landed on `main`:

```shell
sha="$(git rev-parse HEAD)"
run_url="$(gh workflow run notarize-macos.yml \
  --ref main \
  -f ref="$sha" \
  -f version=0.5.0-rc.smoke)"
run_id="${run_url##*/}"
echo "$run_url"
```

The workflow rejects commits that are not reachable from the repository's default branch. It calls
the same reusable signing jobs as `release.yml`, but it never publishes to Central or creates a
GitHub release. After the run finishes, download its two artifacts:

```shell
gh run watch "$run_id"
gh run download "$run_id" -n mac-helper -D build/notarized-smoke/mac-helper-artifact
gh run download "$run_id" -n mac-cli-bundles -D build/notarized-smoke/mac-cli-bundles
mkdir -p build/notarized-smoke/mac-helper
tar -xzf build/notarized-smoke/mac-helper-artifact/SpectreCaptureHelper.app.tar.gz \
  -C build/notarized-smoke/mac-helper
test -x \
  build/notarized-smoke/mac-helper/SpectreCaptureHelper.app/Contents/MacOS/spectre-screencapture
```

The helper is intentionally archived before artifact upload because GitHub normalizes raw artifact
file modes. Use `build/notarized-smoke/mac-helper/SpectreCaptureHelper.app` for the B8 TCC/live-SCK
check. For B16, run the committed verifier on the host-architecture archive (replace `macosArm64` with
`macosX64` on Intel):

```shell
bash .github/scripts/verify-macos-cli-bundle.sh \
  build/notarized-smoke/mac-cli-bundles/spectre-macosArm64.zip \
  0.5.0-rc.smoke
```

Record the workflow run URL and exact resolved SHA in the release smoke results. The workflow proves
Developer ID signing, notarization, stapling, and archive seal; the local live capture is still
required because GitHub-hosted macOS runners cannot grant Screen Recording TCC.

### Manual cells that remain

These cannot currently be made portable and fail-closed by the baseline runner:

- **Windows WGC:** run `windows-release-smoke.ps1` in the logged-in user's native console terminal.
  SSH and even `PsExec -i` can use a service/elevated token that WGC rejects with `0x80070424` or
  `UnauthorizedAccessException`. The Windows harness records hard `n/a` with reason when
  `displayMode` is `windows-ssh` — do **not** treat SSH runs as visual PASS evidence.
- **macOS TCC and release seal:** grant Screen Recording to the actual helper identity, exercise one
  live SCK still/record, then verify the signed release app with `codesign --verify --deep --strict`,
  `spctl`, and `xcrun stapler validate`. A local ad-hoc app is not notarization evidence.
- **Wayland portal:** Xvfb proves X11 only. On a real Wayland desktop the Unix harness now runs
  `portal-token-warmup` first (`:recording:runWaylandPortalSmoke`) and pins
  `SPECTRE_WAYLAND_HELPER` + `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR` for the rest of the run. Approve
  **Share** + **Remember** once at that prompt; later ScreenCast cells should reuse the token.
  JBR `java.awt.Robot` / Remote Desktop prompts are a separate app identity and may still appear.
- **Homebrew/Scoop/public archives:** after the draft artifacts exist, install through the real
  package manager and rerun launcher/MCP smoke. Local packaging does not prove channel metadata.
- **Input focus/lock keys:** real Robot input uses global desktop state. Record focus failures and
  Caps Lock state; restore any modified lock state. Do not silently rerun a case mismatch.
- **Multi-monitor / HiDPI / stock IntelliJ:** environment-dependent delta cells; keep as recipes when
  the release delta claims them.

Copy `build/smoke/release-smoke.json` (+ `.md`) and Windows' `windows-release-smoke.json` (+ `.md`)
into the release record. A report from a different SHA or user session is not evidence for the
release SHA.

### Residual gaps for the 0.5.0 cut

- **Harness baseline (#398):** committed one-command entrypoints + shared `REQUIRED_SCENARIO_IDS`
  + fail-closed reports are on main. Operator multi-OS proof for a given SHA should attach
  `build/smoke/release-smoke.json` / `.md` (macOS + Linux) and `windows-release-smoke.json` /
  `.md` (Windows) — not chat-only PASS. Use `--preflight-only` / `-PreflightOnly` only to
  validate wiring; it is **not** a release GO.
- **WGC / host-native-recording on Windows:** hard pass only from an **interactive desktop**
  console. SSH runs must record hard `n/a` with reason (`displayMode: windows-ssh`) — never
  treat SSH as visual PASS evidence.
- **`agent-attach-core` on Windows SSH:** `AgentAttachIntegration` e2e includes WGC node
  screenshots (#362). Under `windows-ssh` the harness records hard `n/a` with reason (same class
  as WGC recording). Re-run from an interactive console for hard PASS of attach screenshot parity.
  Inject / launch-and-attach remain SSH-runnable semantics cells.
- **MCP lifecycle (#399 / #414)** is a **hard cell** on all three entrypoints when packaging is
  claimed: Unix `release-smoke.py` and Windows `windows-release-smoke.ps1` both require
  attach → cheap op → detach → session-gone (DaemonFixture MCP e2e) plus strict
  `mcp-stdio-smoke.py` (tools/list includes `detach`; unknown detach is `isError`). Packaging and
  the MCP Gradle leg bake `-PVERSION_NAME=<smoke --version>` so `serverInfo.version` matches
  `--expected-version`. Windows fixture e2e is opt-in with
  `-Pspectre.agent.attachE2e.allowWindows=true` (hosted `windows-latest` stays skip-safe).
  Environment-impossible cases (no display, missing Python for the stdio leg, packaging skipped)
  remain hard `n/a` or `fail` with an explicit reason — never a fake PASS.
- Optional `mcp-stdio-smoke.py --attach-pid <pid>` proves the same lifecycle over raw stdio when a
  live fixture PID is available; without a PID the script still fails closed on tools + unknown
  detach. Release hard pass always rests on the fixture e2e leg for session-gone, not tools/list
  alone.
- **#386** (Windows packaged `launch --once` + Gradle `JVM_ATTACHABLE`): product default for
  Gradle-ish launches expands JVM_ATTACHABLE to 120s (matching agent e2e). The Windows one-liner
  `cli-user-flow` may still use Gradle with `--app-name ComposeFixtureMain`; prefer a healthy
  UP-TO-DATE fixture build so cold daemon start alone fits the budget. Prod-like launch remains
  the troubleshooting recommendation when Gradle is slow or flaky.
- **Manual residual (not auto-green):** TCC / notarization / app seal; first Wayland ScreenCast
  consent during `portal-token-warmup` (later cells reuse the restore token); public
  Homebrew/Scoop/archive after undraft; focus/lock keys; multi-monitor / HiDPI; stock IntelliJ
  inject. Xvfb still does not prove Wayland portal behaviour.

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

What it does (no second terminal), using the **same stable scenario IDs** as
`scripts/release-smoke.py` / `scripts/smoke_lib.py`:

1. `preflight` + optional `check`
2. Agent UI e2e: `agent-attach-core`, `agent-inject`, `agent-launch-and-attach`
   (`-Pspectre.agent.attachE2e.allowWindows=true`, properly quoted for PowerShell)
3. `host-native-recording` — WGC region smoke only when `displayMode` is interactive
   (SSH → hard `n/a` with reason, never fake PASS)
4. `cli-packaged` / `cli-native-helper-layout` / packaged `spectre launch --once` as `cli-user-flow`
5. optional `maven-local-consumer` via `verifyMavenLocalPublication`

Writes versioned `build/smoke/windows-release-smoke.json` + `.md` (`schemaVersion` from
`smoke_lib.SCHEMA_VERSION`, full SHA,
dirty flag, `environment.displayMode`) and exits non-zero on any hard `fail`.

Flags:

| Flag | Effect |
| --- | --- |
| `-Version 0.5.0` | Release version recorded in the report (default `0.5.0`) |
| `-Base v0.4.1` | Previous tag recorded in the report |
| `-SkipCheck` | Skip `./gradlew check` (hard `n/a` with reason) |
| `-SkipAgentE2e` | Skip Gradle attach/inject/launch tests |
| `-SkipWgc` | Skip region recording smoke |
| `-SkipCli` | Skip package + `spectre launch` |
| `-SkipPackageCli` | Reuse existing `spectre.exe` (still runs launch) |
| `-SkipMavenLocal` | Skip Maven Local publication smoke |
| `-PreflightOnly` | Preflight + full required-ID matrix as hard `n/a` with reason (schema self-check). **Not a release GO.** |
This is **manual, operator-driven** automation — not hosted CI. Use it so release smoke is
not multi-terminal faff. Prefer the logged-in interactive console for any WGC cell.

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
