# Testing

## Expectations

Always add tests for behaviour changes.

1. Pure logic in `core` should get unit tests.
2. Serialisation, DTO mapping, and remote-call behaviour in `server` should get contract tests.
3. Recording code should separate testable pure logic from OS-specific integration and test the
   pure parts directly.
4. UI-facing spike work in `sample-desktop` may require manual validation, but any reusable logic
   extracted from it should still receive automated tests.
5. Run targeted tests while iterating, then finish with the broader relevant verification pass.
6. Before pushing, ensure the CI-shaped path is green locally when practical: `./gradlew check`.

## TDD Red-Green Cycle

Follow this strictly:

1. Write the test first.
2. Run it and confirm it fails for the missing behaviour.
3. Write the minimum implementation.
4. Re-run the targeted test to green.
5. Run the broader relevant suite.

Do not treat “I wrote the test after the code and it passes” as evidence. If a test was never red,
recreate the failure before considering the work done.

## Test Completeness Check

Before marking testing work complete:

- review every planned scenario and edge case
- confirm each one has a corresponding test or a deliberate manual-validation note
- fill the gaps before moving on

## Package-channel (Homebrew / Scoop) contracts

CLI package manifests are not optional docs — they are install paths. They are split so
a clean Linux host without Ruby can still run `./gradlew check` (issue #400):

| Gradle task | Host tools | On `check` when |
|---|---|---|
| `verifyCliPackageManifests` | `bash`, `python3` | Every Unix host |
| `verifyHomebrewFormulaInstallSemantics` | `bash`, `python3`, **Ruby** | Unix **and** (Ruby on `PATH` **or** `CI` is set) |
| `verifyCliPackageManifestHostDeps` | `bash`, `python3` | Every Unix host (regression: no undeclared Ruby on the structural path; missing Ruby on the semantics path fails with a preflight message) |

Scripts:

- structural/generator: `.github/scripts/test-generate-cli-package-manifests.sh`
- install semantics (Ruby): `.github/scripts/test-homebrew-formula-install-semantics.sh` → `.rb`
- host-dep regression: `.github/scripts/test-cli-package-manifest-host-deps.sh`

**Structural** checks generate fixture manifests, assert Scoop JSON + formula text contracts
(wrapper bin entry, dual-layout `Spectre.app` discovery snippets, install-body alignment
between generator output and committed `Formula/spectre.rb`). They must **not** invoke Ruby.

**Install-semantics** evaluate the formula's real `app = Dir[...]` expression against nested
and Homebrew-stripped layouts and require a wrapper (not a Roast `bin.install_symlink`).
They need Ruby. Locally, if Ruby is absent the task is skipped (structural + host-dep
guards still run). Under CI (`CI` env set), the task always runs and **fails closed** with
an actionable preflight if Ruby is missing:

```text
error: 'ruby' is required for verifyHomebrewFormulaInstallSemantics
  Install: apt install ruby  |  brew install ruby  |  …
```

GitHub `ubuntu-latest` typically ships Ruby; if that ever changes, install Ruby in
`.github/workflows/ci.yml` before `./gradlew check` (do not re-merge semantics into the
structural script). For a full local package-channel gate on Linux: `apt install ruby`
(or equivalent), then `./gradlew verifyHomebrewFormulaInstallSemantics`.

## Cross-Boundary Contract Tests

When a feature spans a boundary, add at least one test that exercises the real boundary shape.

Examples for Spectre:

- HTTP request/response payloads in `server`
- compound node identity formatting and parsing
- coordinate conversion behaviour across Compose/AWT/Robot units
- native helper invocation contracts for recording

These tests should use the real payload or coordinate format rather than a hand-crafted idealized
version. Unit tests for internal math are necessary, but boundary tests catch drift between layers.

## Running `./gradlew check` Locally

`./gradlew check` is the pre-push gate, so it must stay runnable on a machine you are also using.

Two test paths need the whole desktop to themselves. Both send real `java.awt.Robot` key events at
a spawned Compose window, which only works while that window owns OS keyboard focus. A terminal, an
editor, or a notification taking focus mid-run used to fail them.

| Path | Where |
|---|---|
| `typeText` into the fixture text field | `AgentAttachIntegrationTest` (`:agent`) |
| `press-key-tab-after-focus` corpus scenario | `AutomatorContractCorpus`, run by `AgentContractCorpusTest` (`:agent`) |

One gate governs both: `RealKeyboardGate` in `:testing`. By default the paths run on CI (`CI=true`
in the environment) and are **skipped on developer machines**, where each path prints to stderr
exactly what it skipped. Everything else runs on every host — attach, `windows()`,
`findByTestTag`, `click()`, `doubleClick()`, `swipe()`, `scrollWheel()`, window identity, and
screenshots.

When the gate is off, the corpus records `press-key-tab-after-focus` as passed with the detail
`skipped:real-keyboard-gate-off` and never touches the driver. Raising and clicking the fixture
window are themselves focus-stealing, so a gated-off run has to do nothing at all rather than try
and tolerate the failure.

Run the keyboard paths yourself on an idle desktop:

```shell
# bash / zsh / cmd
./gradlew check -Pspectre.agent.realKeyboard=true
```

```powershell
# PowerShell: quote -P… so the shell does not split on the property name
./gradlew check "-Pspectre.agent.realKeyboard=true"
```

Pass `-Pspectre.agent.realKeyboard=false` to turn them off on CI. The `:agent`, `:server`, and
`:testing` test tasks all forward the property to their workers, and both the property and the `CI`
environment variable are task inputs, so switching modes never reuses a cached result from the
other mode.

When the paths do run their assertions are unchanged: hosted macOS CI still tolerates a lost OS
focus handoff, Linux Xvfb stays fail-closed, and a local opt-in run fails loudly so a real keyboard
regression is visible.

## Manual Spike Validation

Some concerns still need live manual verification even with good automated tests:

- Retina/HiDPI coordinate accuracy
- popup discovery across different layer modes
- Robot focus behaviour and click targeting
- recording permission and capture behaviour on macOS
- AWT/Compose Desktop painting and `Robot` capture when the test JVM runs under a macOS
  `sandbox-exec` profile; see [Running on CI](guide/ci.md#macos-sandbox-exec-runners)

Use `sample-desktop` to make those checks reproducible. If a manual validation step is required
for a change, note it explicitly in the final report.

## Coroutine Testing

- Prefer `runTest` for coroutine-based logic **that is not Spectre UI automation**.
- Prefer `runSpectreTest` (from `:testing`) for Spectre UI tests: real wall-clock `delay`
  for longClick/swipe/paste settle, plus unfinished-child leak detection. Do **not** use
  `runTest` for Spectre interaction tests (virtual time collapses internal delays).
  Plain `runBlocking` remains a valid fallback.
- Avoid real sleeps when a deterministic scheduler or fake clock will do.
- Cancel/close long-lived scopes created in tests.
- If asynchronous behaviour cannot be made deterministic, isolate the nondeterminism behind a small
  interface and test the decision logic separately.
