# Atomic capture

Atomic capture freezes one Compose window into a PNG plus a versioned semantics tree
(`capture.json`) taken under the same EDT/intent tick. Agents and scripts get only a
decision-grade summary back; the full tree stays on disk for `jq` and other tools.

Do not confuse this with CLI/MCP **fullscreen screenshot** (full virtual desktop PNG, no
tree) or **primary-display / window recording** (MP4 paths). Mode comparison:
[CLI — Capture and recording](cli.md#capture-and-recording).

## CLI

```shell
spectre capture <session-id> [--window N] [--out-dir DIR] [--json]
spectre captures list [--all] [--json]
spectre captures prune --keep 20
spectre captures prune --session <session-id>
```

Default layout:

```text
$TMPDIR/spectre/captures/NNNN-<timestamp>/
  capture.json
  screenshot.png
```

See [CLI](cli.md) for full flags, retention, and detach leftover reports.

## Agent skill: `spectre-capture`

Ship path in this repository: [`skills/spectre-capture/SKILL.md`](https://github.com/rock3r/spectre/blob/main/skills/spectre-capture/SKILL.md).

Install or copy that skill into the locations your agent harness already loads (for example
project `skills/`, Claude skill dirs, or the packaged `skill/` tree for the general automation
skill). Capture summaries and detach reports name **`spectre-capture`** so agents can discover
it in-band.

The skill covers:

- schemaVersion **1** field reference
- ready-made `jq` recipes (clickable nodes by text, bounds by test tag, tree diffs)
- capture → act → capture → diff workflow
- node-key lifetime (re-capture after navigation / re-attach / hot reload settle)
- division of labor when both Compose Hot Reload MCP and Spectre are configured
- `spectre captures prune` cleanup guidance
- a manual find-click-verify recipe against a live fixture

For reload settle and generation-stamped keys, see
[Compose Hot Reload awareness](hot-reload.md).

## Schema versioning

`capture.json` is stable API. When `schemaVersion` bumps:

1. Update golden fixtures under `core/src/test/resources/capture/`.
2. Bump the **`spectre-capture`** skill (content + `package.json` version).
3. Update this page and [CLI](cli.md) examples.
4. Note the skill bump on the release checklist in [PUBLISHING.md](https://github.com/rock3r/spectre/blob/main/docs/PUBLISHING.md).

## In-process API

```kotlin
automator.waitForVisualIdle() // settle first when pixels matter
val result = automator.capture(windowIndex = 0)
// result.captureJson + result.pngBytes; write via your own paths or the agent/CLI surfaces
```

With `spectre-recording` present, the PNG is a **window-scoped** native still (same path as
`screenshot(windowIndex)`), not a silent desktop crop after a failed native still. Without
recording on the classpath, the still is a Robot region capture of the Compose surface (agent
inject payloads often omit recording). Settle the UI before calling so the semantics tree and
pixels stay decision-grade across native still-helper latency.

Library details live in `:core` under `dev.sebastiano.spectre.core.capture`.

## Pixel scale

`screenshot.png` is written at **screen-pixel** size, not dp size — the same rule
[recording](../RECORDING-LIMITATIONS.md) follows.
A 1600×1000dp window on a 2× display produces a **3200×2000 pixel** PNG. On a 1× display the two
sizes coincide.

`capture.json` always states which is which, so nothing has to be inferred from the file:

| Field | Units | 1600×1000dp window at 2× |
|---|---|---|
| `window.imageWidth` / `imageHeight` | PNG pixels | `3200` / `2000` |
| `window.boundsScreen` | logical screen units | `width: 1600, height: 1000` |
| `window.densityScaleX` / `densityScaleY` | ratio | `2.0` / `2.0` |
| `nodes[].boundsImage` | PNG pixels | scales with the PNG |
| `nodes[].boundsScreen` | logical screen units | use this for input targeting |

Two consequences worth knowing:

- **Node bounds do not need a density conversion.** `boundsImage` is derived from the actual PNG
  size, so it addresses the PNG correctly at any scale. Keep using `boundsScreen` for clicks —
  input coordinates stay logical.
- **Comparing PNGs across machines needs care.** The same UI captured on a 1× and a 2× display
  yields different pixel dimensions. Normalise with `densityScale*`, or assert on `boundsScreen`.

This applies to every still Spectre writes — `spectre capture`, `spectre screenshot --fullscreen`,
in-process `screenshot(windowIndex)` / `screenshot(node)` / `screenshotAtDeviceScale(region)`, and
the JUnit failure artifacts below.

!!! warning "One exception: an older `spectre-core` in the target application"

    On the attach path the pixels come from the `spectre-core` already on the **target's**
    classpath, not from the CLI. A target running a core older than screen-pixel stills has no
    `screenshotAtDeviceScale`, so `screenshot --fullscreen` falls back to its logical-size
    `screenshot(region)` and `capture` uses that core's own still behaviour — the command succeeds
    and returns a **dp-sized** PNG. This is deliberate: refusing would break attach against
    applications Spectre otherwise drives fine.

    `capture.json` still describes what you actually got, so the check is the same one as always —
    compare `window.imageWidth` against `window.boundsScreen.width`. Bump the `spectre-core`
    dependency in the target application to get screen-pixel stills.

!!! note "Oversized fullscreen stills degrade instead of failing"

    `spectre screenshot --fullscreen` is the one still whose size nothing bounds — a multi-monitor
    HiDPI desktop can encode past the attach transport's frame budget (64 MiB by default, which
    holds a worst-case 4K desktop). When the screen-pixel PNG would not fit, Spectre drops to a
    **logical**-resolution desktop still rather than failing the command. Compare the PNG size
    against your desktop's logical bounds if you need to know which one you got, and raise
    `--max-frame-bytes` / `$SPECTRE_MAX_FRAME_BYTES` to get the full-resolution still — see
    [Agent — Payload limits](agent.md#payload-limits-204).

    That drop is best-effort, not a guarantee: below a certain budget no desktop screenshot fits at
    any resolution, so there is nothing to degrade to. If even the logical still overruns you get a
    `payloadTooLarge` error naming the flag that fixes it, rather than a silent failure.

    On a **mixed-density multi-monitor** desktop a fullscreen still is also bounded by
    `java.awt.Robot`, which derives one scale from the display under the centre of the captured
    rectangle rather than capturing each display at its own. The still comes back at that display's
    scale, so a desktop centred on a 1x monitor downsamples the Retina parts. Window-scoped stills
    are unaffected — they use the target window's own screen scale.

    Window-scoped stills are bounded by the window, so they effectively never hit the budget. A
    `capture` of an extremely large window on a high-density display is the exception: it fails
    loudly with a `payloadTooLarge` error rather than downgrading, because `capture.json` records
    the PNG's exact size and must keep agreeing with it. Raise the budget and retry.

The one deliberate exception is in-process `ComposeAutomator.screenshot(region)`, which stays
**logical**-sized so image coordinates equal screen coordinates — that 1:1 mapping is what makes it
useful for pixel assertions addressed by `boundsOnScreen`. Call `screenshotAtDeviceScale(region)`
for the screen-pixel version of the same region.

## Failure artifacts from JUnit

On a **failed** Spectre JUnit test, `ComposeAutomatorExtension` / `ComposeAutomatorRule`
write the same `capture.json` + `screenshot.png` layout under `build/reports/spectre/`
(not `$TMPDIR`), at the same screen-pixel scale — they go through `capture()` too. See [JUnit integration — Failure artifacts](junit.md#failure-artifacts)
and the CI upload snippet in [Running on CI](ci.md#failure-artifacts).
