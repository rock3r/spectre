# #322: OverlayLayerInspector multi-version adapter policy

**Status:** decided (closes #322)  
**Date:** 2026-07-26  
**Depends on:** [api-audit.md](api-audit.md) §1.1, [practicalities.md](practicalities.md) §2, [decision.md](decision.md)

## Decision

| Question | 1.0 outcome |
| --- | --- |
| Runtime behaviour when the reflective OnWindow chain does not match | **Fail closed** — throw a versioned incomplete-inspection error; never return an apparently complete tree |
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

- Field/method missing or access failure → a versioned incomplete-inspection error from the required reflection chain.
- OnSameCanvas / OnComponent layers (no `layerWindow`) are skipped, not errors.
- `WindowTracker` still tracks AWT-owned popups separately; only the OnWindow **layer** path is adapter-risk.

`RecomposerInspector` follows the same **fail-closed** rule. Unsupported overlay recomposers remain explicit unresolved surfaces, which make monitor waits fail.

## Options considered

### A. Fail closed (chosen)

**Pros**

- A renamed private field cannot produce an apparently complete tree.
- The error names the detected Compose version and the missing member.
- Zero maintenance of a version matrix across IDE majors for 1.0.
- Already implemented and documented in source KDoc (#39 lineage).

**Cons**

- An affected tree read fails until someone updates the single adapter.
- Attach bootstrap and requests that do not inspect the tree remain available.

### B. Multi-version reflective adapters in 1.0 (rejected)

**Pros**

- Higher chance of popup discovery across 2024.3–2026.2 IDE lines.

**Cons**

- practicalities estimate: **1–3 variants** for overlays (+ recomposer) — ongoing cost in experimental territory.
- Needs a matrix of CI / host pins Spectre does not ship for inject 1.0 (decision.md: version adapter matrix **not shipped**).
- Silent wrong-adapter selection is worse than failure: partial field matches could return incomplete layers that look authoritative.
- Overkill while inject remains experimental inspect and instrumented sample-plugin CI already covers Jewel popups on the **pinned** line.

### C. Silently return an incomplete tree (rejected)

A missing popup can make `waitUntilGone` report success while UI is still present. A tree that looks
complete is more dangerous than a scoped inspection failure.

## Policy for 1.0 (normative)

1. **Fail-closed inspection is the contract** for `OverlayLayerInspector.findOverlayLayerWindows` and sibling internal reflection (`RecomposerInspector`).
2. **Single chain** for the Compose Desktop version Spectre pins in Gradle; no `if (composeVersion)` adapter table in 1.0.
3. **Do not** block attach or inject bootstrap. Fail requests that require the incomplete tree or recomposer set.
4. **Document** for operators: if an incomplete-inspection error appears under a non-pinned Compose line, update `OverlayLayerInspector` (or file an issue) — do not expect a built-in multi-version matrix.
5. **Validation** stays on the pinned line: sample-desktop popup validation tasks, sample-intellij Jewel popup tags, existing overlay-related tests — not a combinatorial IDE×Compose matrix.

## When to add adapters later (post-1.0)

Add a second (or third) reflective chain **only if**:

- A **supported** platform tier (see `docs/STABILITY.md`) ships a Compose Desktop line that renames the host chain **and**
- Product requires OnWindow popup parity on that tier (not just main scene) **and**
- The new chain is proven on a CI-hosted fixture (not only local).

Shape then:

- Keep fail-closed as the outer contract.
- Try chains in order (newest pin first, then legacy).
- Still concentrate all reflection in `OverlayLayerInspector` / `RecomposerInspector`.
- If no chain matches, retain the same versioned incomplete-inspection error.

## Mapping to #209 / 1.0 inject

| Surface | 1.0 stance |
| --- | --- |
| Main-scene read (public) | Supported experimental attach / inject inspect |
| OnWindow overlay read | Pinned Compose chain; versioned error on mismatch |
| Multi-IDE adapter matrix | Out of 1.0 (this decision) |
| Full injection Robot + every popup mode | Deferred (decision.md) |

## Closes

This policy closes **#322** and finishes the #209 follow-up list with #320 and #321.
