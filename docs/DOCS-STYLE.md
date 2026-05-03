# Docs style guide

Internal note for whoever (human or agent) writes or revises the user-facing docs at
<https://spectre.sebastiano.dev>. **This file is excluded from the published site** via
`exclude_docs` in `mkdocs.yml` — it's a maintainer reference, not user content.

The user guide lives under `docs/index.md` and `docs/guide/`; supporting reference
material lives at `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `docs/CONVENTIONS.md`,
`docs/STATIC-ANALYSIS.md`, and `docs/RECORDING-LIMITATIONS.md`. All of those render
into the same site and follow the same rules.

## The five rules that catch the most regressions

1. **Verify every API claim against source before writing.** Type signatures, default
   values, parameter names, visibility, exceptions, and behavioural notes drift the
   moment you stop checking. Read the relevant `.kt` file before claiming what an API
   does.
2. **Code samples must compile mentally.** Imports at the top of the block before any
   statement. No phantom APIs, no fictional companion functions, no wrong package
   paths. If a sample uses an extension function, import it explicitly.
3. **No milestone language in public-facing prose.** No `v1`, `v2`, `v3`, `v4`. No
   GitHub issue numbers like `#18` or `#77 stage 2`. No "future backend" speculation
   or "not currently scoped" parentheticals. Public docs describe what ships now.
4. **Be honest about pre-release state, but only when the user needs to know.** The
   project README is upfront that the codebase is rough; the user guide should match
   that energy where consumption is affected (no published artifacts, snapshot-only
   versions) and stay neutral elsewhere. No marketing tone.
5. **`mkdocs build --strict` is part of the contract.** It catches broken links,
   missing anchors, and pages-not-in-nav. Run it before pushing any docs change.
6. **Don't leak implementation details into the user guide.** Users care about the
   public contract: what they call, what they get back, what behaviour to expect.
   They do not care about visibility modifiers (no "the X constructor is
   `internal`"), about test-source helpers (no "`newHeadlessAutomator()` is
   internal but you can copy the recipe"), or about how the implementation
   achieves the contract (no "the implementation hashes each surface…", just
   "Spectre/`waitForVisualIdle` hashes each surface…"). If you're tempted to
   write "the implementation does X", ask whether the user needs to know X is
   load-bearing or whether you're just narrating the source. If it's narration,
   cut it.

## Writing for the user

- **Audience: someone testing a Compose Desktop app for the first time.** They know
  Kotlin and have a Gradle project. They've never used Spectre. They don't care about
  internal milestones or refactoring history.
- **Lead with the task, not the architecture.** "Click a button" comes before "what
  an automator owns". Mental-model pages exist (e.g. `automator.md`) but the entry
  points are `installation.md` → `getting-started.md`.
- **Tone is precise, slightly dry, comfortable with technical detail.** Match the
  voice of `README.md` and `ARCHITECTURE.md`. Don't oversell. Don't apologise.
- **Show, don't enumerate.** A working code sample beats a bullet list of method
  signatures. If you do list APIs, link each to a sample.
- **Cross-link liberally.** A user reading `selectors.md` shouldn't have to guess
  where `waitForNode` is documented — link it inline.

## Code samples

Spectre is a Kotlin library, so samples are usually Kotlin. A sample is wrong if any of
the following are true:

- An imported symbol doesn't exist in the source.
- A function call uses a non-existent named argument.
- A modifier mask is on the wrong class (e.g. `KeyEvent.CTRL_DOWN_MASK` instead of
  `InputEvent.CTRL_DOWN_MASK`).
- An interface is "constructed" as if it were a data class.
- An import sits below executable statements (Kotlin imports must precede
  declarations).
- The sample uses `internal` API. The adapter-injecting `RobotDriver` constructor is
  a real-world example: it's `internal`, so samples must use the public no-arg form
  or a public companion factory.
- The sample claims behaviour the implementation doesn't honour (e.g. "auto-wait
  finds the node" when queries are explicitly non-waiting).

When in doubt, grep the source. `findOneByContentDescription` doesn't exist;
`findByContentDescription(...).firstOrNull()` is what callers write.

### Imports in samples

Show the imports the sample actually needs, including extension-function imports. The
`RobotDriver.synthetic(...)` and `Frame.asTitledWindow()` paths require explicit
imports because they're companion/member extensions — leave those imports out and
the sample doesn't compile.

### Default arguments and named parameters

If a sample uses `foo(bar = 5)`, that named argument has to actually be the parameter
name in source. `synthetic(window = …)` was wrong; the source parameter is
`rootWindow`. These mistakes cluster around recent renames — re-check after any source
change that touches a public signature.

## API and behaviour invariants worth re-checking

These have bitten reviews already; they're worth re-verifying every time the
corresponding doc page is touched:

- **No auto-wait.** Queries (`findByTestTag`, `findByText`, etc.) do a single read.
  They never retry. Document them as such.
- **EDT rule, but not for `waitForNode`.** `waitForIdle` and `waitForVisualIdle`
  reject EDT callers via `rejectEdtCaller`. `waitForNode` polls through `readOnEdt`
  and is exempt. Don't say "all wait helpers reject the EDT".
- **`refreshWindows` is only auto-called by `tree()`.** `findBy*` and `allNodes`
  read against the last refresh. If a popup may have appeared since the last call,
  the user has to refresh.
- **`findOneBy*` exists only for `testTag` and `text`.** Not for content
  description, not for role.
- **`waitForNode(tag, text)` is AND, not OR.** Both criteria must match the same
  node.
- **`AutomatorIdlingResource.isIdleNow` and `diagnosticMessage()`.** Not `isIdle()`,
  not `name`. Identity-based register/unregister.
- **`AutoRecorder` routes Wayland first.** Then `window == null`, then macOS SCK,
  then Windows title-based, then ffmpeg region as fallback. Wayland never falls
  through to `x11grab`.
- **`TitledWindow` is an interface.** Production adapter is `Frame.asTitledWindow()`.
  Tests provide minimal `TitledWindow` implementations.
- **`installSpectreRoutes` is engine-agnostic.** The `server` module deliberately
  doesn't bundle Netty/CIO/Jetty — consumers add the engine themselves.
- **`HttpComposeAutomator.DEFAULT_PORT` is `9274`.** That's the *client* default;
  the server-side port is whatever the consumer's Ktor engine listens on.
- **`RobotDriver` public constructors.** `RobotDriver()` (default AWT), `RobotDriver(robot)`,
  `RobotDriver.headless()`, `RobotDriver.synthetic(rootWindow)`. The
  adapter-injecting constructor is `internal` and not for consumers.
- **`RobotDriver.headless()` throws on input/screenshot/clipboard.** Every input,
  clipboard, and screenshot call raises `UnsupportedOperationException` so accidental
  real-I/O calls fail at the call site. `WindowTracker`/`SemanticsReader` are
  untouched, so semantics-tree reads still work.
- **`typeText` is always clipboard-paste.** It writes the text, dispatches the
  platform paste shortcut, drains, then restores the previous clipboard contents.
  No per-key fallback.
- **Linux Wayland + `LinuxX11Grab` throws.** It does not silently produce black
  frames. The thrown message points users at `AutoRecorder`/`WaylandPortalRecorder`.

## Style conventions

- **British spelling in prose. American spelling in code.** Markdown text uses
  `behaviour`, `serialisation`, `serialise`, `colour`, etc. **Do not** rewrite
  identifiers, package names, framework symbols, or anything inside a code fence —
  Kotlin's `@Serializable`, `Color`, `BehaviorSubject`, `Initializer`, kotlinx
  `serialization`, etc., stay en-US because that's how the JVM ecosystem spells them.
  Bulk-rename scripts must use `\b…\b` boundaries and skip code fences. Words inside
  backticks (e.g. ``` `Initializer` ```) are code: leave them en-US.
- **Comma after `e.g.` and `i.e.`** ("e.g., foo", "i.e., bar"). Always.
- **Slashes are tight.** `mouse/keyboard/typing`, not `mouse / keyboard / typing`.
  For two-item alternatives prefer "or"/"and": `clipboard or screen`, not
  `clipboard / screen`. For two named code symbols, `\`A\`/\`B\`` rather than
  `\`A\` / \`B\``.
- **Em dashes don't end lines wrapped to the next line.** Either keep the dash with
  text on both sides on one line, or move it to the start of the wrapped line.
- **Headings sentence-case.** "Real vs. synthetic input", not "Real vs. Synthetic
  Input".
- **Code identifiers in backticks.** Class names, method names, property names,
  package names. Module names too: `core`, `testing`, `server`, `recording`.
- **Don't pack multi-paragraph content into a single list item.** Mixing 2-space
  list-continuation indent with 4-space sub-block indent breaks mkdocs-material's
  list parser — subsequent top-level bullets get folded into the previous item as
  continuation prose. If a bullet needs more than one paragraph plus a nested
  list, promote the topic to its own H3 / H4 subsection or use prose paragraphs
  instead. Verify in the rendered output that subsequent siblings still render as
  siblings.
- **Always leave a blank line between a paragraph and a list that follows it.**
  Pattern: a line ending in `:` (or any prose continuation) directly above a `-`
  list item without a blank line in between. CommonMark / mkdocs-material renders
  the whole thing as a single paragraph with literal `- ` separators inline. The
  cure is one blank line between the paragraph and the first list item.

## Linking

- **Inside `docs/`: relative links only.** `[Synchronization](synchronization.md)`,
  `[Architecture](../ARCHITECTURE.md)`. `mkdocs build --strict` resolves these.
- **Outside `docs/` (workflows, repo-root files, source code): absolute
  `https://github.com/rock3r/spectre/...` URLs.** A relative path like
  `../.github/workflows/...` works in GitHub's preview but fails the strict build,
  because the target lives outside `docs_dir`.
- **Anchor links: re-verify when renaming sections.** Strict mode catches missing
  files, but anchor mismatches (`#build-from-source` after renaming the heading to
  "Consume as a composite build") slip past the file-level check.

## Workflow/CI

- **Permissions scoped per job, not per workflow.** The build job (which runs
  PR-controlled mkdocs config and pip dependencies) gets `contents: read` only.
  `pages: write` and `id-token: write` belong on the deploy job.
- **PRs build, only push-to-main + workflow_dispatch deploys.** Don't surface a
  Pages deployment from a PR build.
- **CNAME lives at `docs/CNAME`.** It's copied through into the built site by
  mkdocs and survives across deploys, so the custom domain doesn't get stripped.

## When the source changes

When a public API in `core`, `testing`, `server`, or `recording` changes, walk the
affected docs:

| If you touch…                                                           | Re-read…                                    |
| ----------------------------------------------------------------------- | ------------------------------------------- |
| `ComposeAutomator`, query/interaction methods, wait helpers              | `automator.md`, `selectors.md`, `interactions.md`, `synchronization.md` |
| `RobotDriver` factories, public constructors                            | `interactions.md`, `automator.md`           |
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

Things that don't belong on the public site, even if they're true:

- Internal milestone narrative (kept out of `docs/` entirely now that
  `bootstrap-plan.md` is gone).
- Open issue numbers and PR numbers, except where the issue itself is an actionable
  user-facing reference (e.g. a tracking issue for a known bug).
- Roadmap speculation, "future backends", "not currently scoped" parentheticals.
- Build-system internals beyond what the consumer needs to invoke.
- Test-suite layout (`testing` module's test sources can be referenced as a
  recipe, but only when the recipe is genuinely useful — flag it as internal).

If a piece of information is interesting to maintainers but not consumers, it
belongs in `AGENTS.md`, an `ARCHITECTURE.md`-shaped doc, or this file — not the
guide.
