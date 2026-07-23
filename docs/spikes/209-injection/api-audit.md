# #209 API-surface audit: `:core` read path vs input path

**Parent:** [#209](https://github.com/rock3r/spectre/issues/209) · **Milestone:** M1 ([#312](https://github.com/rock3r/spectre/issues/312))  
**Status:** spike artifact (excluded from the published MkDocs site)  
**Grounded in:** `core/src/main` as of branch tip at audit time; not a speculative redesign.

This audit answers the injection question for #209: how much of `:core` talks to **stable public
Compose / AWT APIs** versus **Compose Desktop internals**, and which **input verbs** can run once
read-path geometry is available — without further Compose cooperation.

## Legend

| Label | Meaning |
| --- | --- |
| **Public Compose** | Documented / intended Compose UI or Desktop API (may still be `@ExperimentalComposeUiApi`) |
| **Public AWT/JDK** | Standard `java.awt` / Swing / JDK APIs |
| **Compose internal** | Private fields, non-exported package types, or reflection into `androidx.compose.*` host chain |
| **Spectre internal** | Spectre-owned code annotated `@InternalSpectreApi` or package-`internal`; not a Compose concern |
| **Spectre public** | Stable or experimental Spectre API surface for consumers |

Injection implication columns:

| Implication | Meaning for inject/read-only modes |
| --- | --- |
| **Inject-friendly** | Needs only public APIs of the **target's** Compose/AWT; injected `:core` can call them if types resolve against the target classloader |
| **Adapter risk** | Depends on private Compose Desktop host fields; renames break silently or degrade; needs version adapters |
| **OS-only** | No Compose types at the dispatch site; works from screen geometry alone |
| **Semantics action** | Invokes Compose semantics actions on a live node (needs node object from read path, not Robot) |

---

## 1. Read path

### 1.1 Window / surface discovery

| Spectre site | What it uses | Compose/AWT tier | Implication |
| --- | --- | --- | --- |
| `WindowTracker.refresh` | `Window.getWindows()`, `Window.owner`, `Window.isShowing`, `Window.ownedWindows` | Public AWT/JDK | Inject-friendly |
| `WindowTracker.trackComposeWindow` | `ComposeWindow` type check + `ComposeWindow.semanticsOwners` | Public Compose (`@ExperimentalComposeUiApi`) | Inject-friendly (primary path) |
| `WindowTracker` embedded panels | Swing tree walk for `ComposePanel` | Public AWT + Public Compose (`ComposePanel`) | Inject-friendly |
| `WindowTracker` + `OverlayLayerInspector` | Reflective chain: `composePanel` → `_composeContainer` → `layers` → `layerWindow` / `mediator` / `getSemanticsOwners` | **Compose internal** | Adapter risk (OnWindow popups only; empty list if chain breaks) |
| `TrackedWindow.composeContentOrigin` / `composeSurfaceBoundsOnScreen` | `ComposePanel.locationOnScreen` / size, or `JFrame.contentPane`, or `Window.locationOnScreen` | Public AWT | Inject-friendly |
| `SurfaceIdAssigner` | Spectre-owned IDs | Spectre internal | N/A (no Compose) |
| `NativeWindowHandle.resolve` | Public Compose `getWindowHandle` when present; else AWT peer reflection (`sun.awt.*`) | Public Compose **or** JDK internal peers | Inject-friendly for Compose handle; peer path needs module opens (agent already opens via `Instrumentation`) |
| `WindowIdentityResolver` | `GraphicsConfiguration.defaultTransform`, window bounds, native handle | Public AWT + above | Inject-friendly for geometry |

**Spike constraint alignment** ([docs/ARCHITECTURE.md](../../ARCHITECTURE.md)):

1. **Primary read path is public:** `ComposeWindow.semanticsOwners` / `ComposePanel.semanticsOwners`.
2. **Popup discovery is partial without internals:** main-scene owners are public; OnWindow
   (`compose.layers.type=WINDOW`) layers require `OverlayLayerInspector` reflection.
3. **Embedded Swing-hosted Compose** may report `windowHandle == 0` — geometry still works via AWT.

### 1.2 Semantics tree walk and node identity

| Spectre site | What it uses | Compose/AWT tier | Implication |
| --- | --- | --- | --- |
| `SemanticsReader.getOwnersForWindow` | `ComposeWindow.semanticsOwners` / `ComposePanel.semanticsOwners` or overlay accessor | Public Compose **or** Compose internal (overlay) | Inject-friendly for main scene; adapter risk for overlays |
| `SemanticsOwner.rootSemanticsNode` | Owner → root | Public Compose | Inject-friendly |
| `SemanticsNode.id` / `children` / `boundsInWindow` | Tree walk + geometry | Public Compose | Inject-friendly |
| `SemanticsNode.config` + `SemanticsProperties.*` | `TestTag`, `Text`, `EditableText`, `ContentDescription`, `Role`, `Disabled`, `Focused`, `Selected` | Public Compose | Inject-friendly |
| `SemanticsActions.OnClick` presence | Clickability flag | Public Compose | Inject-friendly (property only) |
| `NodeKey(surfaceId, ownerIndex, nodeId)` | Spectre compound id (constraint #2) | Spectre public | N/A |
| `AutomatorNode` property snapshot | Eager copy of semantics props on EDT | Spectre public over Compose types | Inject-friendly; still depends on Compose types at runtime |
| `EdtUtils.readOnEdt` | `SwingUtilities` | Public AWT | Inject-friendly |

**Public vs internal summary (read path):**

| Concern | Public enough for injection? | Caveats |
| --- | --- | --- |
| Discover ComposeWindow / ComposePanel windows | **Yes** | Requires Compose Desktop types on target CL |
| Read main-scene semantics owners | **Yes** (experimental public) | Opt-in `@ExperimentalComposeUiApi` |
| Walk tree, tags, text, roles, bounds | **Yes** | `SemanticsNode.id` only unique per owner — Spectre already compounds |
| OnWindow overlay popup owners | **No** (reflection) | Degrades to empty; adapter matrix for completeness |
| Recomposer discovery (`RecomposerInspector`) | **No** (reflection) | Idle/recomposition monitor only — not required for tree dump |
| Native window handle for capture | Partial | Public Compose handle when non-zero; peer fallback is JDK-internal |

### 1.3 Read-path Spectre façade (consumer API)

These are **Spectre public** entry points that *implement* the read path above. Injection does not
need a separate Spectre install if these classes are loaded from the inject jar and resolve Compose
against the target:

| API | Role |
| --- | --- |
| `ComposeAutomator.refreshWindows` / `surfaceIds` / `tree` / `allNodes` | Window refresh + full tree |
| `findByTestTag` / `findByText` / `findByContentDescription` / `findByRole` | Selectors |
| `printTree` | Debug dump |
| `waitForNode` / `waitForIdle` / `waitForVisualIdle` | Sync (mix of read + optional capture fingerprint) |
| `windowIdentities` (`@InternalSpectreApi`) | Geometry for external recorders |

`waitForVisualIdle` additionally samples screen pixels via Robot — see input path.

---

## 2. Input path

### 2.1 Coordinate mapping (read → screen)

| Spectre site | What it uses | Tier | Implication |
| --- | --- | --- | --- |
| `AutomatorNode.centerOnScreen` / `boundsOnScreen` | Live `SemanticsNode.boundsInWindow` + `GraphicsConfiguration.defaultTransform` + content origin | Public Compose + Public AWT | Inject-friendly; **requires** prior read-path node |
| `HiDpiMapper.composeBoundsToAwt*` | Pure math (Compose px → AWT screen) | Spectre (`@InternalSpectreApi` for some helpers) | Inject-friendly once floats/origin known |
| `focusWindow` | `Window.toFront` / `requestFocus` | Public AWT | OS-only after window object known |

### 2.2 Robot / synthetic dispatch (no Compose at dispatch)

`RobotDriver` dispatches at **integer screen coordinates** (or key codes). The real backend is
`java.awt.Robot`; synthetic / headless adapters exist for tests and restricted environments.

| Verb | API surface | Needs live Compose object? | Needs geometry from read path? | Implication |
| --- | --- | --- | --- | --- |
| `click(x, y)` | `Robot.mouseMove/Press/Release` | No | Yes (for UI targeting) | **OS-only** |
| `doubleClick(x, y)` | Robot ×2 | No | Yes | **OS-only** |
| `longClick(x, y, hold)` | Robot press + delay + release | No | Yes | **OS-only** |
| `swipe(x1,y1 → x2,y2)` | Robot interpolated move | No | Yes | **OS-only** |
| `scrollWheel(x, y, clicks)` | `Robot.mouseWheel` | No | Yes (position) | **OS-only** |
| `typeText` / `pasteText` / `clearAndTypeText` | Robot keys / clipboard | No | No for type/paste; clearAndType needs click geometry first | **OS-only** (focus must already be correct) |
| `pressKey` / `pressEnter` | Robot keys | No | No | **OS-only** |
| `screenshot(region)` | `Robot.createScreenCapture` | No | Optional region from read path | **OS-only** |
| macOS TCC gates | Accessibility / Screen Recording | N/A | N/A | Platform permission, not Compose |

`ComposeAutomator` node-oriented methods (`click(node)`, `doubleClick(node)`, …) are thin wrappers:
resolve `node.centerOnScreen` (read path), then call `RobotDriver`.

### 2.3 Semantics-action dispatch (Compose cooperation)

| Verb | What it uses | Implication |
| --- | --- | --- |
| `performSemanticsClick(node)` | `SemanticsActions.OnClick` invocable on live `SemanticsNode` | **Semantics action** — not equivalent to real input; useful headless / tool-window when Robot is unavailable |

This is the only primary “click” path that **cannot** be reduced to Robot + geometry alone.

### 2.4 Input verbs that survive on read-path geometry alone

Once the read path has produced screen-space bounds/centers (and optionally focused the host
window), these verbs need **no further Compose API**:

| Verb | Geometry required | Notes |
| --- | --- | --- |
| `click` | Center of target | Real OS click |
| `doubleClick` | Center | |
| `longClick` | Center | Hold duration |
| `swipe` (coord or node-to-node) | Start/end centers | |
| `scrollWheel` | Center + wheel delta | |
| `typeText` | None (keyboard) | Requires correct focus beforehand (`click` or `focusWindow`) |
| `pasteText` | None | Same focus caveat |
| `clearAndTypeText` | Center (implicit click) then keys | |
| `pressKey` / `pressEnter` | None | Focus caveat |
| `screenshot` (region / node / window) | Optional region | Robot capture |
| `focusWindow` | Window reference from read path | AWT only |

**Does not** survive on geometry alone:

| Verb / feature | Why |
| --- | --- |
| `performSemanticsClick` | Needs live semantics action handle |
| Overlay popup discovery completeness | Needs Compose-internal layer walk (read path) |
| `waitForVisualIdle` frame hashing | Uses screenshots (OS-only) but also tree fingerprints (read path) |
| `monitorRecompositions` / `RecomposerInspector` | Compose-internal host chain |

---

## 3. Dependency / classloader notes for injection (feeds M2)

| Artifact | Today’s agent-runtime policy | Injection consequence |
| --- | --- | --- |
| `:core` (`dev.sebastiano.spectre.core.*`) | **Forbidden** in thin `spectre-agent-runtime` jar | Must be **injected** (relocated or dedicated inject jar) for no-core targets |
| Compose UI / Desktop / Skiko | **Forbidden** in agent-runtime | Must resolve against **target** classloaders — never shade |
| kotlinx-coroutines (core uses them) | Excluded from thin runtime today | Likely need **relocated** coroutines inside inject payload to avoid IDE collisions |
| kotlinx-serialization-cbor | Bundled in agent-runtime for IPC | Already on agent side; inject path must not clash with IDE’s kotlinx |
| Kotlin stdlib | Not bundled in agent-runtime | Rely on target or relocate carefully |

Bootstrap today (`AgentBootstrap`) **requires** a preloaded `ComposeAutomator` on the target
classpath and applies D-14 PluginClassLoader disambiguation. Injection must add a branch that
loads relocated core when no candidate exists, while still resolving Compose types from the target.

---

## 4. Implications for #209 modes

| Mode | Feasibility from this audit | Primary risks |
| --- | --- | --- |
| **Read-only inspect** (tree dump, selectors, printTree) | **Strong** — main scene is public Compose API | Overlay popups incomplete without adapters; Compose version skew for experimental APIs; classloader / kotlinx collision |
| **Read + Robot input** | **Strong** for geometry-based verbs | OS focus, TCC, multi-monitor HiDPI already handled in core; no extra Compose adapters beyond read path |
| **Semantics-action input** | Possible when nodes are live | Not a substitute for real input validation |
| **Full idle/recomposition monitoring** | Weaker | `RecomposerInspector` is pure internal reflection |

**Bottom line for the spike prototype:** a relocated `:core` + kotlinx inject jar that does **not**
bundle Compose is architecturally coherent with the current read path. The first proving target
should be: attach → inject → `refreshWindows` / `allNodes` / `findByTestTag` over UDS. Overlay and
recomposer completeness can lag.

---

## 5. Source map (audit trail)

| Area | Primary files |
| --- | --- |
| Window discovery | `WindowTracker.kt`, `TrackedWindow.kt`, `OverlayLayerInspector.kt`, `SurfaceIdAssigner.kt` |
| Semantics | `SemanticsReader.kt`, `AutomatorNode.kt`, `NodeMatcher.kt`, `AutomatorTree.kt` |
| Coordinates | `HiDpiMapper.kt`, `AutomatorNode` geometry, `WindowIdentityResolver.kt` |
| Input | `RobotDriver.kt`, `ComposeAutomator` click/type/swipe/…, `SyntheticRobotAdapter.kt` |
| Native handles | `NativeWindowHandle.kt` |
| Recomposer (non-essential for dump) | `perf/RecomposerInspector.kt`, `perf/RecompositionMonitor.kt` |
| Agent bootstrap (context) | `agent/.../AgentBootstrap.kt`, `agent-runtime/build.gradle.kts` |

---

## 6. Open items deferred to later milestones

- **M2:** concrete inject-jar layout, relocation scheme, bootstrap without preinstalled core.
- **M3:** e2e tree dump against fixture/sandbox.
- **M4:** stock IDE `EnableDynamicAgentLoading`, adapter-matrix estimate, detach leak call.
- **M5:** 1.0 choice (instrumented-only vs read-only-injection vs full-injection) vs STABILITY tiers.
