# #322: OverlayLayerInspector multi-version adapter policy

**Status:** decided (closes #322)  
**Date:** 2026-07-26  
**Depends on:** [api-audit.md](api-audit.md) §1.1, [practicalities.md](practicalities.md) §2, [decision.md](decision.md)

## Decision

| Question | 1.0 outcome |
| --- | --- |
| Runtime behaviour when the reflective OnWindow chain does not match | **Degrade to empty** — `findOverlayLayerWindows` returns `emptyList()`, never throws |
| Ship a multi-version reflective adapter matrix (per Compose Desktop / IDE major) | **No** for 1.0 |
| Number of supported internal field layouts in-tree | **One** — the chain that matches Spectre’s **pinned** Compose Desktop line |
| Where adapters would live if added later | Still concentrated in `OverlayLayerInspector` (and `RecomposerInspector` for recomposer only) |
| Main-scene semantics when overlays fail | **Unaffected** — public `ComposeWindow` / `ComposePanel.semanticsOwners` path stays primary |
| Product implication for inject / experimental attach | Overlay popups may be **invisible to selectors** on unsupported Compose internals; main-scene inspect still works |

## What the code does today

`OverlayLayerInspector` (internal) walks Compose Desktop private fields:

```text
ComposeWindow.composePanel
  → ComposeWindowPanel._composeContainer
    → ComposeContainer.layers
      → WindowComposeSceneLayer.layerWindow / mediator.getSemanticsOwners()
```

- Field/method missing or access failure → **null / empty**, uniform degrade via `readField` / `invokeGetter`.
- OnSameCanvas / OnComponent layers (no `layerWindow`) are skipped, not errors.
- `WindowTracker` still tracks AWT-owned popups separately; only the OnWindow **layer** path is adapter-risk.

`RecomposerInspector` follows the same **degrade-to-null** rule for idle/recomposition monitoring (not required for tree dump).

## Options considered

### A. Degrade-to-empty (chosen)

**Pros**

- Automator never dies because an IDE minor renamed a private field.
- Matches inject/read-only goals: main scene is public Compose API and remains the contract.
- Zero maintenance of a version matrix across IDE majors for 1.0.
- Already implemented and documented in source KDoc (#39 lineage).

**Cons**

- OnWindow popup tags disappear silently if the chain breaks until someone updates the single adapter.
- Full popup parity on every Compose Desktop line used by every IDE major is **not** guaranteed.

### B. Multi-version reflective adapters in 1.0 (rejected)

**Pros**

- Higher chance of popup discovery across 2024.3–2026.2 IDE lines.

**Cons**

- practicalities estimate: **1–3 variants** for overlays (+ recomposer) — ongoing cost in experimental territory.
- Needs a matrix of CI / host pins Spectre does not ship for inject 1.0 (decision.md: version adapter matrix **not shipped**).
- Silent wrong-adapter selection is worse than empty: partial field matches could return incomplete layers.
- Overkill while inject remains experimental inspect and instrumented sample-plugin CI already covers Jewel popups on the **pinned** line.

### C. Fail-hard / throw on missing fields (rejected)

Breaks main-scene attach and tree dump when only the overlay chain is wrong — violates “rest of automator keeps working”.

## Policy for 1.0 (normative)

1. **Degrade-to-empty is the contract** for `OverlayLayerInspector.findOverlayLayerWindows` and sibling internal reflection (`RecomposerInspector`).
2. **Single chain** for the Compose Desktop version Spectre pins in Gradle; no `if (composeVersion)` adapter table in 1.0.
3. **Do not** block attach, inject bootstrap, or main-scene selectors on overlay discovery failure.
4. **Document** for operators: if OnWindow popup nodes are missing under a non-pinned Compose line, update `OverlayLayerInspector` (or file an issue) — do not expect a built-in multi-version matrix.
5. **Validation** stays on the pinned line: sample-desktop popup validation tasks, sample-intellij Jewel popup tags, existing overlay-related tests — not a combinatorial IDE×Compose matrix.

## When to add adapters later (post-1.0)

Add a second (or third) reflective chain **only if**:

- A **supported** platform tier (see `docs/STABILITY.md`) ships a Compose Desktop line that renames the host chain **and**
- Product requires OnWindow popup parity on that tier (not just main scene) **and**
- The new chain is proven on a CI-hosted fixture (not only local).

Shape then:

- Keep degrade-to-empty as the outer contract.
- Try chains in order (newest pin first, then legacy).
- Still concentrate all reflection in `OverlayLayerInspector` / `RecomposerInspector`.
- Never throw from discovery; optional debug logging only.

## Mapping to #209 / 1.0 inject

| Surface | 1.0 stance |
| --- | --- |
| Main-scene read (public) | Supported experimental attach / inject inspect |
| OnWindow overlay read | Best-effort on pinned Compose; degrade empty otherwise |
| Multi-IDE adapter matrix | Out of 1.0 (this decision) |
| Full injection Robot + every popup mode | Deferred (decision.md) |

## Closes

This policy closes **#322** and finishes the #209 follow-up list with #320 and #321.
