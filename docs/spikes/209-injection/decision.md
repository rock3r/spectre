# #209 decision: injection for Spectre 1.0 (M5 / #316)

## Recommendation for 1.0

**Read-only injection: experimental / optional path**  
**Full injection (Robot input via inject): deferred**  
**Instrumented-only (preinstalled `spectre-core`): remains the supported production attach path**

### Why

1. **API audit** shows the primary semantics read path is public Compose Desktop API — inject is
   architecturally sound for tree dump/inspect without shading Compose.
2. **Prototype succeeded** against a no-core Compose fixture over real attach/UDS
   (`AgentInjectAttachIntegrationTest`, PR #319).
3. **Maintenance cost** for main-scene read is low; full overlay/recomposer parity and multi-IDE
   Compose lines raise adapter cost into STABILITY experimental territory.
4. **Metaspace leak** after inject means unbounded attach/detach on stock IDEs is a product risk
   if advertised as production.
5. **Stock IDE** was not fully proven in the delivery environment — keep the feature experimental
   until a documented stock-IDE recipe is green in CI or release QA.

## Mapping to `docs/STABILITY.md` tiers

| Surface | Tier | Notes |
| --- | --- | --- |
| Agent attach with preinstalled core | `@ExperimentalSpectreAgentApi` (existing) | Unchanged |
| Inject packaging + bootstrap | Experimental (same marker) | Nested inject jar inside agent-runtime |
| Production CLI `spectre attach --inject` | **Out of 1.0** | Requires re-approval (non-goal of #209) |
| Version adapter matrix for every IDE major | Not shipped | Estimate only (see practicalities) |

## Modes compared

| Mode | 1.0 status | Rationale |
| --- | --- | --- |
| Instrumented-only | **Supported experimental attach** | Existing path; no leak; CI e2e mature |
| Read-only injection | **Spike-proven; experimental** | Tree dump works; document leak + EnableDynamicAgentLoading |
| Full injection (Robot) | **Technically viable** after geometry | Same inject payload; defer product UX until stock-IDE QA |

## Follow-up issues (filed from this decision)

Filed after decision (not before):

1. **#320** — Stock IDE attach recipe + QA (vmoptions + IntelliJ 2026.2 Jewel tags).
2. **#321** — Decide 1.0 fate of nested inject-runtime packaging (promote / keep experimental / strip).
3. **#322** — OverlayLayerInspector multi-version adapter policy.

## Closes

This decision, the API audit, practicalities, packaging prototype, and fixture e2e evidence
together **close spike #209** even without a stock-IDE screenshot: negative stock-IDE automation
is environmental and documented.
