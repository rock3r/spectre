---
name: addressing-detekt-violations
description: Use when Detekt reports a violation and you need to fix the underlying problem without papering over it with suppressions or configuration changes.
---

# Addressing Detekt Violations

## Golden Rule

Fix the design problem. Do not use `@Suppress`, baselines, or config loosening as the default way
out of a finding.

## What To Do First

When Detekt reports a violation:

1. Identify whether it reflects a real design/code-structure problem.
2. Prefer extracting collaborators or simplifying control flow over shuffling code around.
3. Re-run the smallest relevant Detekt task after the fix.

## Red Flags

Treat these as bad fixes:

- adding `@Suppress` with no clear boundary reason
- moving methods around purely to dodge rule counts
- converting complex functions into lambdas stored in properties just to hide them from Detekt
- relaxing the config or generating a baseline without explicit user approval

## Spectre Notes

- `ktfmt` owns formatting; do not try to resolve formatting findings by adding Detekt formatting
  plugins or config churn.
- Compose-bearing modules also run `io.nlopez.compose.rules:detekt`; treat those findings like any
  other structural warning and prefer API/design fixes over suppressions.
- If a Detekt rule seems wrong for this repo, surface it and ask before changing `config/detekt`.
- For current repo policy and commands, read `docs/STATIC-ANALYSIS.md`.
