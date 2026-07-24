# Automator capability matrix

Spectre exposes the same *automation ideas* over three transports:

| Transport | Client type | Module |
| --- | --- | --- |
| **In-process** | `ComposeAutomator` | `:core` |
| **HTTP** | `HttpComposeAutomator` | `:server` |
| **Agent attach** | `AttachedAutomator` | `:agent` |

They deliberately do **not** share one Kotlin interface. The agent crosses a
reflection/classloader boundary with its own CBOR DTOs; HTTP has JSON payloads and
HTTP status semantics; in-process returns live `AutomatorNode` graphs. Forcing a single
runtime type leaks those boundaries.

**The contract is a shared contract-test corpus**
(`AutomatorContractCorpus` in `:testing`) driven against each transport's own client.
Cell states live in Kotlin as `CapabilityMatrix` — also in `:testing` — and this page
is the human-readable view of that source of truth.

## Cell states (multi-state, not binary)

| State | Meaning |
| --- | --- |
| **Supported** | Claimed working for that op × transport × platform. Must list executable evidence (test source + CI workflow). A Supported cell without resolvable evidence **fails** `CapabilityMatrixEvidenceTest` — fail-closed. |
| **Experimental** | Works in some environments; not a 1.0 guarantee. |
| **Unsupported by design** | Will not be offered on this transport (e.g. needs live JVM objects). Always includes a rationale. |
| **Not yet CI-executed** | Intended for the 1.0 surface, but no CI task has executed the cell yet. Docs must not claim "supported" until the state flips and evidence lands. |

Platform rows are **prerequisites**, not just OS names: headless JVMs, Linux Xvfb,
Wayland sessions, and interactive desktops behave differently for Robot and Compose.

## Contract form decision (#198)

| Option | Outcome |
| --- | --- |
| Single shared interface in `:core` | Rejected for 1.0 — agent reflection and HTTP status semantics do not fit cleanly. |
| **Shared contract-test corpus in `:testing`** | **Chosen.** Each transport implements `AutomatorContractDriver` and runs the same scenarios against the real client. |

Corpus runners today:

| Transport | Test | CI evidence |
| --- | --- | --- |
| In-process | `InProcessContractCorpusTest` | `.github/workflows/ci.yml` (`./gradlew check`) |
| HTTP | `HttpContractCorpusTest` | `.github/workflows/ci.yml` |
| Agent | `AgentContractCorpusTest` (+ `AgentAttachIntegrationTest`) | `.github/workflows/validation-linux.yml` (Xvfb, fail-closed JUnit XML), `.github/workflows/macos-check.yml` |

## Intersection ops (current shared surface)

These operations exist on all three clients and are the corpus core:

- `windows` / surface listing
- `allNodes`
- `findByTestTag`
- `click` (by canonical `surfaceId:ownerIndex:nodeId` on remote transports)

Headless CI exercises **transport liveness** (empty trees OK; unknown-key click must fail).
Fixture-backed semantics (non-empty windows, known tags, click/type/screenshot) are claimed
for the **agent** transport under Linux Xvfb and macOS desktop.

## Deliberate exclusions

These stay **in-process only** — they need live JVM objects that cannot cross a transport
boundary without a different design:

| Operation | Why |
| --- | --- |
| `registerIdlingResource` / idling resources | Live callbacks in the UI JVM |
| `withTracing` | Live tracer hooks |
| `waitForIdle` (idling-resource variant) | Same as idling resources |

Remote **waits** (`waitForNode` over agent — #201, also CLI `wait-for-node` / MCP
`wait_for_node`), **selectors** (`findByText` / role / content-description — #202), and **input
verbs** (`doubleClick` / `swipe` / `scrollWheel` — #203) are **Supported** on agent under Linux
Xvfb and macOS desktop via `AgentContractCorpusTest` against `agent-test-fixture`. Agent
`pressKey` is **Supported** on Linux Xvfb (fail-closed after focus retries) and
**Experimental** on macOS desktop (hosted runners may soft-skip OS keyboard focus loss after
retries — same class as `typeText`). HTTP selector entry points are covered by headless
`HttpContractCorpusTest`. Some HTTP input/wait cells and agent `longClick` /
`waitForVisualIdle` remain **Not yet CI-executed**.

## How to read a cell

1. Find the operation and transport of interest.
2. Check the platform prerequisite that matches your environment.
3. If the state is **Supported**, the evidence column (in `CapabilityMatrix`) names the
   test file and workflow that must execute it — CI must not silently skip that cell.
4. If you add a new op or flip a cell to Supported, update `CapabilityMatrix` **first**,
   land the corpus/test evidence, then refresh this guide if the human summary changed.

## Source of truth

| Artifact | Location |
| --- | --- |
| Matrix data + states | `testing/.../contract/CapabilityMatrix.kt` |
| Corpus runner | `testing/.../contract/AutomatorContractCorpus.kt` |
| Fail-closed evidence test | `testing/.../contract/CapabilityMatrixEvidenceTest.kt` |
| Epic | GitHub #197; matrix issue #198 |

Machine-check: `./gradlew :testing:test --tests "*CapabilityMatrixEvidenceTest*"`.
