# AGENTS.md

## Project Overview

Spectre is a JVM-first Kotlin project for automating live Compose Desktop UIs.

The planned system spans:

- semantics tree inspection across windows and popup roots
- Robot-backed real OS input
- optional cross-JVM/server access
- screenshot and recording support
- standalone Compose Desktop apps and IntelliJ/Jewel-hosted UIs

The repository is currently in bootstrap mode. Favor clean architecture, reproducible tests, and
careful prior-art research over premature implementation.

If this file, the spike gist, and the checked-in code ever disagree, treat the checked-in code and
repo docs as the current source of truth.

## Source Of Truth Docs

Keep this file focused on operating rules. Use the docs below for implementation guidance:

### Contributor / agent-facing

| Document | Use it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module map, dependency direction, and project invariants |
| [docs/TESTING.md](docs/TESTING.md) | TDD flow, contract tests, and validation expectations |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | File placement, coding style, and git/build workflow |
| [docs/STATIC-ANALYSIS.md](docs/STATIC-ANALYSIS.md) | Detekt, ktfmt, and CI-backed quality expectations |
| [docs/RECORDING-LIMITATIONS.md](docs/RECORDING-LIMITATIONS.md) | Per-platform recording trade-offs, frame drop behaviour, audio caveats |
| [docs/DOCS-STYLE.md](docs/DOCS-STYLE.md) | Style guide and verification checklist for the user-facing docs site (excluded from the published site itself) |
| [Compose Desktop automator spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8) | External design notes and open spike questions |

### User-facing

| Document | Use it for |
|---|---|
| [docs/index.md](docs/index.md) + [docs/guide/](docs/guide/) | End-user user guide (installation, getting started, automator concepts, selectors, input, synchronization, JUnit integration, recording, cross-JVM, IntelliJ-hosted Compose, troubleshooting) |
| [mkdocs.yml](mkdocs.yml), [requirements-docs.txt](requirements-docs.txt), [.github/workflows/docs.yml](.github/workflows/docs.yml) | Docs site config — MkDocs Material, deployed to GitHub Pages at <https://spectre.sebastiano.dev> |

The contents of `docs/` are published as a MkDocs site, so all internal links inside
`docs/` must resolve relative to `docs/` itself. Files outside `docs/` (workflows,
sources at repo root) must be linked as absolute `https://github.com/rock3r/spectre/...`
URLs, or the `mkdocs build --strict` step in CI will fail.

## Non-Negotiables

### TDD First

Write the failing test before implementation, then make it pass with the minimum production change.

1. Write the test.
2. Run the targeted test.
3. Prove it fails before editing production code.
4. Implement the minimum fix.
5. Re-run the targeted test and then the broader relevant suite.

Full policy: [docs/TESTING.md](docs/TESTING.md).

### Worktree Policy

Before writing tracked files from a clean `main` checkout, check whether isolation already exists.
If you are already inside a worktree or already on a non-main branch, keep working there.
Otherwise, prefer asking whether to create a worktree before starting feature work.

Plans belong in `.plans/` at the repo root, which should stay gitignored.

Use the local `using-git-worktree` skill when setting up an isolated workspace.

### Worktree pitfalls

Lessons learned from working in git worktrees on this project.

#### ktfmt version mismatch

The project's Gradle plugin bundles a specific ktfmt formatter version. If you have a
different `ktfmt` CLI installed locally (e.g., via Homebrew), the two may format
differently. Specifically:

- The CLI may leave unused imports that the Gradle plugin removes.
- Line-wrapping thresholds may differ slightly.

**Always verify with `./gradlew check`, not the CLI.** CI uses Gradle.

#### Rebase silently reverts fixes

When rebasing a long-lived branch, git may silently revert changes that conflict with
upstream. After every rebase:

1. Find the merge base: `git merge-base HEAD origin/main` (note the commit hash).
2. List your branch's changed files: `git diff --name-only <merge-base-hash>`.
3. Verify your changes survived: `git diff origin/main -- <those-files>`.
4. Re-run `./gradlew check` locally before pushing.
5. Pay special attention to files that were modified by both your branch and main.

Common casualties during rebase:

- Conditional guards (`if (x > 0)` wrappers around existing code).
- Import removals (git re-adds them from main's version).
- Label/text changes that main also touched.

#### Pre-push checklist

Before every push from a worktree:

- [ ] `./gradlew check` passes (this is the CI gate — detekt + ktfmt + tests).
- [ ] After rebase: verified key changes survived (see above).

For faster iteration *before* the final push, target individual checks:

- `./gradlew :test --tests "*TestName*"` — run a specific failing test.
- `./gradlew ktfmtCheckMain` — verify formatting only.
- `./gradlew detektMain` — verify detekt only.

**Always run `./gradlew check` before the actual push.**

### Regressions And Scope Discipline

- Keep changes scoped to the user’s request.
- Treat unrequested behavioral changes as regressions.
- Refactors, cleanup, and test-only work must not change behavior unless the user explicitly asks.
- If implementation is intentionally phased, make the incomplete behavior explicit to the user and
  document what remains.

### Upstream And Template Breakage

If a dependency bump, template update, or pulled upstream change breaks the project, surface the
breakage clearly. Small, obvious bootstrap fixes should usually be handled directly. Large or
ambiguous breakage should be escalated before broad changes are made.

### Detekt And ktfmt

- Do not edit `config/detekt/detekt.yml`, add suppressions broadly, or create baselines unless the
  user explicitly asks.
- Treat Compose Rules findings as first-class Detekt findings in Compose-bearing modules.
- Use `ktfmt` as the formatting source of truth.
- Prefer fixing the cause of a Detekt finding over reshuffling code to game the rule.
- Read [docs/STATIC-ANALYSIS.md](docs/STATIC-ANALYSIS.md) before changing static-analysis
  configuration.

## Actions Requiring Explicit User Approval

Do not perform the following without clear user instruction in the current task:

- opening a PR
- merging or closing a PR
- pushing any branch
- destructive git history changes (`reset --hard`, `checkout --`, deleting branches, etc.)
- committing unrelated changes outside the task’s scope

## Working Style

- Minimize interruptions. Read the code and docs before asking questions.
- Clarify upfront or not at all. If a real blocker exists, ask once and ask clearly.
- Be explicit about directory changes when working across repos such as `~/src/compose-pi`,
  `~/src/compose-driver`, `~/src/intellij-community`, or `~/src/compose-multiplatform-core`.
- Prefer repo-local docs and checked-out source over memory for Compose/Jewel/IntelliJ specifics.
- Before concluding that something is unsupported, check `.agents/skills/` for local guidance.

## Build & Run

```bash
./gradlew check
./gradlew build
./gradlew test
./gradlew :sample-desktop:run
```

Use targeted Gradle tasks when iterating, then finish with the broader relevant verification pass.

## Local Skills

Before reinventing a workflow, check `.agents/skills/`.

Current Spectre-local skills:

- `using-git-worktree`
- `jewel-swing-interop`
- `addressing-detekt-violations`
