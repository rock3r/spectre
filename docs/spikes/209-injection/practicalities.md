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
- **Overlay popups + recomposer:** **1–3 reflective adapter variants** over that window if full
  parity is required — concentrate in `OverlayLayerInspector` / `RecomposerInspector` only.
- **Jewel version:** Jewel is theming/UI; Spectre reads semantics, not Jewel widgets. Jewel skew
  does not require Spectre adapters unless Jewel embeds Compose through a non-standard host
  (none known for stock tool windows).

Spike prototype does **not** ship a multi-version adapter matrix; it validates the inject packaging
and bootstrap against the project's pinned Compose Desktop line.

## 3. Detach / classloader-leak acceptability (inspect mode)

Injected classes **cannot be unloaded** once defined. Spectre now:

- extracts inject jar to a temp file
- closes `SpectreInjectClassLoader` and deletes the temp jar on detach / failed bootstrap
  (handles/files reclaimable)

But **loaded classes remain in Metaspace** for the target process lifetime. For **inspect-mode**
(attach, dump tree, detach) on a long-lived IDE:

- **Acceptable** if attach is rare (debug sessions): small metaspace growth per attach cycle
  that used a new inject loader.
- **Not acceptable** as an unbounded attach loop without process restart.

**Implication for 1.0:** prefer **instrumented-only** (preinstalled core) for production CI
targets; treat injection as **experimental inspect** with documented leak semantics — or
instrument once via `-javaagent` at IDE start to avoid repeated inject classloader creation.

## 4. Stock IDE proving status

Automated stock IntelliJ 2026.2 attach was **not** executed in the agent delivery environment
(no reliable stock IDE launch path). Evidence path used instead:

- `AgentInjectAttachIntegrationTest` — real attach + UDS + tree dump against Compose fixture
  **without** preinstalled `spectre-core` on the target classpath (injection exercised).

Stock-IDE gap is environmental, not a prototype packaging failure. Optional remote
Windows/Linux hosts were not required for packaging validation.
