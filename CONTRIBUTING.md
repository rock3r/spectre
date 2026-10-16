# Contributing

Thanks for considering it. This is a small project with a single maintainer; the bar for a
PR is "useful change, reviewable diff, doesn't break anything" rather than anything more
elaborate.

> [!IMPORTANT]
> **Do not submit AI-generated PRs or issues.** The maintainer closes these submissions,
> including output from local tools, hosted tools, and AI review or editing passes.
> You can use AI to learn or explore. Read the code, reproduce reported bugs, and write
> your submission yourself.

## Filing issues

Useful issues include:

- **What you tried.** Command, OS, Spectre version, JBR vs Temurin.
  `:sample-desktop:validationTest*` and `:recording:runWaylandPortalSmoke` are the obvious
  Gradle reproductions for runtime / recording problems.
- **Expected and actual results.** Include stack traces and log snippets. Add screenshots
  when they help explain a visual problem.
- **Whether you'd like to send the fix.** "Filing for tracking, won't pick up" is fine.
  "Happy to PR if you point me at the right module" is better.

For Linux-related reports please include your distro, desktop (`echo $XDG_CURRENT_DESKTOP`),
session type (`echo $XDG_SESSION_TYPE`), and compositor / window manager. The README's
best-effort callout means widening the validation footprint is explicitly welcome.

## Pull requests

- Branch off `main`; PR back into `main`.
- One concern per PR — keep the diff reviewable.
- `./gradlew check` passes locally. `./gradlew ktfmtFormat` keeps Kotlin / `.gradle.kts`
  files formatted.
- Tests where they make sense. `:check` covers the unit / integration suite; runtime
  smokes live under `:sample-desktop` and `:recording`.
- Commit messages: present-tense, explain the *why*. Squash scratch commits before
  requesting review.

For anything non-trivial, file an issue first so we can sanity-check the approach before
you invest the time.

## Code style + architecture

The existing docs are the live spec:

- [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) — naming, Kotlin idioms,
  en-US-in-code-and-en-GB-everywhere-else.
- [`docs/STATIC-ANALYSIS.md`](docs/STATIC-ANALYSIS.md) — Detekt + Compose Rules + ktfmt.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module layout and dependency direction.
- [`docs/TESTING.md`](docs/TESTING.md) — test taxonomy and the AutomatorFactory seam.

## Licence

By contributing you agree your contribution is licensed under [Apache-2.0](LICENSE), the
project's licence.
