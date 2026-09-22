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
| IntelliJ/Jewel validation plugin | `sample-intellij-plugin/` |
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

### IntelliJ dependency baseline

Before changing dependencies shared with an IDE host, inspect the latest stable IDEA release's
source tag and dependency inventory. Pin that baseline in `gradle/libs.versions.toml` and update
the [compatibility table](guide/intellij.md#dependency-compatibility). Do not use an EAP or the
IntelliJ repository's current main branch as the stable baseline.

Compile shared libraries against those versions, even when a transitive dependency requests a
newer one. The root build enforces this for application and test classpaths without publishing
strict version constraints to consumers. The CLI and build-tool classpaths are separate.

Run `./gradlew :testing:intellijCompatibilityTest` after dependency changes. It executes the
test-runner contracts with the stable IDE's actual coroutine JAR, while retaining Spectre's
normally compiled bytecode. Update that runtime pin when changing the stable IDE baseline.
Also run a Linux, macOS, or Windows live smoke against the updated libraries before release.

## Git Workflow

- Do not push directly to `main` without explicit approval.
- Prefer isolated work via feature branches or worktrees when starting nontrivial implementation.
- Keep commits scoped and descriptive.
- Do not mix unrelated cleanup into feature work unless the user explicitly asks.

## CI

- The primary code CI is `.github/workflows/ci.yml`. Other workflows under
  `.github/workflows/` cover platform-specific builds (`macos.yml`, `macos-check.yml`,
  `windows.yml`), the IDE-hosted UI test (`ide-uitest.yml`), `:sample-desktop`
  validation on Windows and Linux (`validation-windows.yml`, `validation-linux.yml`),
  and the docs site (`docs.yml`).
- The default CI job runs on pull requests and pushes to `main`.
- Keep local verification aligned with CI to avoid “works on my machine” drift.

## Workspace Hygiene

- `.plans/` is reserved for local implementation plans and should stay gitignored.
- `.worktrees/` and `worktrees/` are allowed as local worktree roots and should stay gitignored.
- If you add new local-only infrastructure, keep it out of source control unless it is meant to be
  shared by future contributors and agents.
