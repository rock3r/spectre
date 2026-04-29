# Architecture

## Module Structure

```text
spectre
├── core/                    — shared automation model and desktop automation primitives
├── server/                  — optional transport layer for cross-JVM access
├── recording/               — screenshot / recording integrations and native capture boundaries
├── testing/                 — test fixtures and JUnit-facing helpers built on top of public APIs
├── sample-desktop/          — small manual-test harness app for spike validation
└── sample-intellij-plugin/  — sample IntelliJ plugin embedding Spectre in a Jewel tool window
```

## Dependency Direction

```text
sample-desktop          ─┐
sample-intellij-plugin   ├──> core
server                   │
testing                 ─┘

recording                (isolated desktop/native integration module)
```

Guidelines:

- `core` owns the reusable automation concepts.
- `server` should be transport-only glue over `core`, not a second implementation.
- `sample-desktop` is a harness, not the source of truth for automation logic.
- `testing` should exercise public seams and reusable fixtures, not reach through private internals
  without a strong reason.
- `recording` should isolate OS- and native-library-specific behavior from the core query/input
  APIs.

## Spike Constraints

These constraints are already known from the current research and should shape implementation:

1. `ComposeWindow.semanticsOwners` and `ComposePanel.semanticsOwners` are public and should be the
   primary read path.
2. `SemanticsNode.id` is only unique within a single `SemanticsOwner`. Public identifiers must be
   owner-scoped or compound.
3. Popup discovery cannot assume separate windows. Extra semantics roots may appear within the same
   window, especially when Compose layers stay on the same canvas.
4. HiDPI/AWT conversion is subtle. Compose semantics coordinates must be converted carefully before
   feeding Robot/AWT screen APIs.
5. Embedded Swing-hosted Compose surfaces may not expose a usable native `windowHandle`, so
   window-targeted recording cannot be assumed everywhere.

## Planned Responsibility Split

### `core`

Expected long-term responsibilities:

- window/surface tracking
- semantics tree reading and normalization
- owner/root-aware node identity
- selector/query API
- coordinate mapping between Compose, AWT, and Robot
- Robot-backed interaction primitives
- wait/synchronization semantics

### `server`

Expected long-term responsibilities:

- request/response DTOs
- serialization
- embedded HTTP server
- remote client

Keep server concerns out of the core data model unless they are genuinely transport-independent.

### `recording`

Expected long-term responsibilities:

- screen/region capture orchestration
- macOS-specific window capture helpers
- recorder lifecycle and output plumbing

Keep native capture boundaries narrow and test the pure pieces separately from OS integration.

Current backends:
- `FfmpegRecorder` — region capture via `ffmpeg` + `avfoundation` (v1).
- `screencapturekit.ScreenCaptureKitRecorder` — window-targeted capture on macOS via a bundled
  Swift helper (`recording/native/macos/`, v2 / #18). The helper is built by Gradle on macOS and
  staged into the module's `src/main/resources/native/macos/` so the JAR carries it.

### `testing`

Expected long-term responsibilities:

- JUnit rules/extensions
- reusable fixtures for validation of public APIs
- focused contract-test helpers for transport, selectors, and geometry

### `sample-desktop`

Expected long-term responsibilities:

- tiny exploratory app for manual spike validation
- reproducible surfaces for popup, focus, scrolling, and coordinate tests

Do not let the sample app become a dumping ground for production logic.

### `sample-intellij-plugin`

A minimal IntelliJ plugin used to validate that Spectre's in-process automator works against
IDE-hosted Compose surfaces (Jewel-on-IntelliJ tool windows). The plugin is **never published**
— it exists only for `runIde` validation against #13's checklist (popup discovery and
`ComposePanel` semantics in the IDE-hosted case). Same constraint as `sample-desktop`: do not
move production logic here.

Run via `./gradlew :sample-intellij-plugin:runIde`, then `Tools → Run Spectre Against the
Sample Tool Window`. The action drives the in-process `ComposeAutomator` against the Jewel
tool window and dumps the discovered semantics tree to `idea.log`.

## Architectural Invariants

These should remain true as the codebase grows:

1. `core` stays usable in-process without requiring the server.
2. Selector/query logic lives in `core`, not in the sample app or transport layer.
3. Transport modules serialize public/core-facing models instead of inventing parallel ones unless
   there is a strong compatibility reason.
4. Platform-specific integrations should be isolated behind small interfaces at module boundaries.
5. Research-only shortcuts are acceptable in the sample app, but reusable behavior must be moved
   into the proper module before it is treated as part of the product surface.

