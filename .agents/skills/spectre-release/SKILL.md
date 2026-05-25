---
name: spectre-release
description: Use when preparing, validating, publishing, or undrafting a Spectre release.
metadata:
  internal: true
---

# Spectre Release

Use this skill for Spectre release work after the tag-driven release workflow has run.

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

## Finish

After Central reports `PUBLISHED`, undraft the GitHub release:

```bash
gh release edit v<version> --draft=false
```

Then verify the public Maven Central coordinates resolve from a clean consumer project.
