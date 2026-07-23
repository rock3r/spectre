# #209 inject-runtime packaging (M2 / #313)

## Artifacts

| Artifact | Contents | Compose |
| --- | --- | --- |
| `spectre-agent-runtime` | Thin agent + IPC + nested inject jar resource | Not bundled |
| nested `META-INF/spectre/inject-runtime.jar` | `:core` + relocated kotlinx (+ tracing/okio/wire) | **Not** bundled |

## Relocation

Shadow relocates:

- `kotlinx.coroutines` → `dev.sebastiano.spectre.inject.relocated.kotlinx.coroutines`
- `kotlinx.atomicfu` → `dev.sebastiano.spectre.inject.relocated.kotlinx.atomicfu`

Compose / Skiko / Kotlin stdlib are excluded from the inject jar and resolve against the
**target** classloader (parent of `SpectreInjectClassLoader`).

## Bootstrap

1. Prefer preinstalled `ComposeAutomator` (existing thin-agent path, D-14).
2. Else extract nested inject jar → `SpectreInjectClassLoader(parent = Compose host)`.
3. Compose host discovery uses `ComposeWindow` / `ComposePanel` / `SemanticsOwner` + D-14.

## Verification

- `:agent-inject-runtime:verifyInjectRuntimeJarContents`
- `:agent-runtime:verifyAgentRuntimeJarContents` (nested resource present; no exploded core)
- `AgentInjectAttachIntegrationTest` — attach + tree dump with spectre-core stripped from target CP
