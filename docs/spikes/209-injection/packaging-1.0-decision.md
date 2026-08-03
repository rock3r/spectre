# #321: 1.0 fate of nested inject-runtime packaging

**Status:** decided (closes #321)  
**Date:** 2026-07-26  
**Depends on:** #209 M2 packaging (#319), decision.md (M5)

## Decision

| Option | 1.0 outcome |
| --- | --- |
| Nested `META-INF/spectre/inject-runtime.jar` inside `spectre-agent-runtime` | **Keep** |
| Stability tier | **Experimental** (`@ExperimentalSpectreAgentApi` — same as attach API) |
| Promote inject packaging to a first-class stable product surface | **No** for 1.0 |
| Strip inject payload for a strict instrumented-only 1.0 | **No** |
| Production CLI `spectre attach --inject` / user-facing “magic inject” flag | **Out of 1.0** (re-approval required) |
| Separate published `spectre-agent-inject-runtime` consumer coordinate | **Not required** for attach; nested resource is the ship shape |

## Criteria applied (from decision.md)

1. **API soundness** — main-scene semantics read uses public Compose Desktop APIs; inject does not shade Compose.
2. **Prototype evidence** — `AgentInjectAttachIntegrationTest` + packaging verify tasks prove nested jar bootstrap without preinstalled core.
3. **Maintenance cost** — nested jar + classloader bootstrap is contained; multi-IDE Compose adapter matrix stays experimental / deferred.
4. **Metaspace / detach** — GC-dependent unload after inject; unacceptable as a production high-frequency path → experimental inspect only.
5. **Stock IDE QA** — recipe + evidence chain in [stock-intellij-recipe.md](stock-intellij-recipe.md) (#320); not a reason to strip packaging already validated on fixtures.

## Rationale (short)

- **Keep:** removing inject for 1.0 would discard the only path for attach against targets that cannot take a compile-time `spectre-core` dependency (stock IDE, third-party apps). The packaging is already built, verified in CI jar checks, and covered by the inject e2e on Linux/macOS (plus physical Windows strip coverage).
- **Do not promote:** instrumented-only (preinstalled core) remains the preferred attach path; inject is opt-in via bootstrap fallback, not a separate stable product mode.
- **Do not strip:** “strict instrumented-only 1.0” would force every inspect target to ship Spectre, which is the problem #209 solved for experimental inspect.

## Shipping shape (unchanged)

```text
spectre-agent-runtime.jar
└── META-INF/spectre/inject-runtime.jar   # core + relocated kotlinx; no Compose
```

Bootstrap order (see `AgentBootstrap.findSpectre`):

1. Prefer preinstalled `ComposeAutomator` on the target.
2. Else extract nested inject jar → `SpectreInjectClassLoader(parent = Compose host)`.

## Revisit triggers (post-1.0)

- Metaspace / unload hardened enough for CI attach loops.
- Always-on PR CI for stock-IDE inject (currently opt-in `stockInjectUiTest` is green — #353).
- Product need for CLI/MCP “attach without core” as a supported mode (not experimental).

Until then, docs and STABILITY language must keep inject **experimental inspect**, not production attach.
Stock no-core IDE inject is **proven** (opt-in e2e) but not promoted beyond experimental inspect.
