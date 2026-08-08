---
name: spectre-release
description: Use when preparing, validating, publishing, or undrafting a Spectre release.
metadata:
  internal: true
---

# Spectre Release

Use this skill for Spectre release work: pre-tag smoke, tag-driven workflow, Central
promotion, and undrafting.

## Pre-tag release smoke (required)

**Before** `git tag` / pushing a `v*` tag, complete a scoped smoke per
[docs/RELEASE-SMOKE.md](../../../docs/RELEASE-SMOKE.md):

1. Diff since previous tag + capability matrix → baseline hard cells + **delta** hard cells.
2. Run the committed one-command harness on each OS (shared stable scenario IDs / `schemaVersion`
   report under `build/smoke/`):
   - **macOS / Linux:** `python3 scripts/release-smoke.py --version <X.Y.Z>`
     → `build/smoke/release-smoke.json` + `.md`
   - **Windows (interactive desktop for WGC):**
     - `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1` when PowerShell 7+ is installed
     - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1` on stock WinPS 5.1
     → `build/smoke/windows-release-smoke.json` + `.md`
   - Optional wiring check (not a GO): `--preflight-only` / `-PreflightOnly`
3. Produce a results table from the reports. **Hard red or empty hard cells → do not tag.**
4. Soft cells (Experimental matrix, focus flakes, Hot Reload) may be notes only.
5. Manual residual only for TCC/notarization/seal, real Wayland portal, public Homebrew/Scoop/archive,
   focus/lock keys, multi-monitor/HiDPI, stock IntelliJ — see RELEASE-SMOKE residual list.

Every release needs its own scoped plan (new features + permanent CI gaps such as
Windows agent inject). Do not skip smoke because `main` CI is green. To add a reusable
scenario ID, follow **Adding a scenario ID** in [docs/RELEASE-SMOKE.md](../../../docs/RELEASE-SMOKE.md)
(`REQUIRED_SCENARIO_IDS` + both entrypoints + contract tests) — do not leave commands only in chat.

## Central Portal Check

Use the release checker before publishing a validated Central Portal deployment:

```bash
scripts/central_portal_check.py status \
  --deployment-id <deployment-id>

scripts/central_portal_check.py validate \
  --deployment-id <deployment-id> \
  --version <version>
```

The script reads credentials from the 1Password item `Spectre Maven Central Portal` via `op`.
It can also use `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` or the matching
`ORG_GRADLE_PROJECT_mavenCentralUsername` / `ORG_GRADLE_PROJECT_mavenCentralPassword`
environment variables when running in CI-like shells.

The read-only validation checks:

- deployment status and expected Spectre component purls
- expected Maven artifact files and `.asc` signatures for all published modules
- file sizes from the Central validated-deployment download endpoint
- recording helper jar contents
- `spectre-agent` does not contain `AttachSpike`
- `spectre-agent-runtime` has the Java agent manifest and no forbidden bundled classes

## Publishing

Publishing is intentionally not automatic.

Prefer the Central Portal UI for the first few releases. If the API path is used, run the
script's `publish` command only after `validate` is green:

```bash
scripts/central_portal_check.py publish \
  --deployment-id <deployment-id> \
  --version <version>
```

The command requires typing this exact confirmation:

```text
publish <deployment-id> <version>
```

Do not pass `--yes` unless the user explicitly asks for non-interactive publishing in the
current task.

## Capture schema skill

If the release changes `CaptureDocument.SCHEMA_VERSION` / `capture.json`, bump the
**`spectre-capture`** skill (`skills/spectre-capture/SKILL.md` + `package.json`) and the
user-guide page `docs/guide/capture.md` in the same release.

## macOS helper bundle signing (#191)

Release tags must produce a **Developer ID signed, notarized, and stapled**
`SpectreCaptureHelper.app` (not a bare Mach-O). Confirm:

- [ ] `mac-helper` job ran with `-PnotarizeScreenCaptureKitHelper`
- [ ] Artifact is the app tree under `SpectreCaptureHelper.app/`
- [ ] Local or CI log shows `stapler staple` / `stapler validate` success
- [ ] `codesign --verify --deep --strict` on the app
- [ ] Docs: [docs/NOTARIZATION.md](../../../docs/NOTARIZATION.md) local-dev vs release table still accurate

## Finish

After Central reports `PUBLISHED`, undraft the GitHub release (only after smoke +
workflow green + portal validate):

```bash
gh release edit v<version> --draft=false
```

GitHub releases should point readers to Maven Central for artifacts instead of attaching
a partial jar set. Then verify the public Maven Central coordinates resolve from a clean
consumer project.
