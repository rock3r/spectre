# Docs style guide

This guide applies to anyone who writes the Spectre docs at
<https://spectre.sebastiano.dev>. `mkdocs.yml` excludes it from the published site.

The user guide lives in `docs/index.md` and `docs/guide/`.
Reference pages include `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `docs/CONVENTIONS.md`,
`docs/STATIC-ANALYSIS.md`, and `docs/RECORDING-LIMITATIONS.md`.
All these pages appear on the same site and follow these rules.

## Rules for accurate docs

1. **Check API claims against source.** Read the relevant `.kt` file for signatures,
   defaults, parameter names, visibility, exceptions, and behaviour before documenting them.
2. **Write valid code samples.** Put imports before declarations. Use real APIs and package
   paths. Import every extension function the sample calls.
3. **Describe released behaviour in public pages.** Omit internal milestones, issue numbers,
   and plans for future backends unless a user needs an actionable issue reference.
4. **State prerelease limits where they affect use.** Explain missing published artefacts
   or snapshot-only versions. Keep other pages factual and free of marketing claims.
5. **Run `mkdocs build --strict` before pushing docs.** Strict mode checks links,
   anchors, and navigation entries.
6. **Document the public contract.** Explain calls, results, and observable behaviour.
   Keep visibility modifiers and test-only helpers in contributor docs unless consumers need them.

## Writing for the user

- **Assume Kotlin and Gradle knowledge.** Explain Spectre concepts without relying on
  internal milestones or refactoring history.
- **Start with the user's task.** `installation.md` and `getting-started.md` lead to
  concept pages, including `automator.md`.
- **Use precise, plain language.** Include technical detail when it changes what a user does.
- **Provide working examples.** Link listed APIs to examples that show how to call them.
- **Link related guidance.** For example, `selectors.md` should link to `waitForNode`.

## Code samples

Spectre is a Kotlin library, so samples are usually Kotlin. A sample is wrong if any of
the following are true:

- An imported symbol doesn't exist in the source.
- A function call uses a non-existent named argument.
- A modifier mask uses `KeyEvent.CTRL_DOWN_MASK`; the correct class is
  `InputEvent.CTRL_DOWN_MASK`.
- An interface is "constructed" as if it were a data class.
- An import sits below executable statements (Kotlin imports must precede
  declarations).
- The sample uses `internal` API. The adapter-injecting `RobotDriver` constructor is
  a real-world example: it's `internal`, so samples must use the public no-arg form
  or a public companion factory.
- The sample claims behaviour the implementation doesn't honour (e.g. "auto-wait
  finds the node" when queries are explicitly non-waiting).

Check the source when a signature is unclear. In-process callers use
`findByContentDescription(...).firstOrNull()`. The in-process API has no
`findOneByContentDescription`. The HTTP client offers `findOneByContentDescription`
and `findOneByRole` as wrappers over `GET /node`.

### Imports in samples

Show the imports the sample actually needs, including extension-function imports.
`Frame.asTitledWindow()` is an extension and requires an explicit import.
`RobotDriver.synthetic(...)` is a real companion function and needs only the
`RobotDriver` import.

### Default arguments and named parameters

For a call such as `foo(bar = 5)`, verify that the source names its parameter `bar`.
The parameter for `synthetic(...)` is `rootWindow`, so `synthetic(window = …)` is
invalid. Recheck samples whenever a public signature changes.

## API and behaviour invariants worth re-checking

Check these API contracts when editing the corresponding page:

- **No auto-wait.** Queries (`findByTestTag`, `findByText`, `hasTag`, `hasText`, etc.)
  do a single read. They never retry. Document them as such.
- **EDT rule applies to all five waits.** `waitForNode`, `waitUntilGone`, `waitUntil`,
  `waitForIdle`, and `waitForVisualIdle` all reject EDT callers via `rejectEdtCaller`
  (see `WaitForNodeTest.waitForNode rejects EDT callers with valid arguments`). Earlier
  drafts of the docs carved out `waitForNode` as exempt. Current code rejects EDT callers
  for all five waits. Document that all wait helpers reject the EDT.
- **`refreshWindows` is only auto-called by `tree()`.** `findBy*`, `hasTag`/`hasText`,
  and `allNodes` read against the last refresh. If a popup may have appeared since the
  last call, call `refreshWindows()` explicitly.
- **`findOneBy*` exists only for `testTag` and `text` on the in-process automator.**
  Content description and role lack in-process `findOneBy*` helpers. `HttpComposeAutomator` adds `findOneByContentDescription`
  and `findOneByRole` as HTTP-only conveniences.
- **`waitForNode(tag, text)` combines criteria with AND.** Both criteria must match the same
  node. `waitUntilGone(tag, text)` mirrors it: it returns once no single node carries
  both, and it refreshes windows before every poll. Finder calls use the last refresh.
- **`waitUntil` requires a non-blank `description` and an `AutomatorTree` receiver.**
  Its predicate has type `AutomatorTree.() -> Boolean`. Spectre passes only the tree
  snapshot. Callers must handle waits on other state themselves. A closure can still
  capture other state, so the receiver guides use without enforcing that limit.
- **`AutomatorIdlingResource.isIdleNow` and `diagnosticMessage()`.** The API has no
  `isIdle()` or `name` member. Register and unregister resources by identity.
- **`AutoRecorder` has explicit capture modes.** `startWindow(...)` requires a
  window-targeted backend. `startRegion(...)` requires a region backend. Each call
  fails if its backend is unavailable. Document the failure. The call never switches
  modes silently.
- **`TitledWindow` is an interface.** Production adapter is `Frame.asTitledWindow()`.
  Tests provide minimal `TitledWindow` implementations with title and bounds.
- **`installSpectreRoutes` is engine-agnostic.** The `server` module deliberately
  doesn't bundle Netty/CIO/Jetty. Consumers add the engine themselves.
- **`HttpComposeAutomator.DEFAULT_PORT` is `9274`.** That's the *client* default;
  the server-side port is whatever the consumer's Ktor engine listens on.
- **`RobotDriver` public constructors.** `RobotDriver.synthetic()` /
  `RobotDriver.synthetic(rootWindow)` (the `ComposeAutomator.inProcess()` default),
  `RobotDriver()` (explicit real-OS AWT Robot), `RobotDriver(robot)`,
  `RobotDriver.headless()`. The adapter-injecting constructor is `internal` and
  unavailable to consumers.
- **`RobotDriver.headless()` throws on input/screenshot/clipboard.** Every input,
  clipboard, and screenshot call raises `UnsupportedOperationException` so accidental
  real-I/O calls fail at the call site. `WindowTracker`/`SemanticsReader` are
  untouched, so semantics-tree reads still work.
- **`typeText` is key-event typing; `pasteText` is clipboard-paste.** `typeText`
  supports conservative ASCII key-event input without touching the clipboard. `pasteText`
  writes the text, dispatches the platform paste shortcut, drains, then restores the
  previous clipboard contents.
- **Synthetic typing and macOS UIElement mode.** Current `RobotDriver.synthetic(rootWindow)`
  routes key events to Compose Desktop's key-listening AWT host when AWT has no
  `Window.focusOwner`, so `typeText` works in `apple.awt.UIElement=true` helper JVMs.
  Keep documenting `pasteText` and OS recording as separate macOS-service paths that
  UIElement mode can still break.
- **Desktop input coordination is experimental and opt-in.** Consumer examples use
  `ExperimentalSpectreInputCoordinationApi`; no-argument `RobotDriver()` remains uncoordinated.
  `--force` is documented only as unsafe recovery with possible native-input overlap and
  `unsafeTakeover=true`. The coordinator serialises participating Spectre clients. People and
  other automation tools remain outside its scope.
- **Linux Wayland routes through the portal.** Xorg/Xvfb capture uses the Linux
  helper's GStreamer `ximagesrc` path; native Wayland capture uses the portal helper.
  Do not document a silent X11 fallback on Wayland.

## Style conventions

- **Use British spelling in prose and preserve code spelling.** Write `behaviour`,
  `serialisation`, `serialise`, and `colour` in Markdown text. Keep identifiers,
  packages, framework symbols, fenced code, and text in backticks unchanged. Examples
  include `@Serializable`, `Color`, `BehaviorSubject`, `Initializer`, and kotlinx
  `serialization`. Bulk rename scripts must use `\b…\b` boundaries and skip code fences.
- **Comma after `e.g.` and `i.e.`** ("e.g., foo", "i.e., bar"). Always.
- **Slashes are tight.** Write `mouse/keyboard/typing` with no spaces around slashes.
  For two-item alternatives, use "or"/"and", as in `clipboard or screen`.
  For two named code symbols, use `\`A\`/\`B\`` with no spaces around the slash.
- **Em dashes don't end lines wrapped to the next line.** Either keep the dash with
  text on both sides on one line, or move it to the start of the wrapped line.
- **Headings sentence-case.** Capitalise the first word in "Real vs. synthetic input"
  and keep subsequent words in lower case unless they are proper names.
- **Code identifiers in backticks.** Class names, method names, property names,
  package names. Module names too: `core`, `testing`, `server`, `recording`.
- **Keep list items short.** MkDocs Material can fold later bullets into an earlier
  item when continuation paragraphs use two spaces and nested blocks use four.
  Move a topic with several paragraphs and a nested list into an H3 or H4 section.
  Check the rendered list after editing.
- **Leave a blank line before a list.** CommonMark and MkDocs Material can render a
  following `-` as paragraph text when no blank line separates it from prose.

## Linking

- **Use relative links inside `docs/`.** For example, link to
  `[Synchronization](synchronization.md)` or `[Architecture](../ARCHITECTURE.md)`.
  `mkdocs build --strict` checks these links.
- **Outside `docs/` (workflows, repo-root files, source code): absolute
  `https://github.com/rock3r/spectre/...` URLs.** A relative path like
  `../.github/workflows/...` works in GitHub's preview. The strict build rejects it
  because the target lives outside `docs_dir`.
- **Check anchor links after renaming headings.** A file can exist while an anchor
  such as `#build-from-source` points to a heading that no longer exists.

## Workflow/CI

- **Permissions scoped per job.** The build job (which runs
  PR-controlled mkdocs config and pip dependencies) gets `contents: read` only.
  `pages: write` and `id-token: write` belong on the deploy job.
- **PRs build, only push-to-main + workflow_dispatch deploys.** Don't surface a
  Pages deployment from a PR build.
- **CNAME lives at `docs/CNAME`.** It's copied through into the built site by
  mkdocs and survives across deploys, so the custom domain doesn't get stripped.

## When the source changes

When a public API changes in `core`, `testing`, `server`, or `recording`, review
the related pages:

| Source changed                                                         | Pages to review                             |
| ----------------------------------------------------------------------- | ------------------------------------------- |
| `ComposeAutomator`, query/interaction methods, wait helpers              | `automator.md`, `selectors.md`, `interactions.md`, `synchronization.md` |
| `RobotDriver` factories, public constructors                            | `interactions.md`, `automator.md`           |
| Input coordination policy, scope, runtime, or JUnit isolation           | `input-coordination.md`, `interactions.md`, `junit.md`, `ci.md`, `STABILITY.md`, `SECURITY.md` |
| `WindowTracker`, `SemanticsReader`, `AutomatorNode`, `AutomatorTree`     | `automator.md`, `selectors.md`              |
| `AutomatorIdlingResource`                                               | `synchronization.md`                        |
| `ComposeAutomatorExtension`, `ComposeAutomatorRule`, `AutomatorFactory`  | `junit.md`, `getting-started.md`            |
| `installSpectreRoutes`, `HttpComposeAutomator`, server DTOs              | `cross-jvm.md`                              |
| `AutoRecorder`, `Recorder` impls, `TitledWindow`, `FfmpegBackend`        | `recording.md`, `RECORDING-LIMITATIONS.md`, `ARCHITECTURE.md` |
| Public Gradle build (publishing, `maven-publish`, module ids)           | `installation.md`                           |

## Verify before pushing

- `python -m mkdocs build --strict` passes (warns become errors; broken links and
  missing anchors fail the build).
- Code samples compile against the current source (mentally, or by pasting into a
  scratch Kotlin file).
- New cross-links resolve.
- `git diff main...HEAD -- docs/` reads cleanly: no leftover milestone language,
  no scope creep into unrelated docs.

## Out of scope for the user guide

Keep these details off the public site:

- Internal milestone narrative (kept out of `docs/` entirely now that
  `bootstrap-plan.md` is gone).
- Open issue numbers and PR numbers, except where the issue itself is an actionable
  user-facing reference (e.g. a tracking issue for a known bug).
- Roadmap speculation, "future backends", "not currently scoped" parentheticals.
- Build-system internals beyond what the consumer needs to invoke.
- Test-suite layout (`testing` module's test sources can be referenced as a
  recipe when genuinely useful; flag it as internal).

Put maintainer guidance in `AGENTS.md`, `ARCHITECTURE.md`, or this file. Keep the
user guide focused on tasks that consumers perform.
