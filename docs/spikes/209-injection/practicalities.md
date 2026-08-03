# #209 practicalities (M4 / #315)

## 1. Enabling `-XX:+EnableDynamicAgentLoading` on a stock IDE

JetBrains IDEs expose **Help → Edit Custom VM Options…**, which opens (or creates) a
per-product `*.vmoptions` file under the IDE config directory. Append:

```text
-XX:+EnableDynamicAgentLoading
```

Then fully restart the IDE. Without the flag, JDK 21+ prints a JEP 451 stderr warning on
dynamic attach; a future JDK may reject attach entirely. Spectre's launch harness injects the
flag for direct `java` launches automatically (`LaunchCommandRewriter`); stock IDEs must be
edited by the user/operator.

**Config path examples (macOS):**
`~/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.vmoptions`

## 2. Compose / Jewel adapter-matrix estimate

| Surface | Public vs internal | Adapter pressure |
| --- | --- | --- |
| Main-scene `ComposeWindow` / `ComposePanel.semanticsOwners` | Public (`@ExperimentalComposeUiApi`) | Low — track experimental opt-in + Desktop release notes |
| Semantics tree walk / properties | Public Compose UI | Low–medium if property keys rename |
| OnWindow overlay popups (`OverlayLayerInspector`) | **Internal reflection** | High per Compose Desktop minor if host fields rename |
| `RecomposerInspector` / recomposition monitor | Internal reflection | High; optional for inspect mode |
| Robot geometry input | Public AWT only | None for Compose skew |

**Estimate across supported IDE majors (2024.3 → 2026.2):**

- **Read-only main scene:** expect **0–1 adapter** if experimental public API stays stable; otherwise
  a thin shims package per Compose Desktop line used by an IDE major.
- **Overlay popups + recomposer:** **1–3 reflective adapter variants** *if* full parity across that
  window were a 1.0 goal — concentrate in `OverlayLayerInspector` / `RecomposerInspector` only.
- **Jewel version:** Jewel is theming/UI; Spectre reads semantics, not Jewel widgets. Jewel skew
  does not require Spectre adapters unless Jewel embeds Compose through a non-standard host
  (none known for stock tool windows).

### Decided policy (#322)

**Degrade-to-empty on a single pinned Compose Desktop chain — no multi-version adapter matrix
for 1.0.** Full write-up: [overlay-adapter-policy.md](overlay-adapter-policy.md).

Spike / 1.0 ship shape validates inject packaging and bootstrap (and overlay discovery) against
the project's **pinned** Compose Desktop line only.

## 3. Detach / classloader-leak acceptability (inspect mode)

Spectre:

- extracts inject jar to a temp file
- closes `SpectreInjectClassLoader` and deletes the temp jar on detach / failed bootstrap
  (file handles reclaimable)

Injected classes are defined by that **child** `URLClassLoader`. After detach releases the
loader (and no other strong references remain), those classes are **eligible for unloading**
when the GC reclaims the loader — timing is **not guaranteed**, and any retained reference
(stale automator, IDE-held stack frame, etc.) can keep the loader alive.

For **inspect-mode** (attach, dump tree, detach) on a long-lived IDE:

- **Usually fine** for rare debug sessions: close/delete of the inject jar is deterministic;
  metaspace retention is GC-dependent, not a guaranteed permanent per-attach leak.
- **Still prefer instrumented-only** for high-frequency CI attach loops so product behaviour
  does not depend on GC class-unloading behaviour.

**Implication for 1.0:** keep injection as **experimental inspect**; production CI targets
should keep preinstalled core (or a single `-javaagent` at process start) rather than
relying on unbounded inject attach/detach cycles.

## 4. Stock IDE proving status

Full operator recipe, tag table, and evidence chain: **[stock-intellij-recipe.md](stock-intellij-recipe.md)**
(#320).

| Layer | Status |
| --- | --- |
| Inject packaging + no-core fixture attach | Automated (`AgentInjectAttachIntegrationTest`, jar verify tasks; Windows opt-in e2e) |
| Jewel `ide.counter.*` / `ide.popup.*` in IDEA 2026.2 | Automated via `:sample-intellij-plugin:uiTest` (**instrumented** sample — ships core) |
| No-core sample plugin packaging | Automated (`buildNoCorePlugin` / `verifyNoCorePluginZip` + buildSrc contract tests) (#375) |
| Stock IDE + inject (no preinstalled core) | **PASS** via opt-in `:sample-intellij-plugin:stockInjectUiTest` (#353 / #376) — not always-on PR CI |
| Manual operator path | [stock-intellij-recipe.md](stock-intellij-recipe.md) (vmoptions + attach dump) |

Stock no-core inject remains **experimental inspect**, not a PR-blocking always-on CI gate
(see packaging-1.0-decision.md). Re-verify with `stockInjectUiTest` before releases that
touch inject bootstrap or IDE-hosted Compose discovery.
