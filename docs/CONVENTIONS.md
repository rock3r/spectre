# Conventions

## Code Style

These rules apply to new and modified code:

- no wildcard imports
- keep lines reasonably short; prefer roughly 120 characters or less
- avoid magic numbers when a named constant or token would clarify intent
- public/reusable composables should usually accept `modifier: Modifier = Modifier` first
- do not catch broad `Exception`/`Throwable` except at explicit runtime or integration boundaries
- keep methods focused; extract helpers when branching and state handling start to sprawl

## UI And Work Boundaries

- Treat Compose UI as a render layer.
- Keep business logic, coordination, and multi-step workflows out of composables when they can live
  in plain Kotlin collaborators.
- The sample app should delegate to reusable logic instead of becoming an alternate implementation
  of the product.

## File Placement

| What | Where |
|---|---|
| Shared automation logic and data model | `core/` |
| Remote transport glue | `server/` |
| Native/OS capture integration | `recording/` |
| Test-only helpers and fixtures | `testing/` |
| Manual validation harness UI | `sample-desktop/` |
| Long-lived implementation guidance | `docs/` |
| Local agent workflow help | `.agents/skills/` |

## Build And Verification Workflow

- Use targeted Gradle tasks while iterating.
- Run the broader relevant verification before pushing or opening a PR.
- Use `./gradlew check` as the default CI-shaped verification command unless the task clearly calls
  for a more targeted command.
- `check` is expected to stay CI-shaped: tests, Detekt, Compose Rules through Detekt, and ktfmt
  verification.
- Use `./gradlew build` when you also want the broader assemble/package path validated.
- Use `./gradlew ktfmtFormat` when a formatting pass is needed instead of hand-fixing large style
  diffs.

## Git Workflow

- Do not push directly to `main` without explicit approval.
- Prefer isolated work via feature branches or worktrees when starting nontrivial implementation.
- Keep commits scoped and descriptive.
- Do not mix unrelated cleanup into feature work unless the user explicitly asks.

## CI

- GitHub Actions lives in `.github/workflows/ci.yml`.
- The default CI job runs on pull requests and pushes to `main`.
- Keep local verification aligned with CI to avoid “works on my machine” drift.

## Workspace Hygiene

- `.plans/` is reserved for local implementation plans and should stay gitignored.
- `.worktrees/` and `worktrees/` are allowed as local worktree roots and should stay gitignored.
- If you add new local-only infrastructure, keep it out of source control unless it is meant to be
  shared by future contributors and agents.
