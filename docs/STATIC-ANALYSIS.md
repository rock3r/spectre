# Static Analysis

Spectre uses two complementary tools:

- `detekt` 2.x for Kotlin static analysis and design/code-smell checks
- `io.nlopez.compose.rules:detekt` for Compose-specific lint in Compose-bearing modules
- `ktfmt` for formatting

We intentionally do **not** enable Detekt's formatting plugin. `ktfmt` is the formatting source of
truth, and duplicating formatting enforcement in Detekt creates noisy overlap.

## Commands

Use these during local development:

```bash
./gradlew check
./gradlew detekt
./gradlew ktfmtCheck
./gradlew ktfmtFormat
./gradlew build
```

Notes:

- `check` is the CI-shaped validation path in this repo.
- `ktfmtCheck` verifies formatting.
- `ktfmtFormat` rewrites Kotlin and Gradle Kotlin DSL files to the chosen style.
- `detekt` checks Kotlin source for structural/style issues.
- Modules load the upstream Compose Rules checks through Detekt so Compose-bearing
  sources are covered wherever they live.
- `build` is the broader all-up build path and should also stay green before pushing.

## Style Choices

- `ktfmt` uses KotlinLang style in this repo.
- Detekt follows the same 2.x line as Compose Pi and builds on the default rule set using a
  Pi-inspired profile tuned for Compose/Desktop work: 120-char lines, a looser
  return-count/complexity envelope, and Compose-friendly naming exceptions.
- Compose-bearing modules also load the upstream Compose Rules Detekt plugin. We keep its defaults
  unless the repo develops a clear recurring need for local overrides.
- Generated and `build/` outputs are excluded from both Detekt and ktfmt tasks.

## Guidance

### Detekt

- Fix the underlying design/problem instead of suppressing findings by default.
- Do not relax rules, edit thresholds, or add baselines unless the user explicitly asks.
- Avoid broad `@Suppress` annotations. If a suppression is truly necessary, keep it narrow and
  document why.
- Treat Compose Rules findings the same way: fix the composable API or structure first, then reach
  for suppression only when the composable is intentionally outside the reusable-component path.

### ktfmt

- Prefer running `ktfmtFormat` over hand-formatting large Kotlin/Gradle changes.
- If formatting changes are unrelated noise, split them from behaviour changes when practical.
- Treat the formatter output as canonical unless there is a compelling repo-level reason to change
  the style configuration.

## CI

GitHub Actions runs the same validation path on pushes to `main` and on pull requests:

- `./gradlew check`

`check` is wired so that it also enforces `detekt`, Compose Rules through Detekt, and
`ktfmtCheck`.
