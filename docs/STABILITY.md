# Stability policy

Spectre is **pre-1.0**. This page documents what changes between releases, what doesn't, and
how the project signals the difference at the source / compiler / binary level.

The companion document on the trust model lives at [Security notes](SECURITY.md).

## Versioning

Spectre follows [Semantic Versioning](https://semver.org/):

- **Major** (`X.0.0`) — breaking changes to the stable public API.
- **Minor** (`0.X.0`) — additive changes to the stable public API; experimental APIs may change.
- **Patch** (`0.0.X`) — bug fixes; experimental APIs may change.

**Pre-1.0 caveats.** The project has not yet shipped 1.0. Minor versions may introduce breaking
changes to the stable public API until 1.0; that's the whole point of the pre-1.0 window. Each
release notes call those out explicitly.

## API tiers

Spectre distinguishes three tiers of public API. The distinction matters because experimental
APIs are still *visible*: they appear in jar bytecode and in [Kotlin Binary Compatibility
Validator](#binary-compatibility-validation) dumps. The tier controls what guarantees you get,
not whether you can call the code.

### Stable public API

Public declarations **not** annotated with `@InternalSpectreApi` or any experimental opt-in
annotation. Covered by binary-compatibility guarantees within a major version: a method
signature won't change, a class won't disappear, a property won't change type. Source-level
breaking changes (parameter renames with default values, etc.) are also treated as breaking
within a major version, even though [BCV](#binary-compatibility-validation) doesn't catch
every such case.

The committed `api/<module>.api` baselines in each library module enumerate the stable
public API for that module.

### Experimental public API

Public declarations annotated with `@ExperimentalSpectreHttpApi` (or any future per-area
`@ExperimentalSpectre*Api` markers we add). These are **visible** to consumers and **appear in
the API baselines**, but they are explicitly **not covered** by compatibility guarantees and
may change or be removed in any release, including patch releases.

Today the only experimental marker is `@ExperimentalSpectreHttpApi`, which covers the entire
`server` module's public surface — the HTTP transport (`installSpectreRoutes`,
`HttpComposeAutomator`, the `ComposeAutomator.http(...)` factory, and the DTOs). Authentication,
TLS, narrower per-window capture, and other major reshapes are tracked under #96 and will land
under this marker.

Consumers opt in either at file level or call site:

```kotlin
@file:OptIn(ExperimentalSpectreHttpApi::class)

import dev.sebastiano.spectre.server.ExperimentalSpectreHttpApi
import dev.sebastiano.spectre.server.installSpectreRoutes
```

The annotation is `WARNING`-level, so callers that ignore the opt-in still compile — they just
see a compiler warning per call site. The instability guarantees are the same either way.

### Internal escape hatch

Public declarations annotated with `@InternalSpectreApi`. **Not** part of the supported
surface; `ERROR`-level opt-in (the compiler refuses without an explicit `@OptIn`). May change
without notice and without a deprecation cycle.

This marker exists because some in-repo collaborators (the HTTP transport, the recording
module's helpers, in-repo fixtures) legitimately need to reach into core's internals. End-user
code should not need this annotation — if you find yourself opting in, please file an issue
describing the use case so we can shape a stable API for it.

## Binary compatibility validation

Spectre uses the Kotlin Gradle Plugin's built-in **`abiValidation`** extension to track public
ABI per module. Each library module commits a baseline at `<module>/api/<module>.api` listing
every public class, function, property, and field that consumers depend on. The baseline is
a textual snapshot of the JVM-level public surface.

Workflow:

- **On every PR** — `./gradlew check` runs `checkKotlinAbi` automatically. A PR that changes
  the public surface without updating the committed baselines fails CI.
- **At release** — the release workflow re-runs `checkKotlinAbi` before building artefacts, as
  defensive depth against tag-from-feature-branch shenanigans that would otherwise skip the
  PR-time check.
- **Regenerating the baselines** — when a public API change is intentional, run
  `./gradlew updateKotlinAbi` locally, review the diff, and commit the updated `api/*.api`
  files alongside the source change.

### What's covered

- Public JVM ABI of `core`, `testing`, `server`, `recording` — class shapes, method signatures,
  property types, field visibility, inheritance.
- Both stable and experimental APIs appear in baselines. The annotation level controls the
  *guarantee*, not the *visibility*.

### What's not covered

- **Annotation usages on individual symbols.** The baseline records the shape of declarations,
  not the annotations attached to them. Silently removing `@ExperimentalSpectreHttpApi` from a
  marked declaration would not fail `checkKotlinAbi`, because removing an annotation doesn't
  change the JVM symbol shape. The opt-in / experimental-marker layer is enforced at compile
  time by the Kotlin compiler (via `@RequiresOptIn`), not by ABI validation. Source review
  remains the gate for the marker layer; ABI validation is the gate for the symbol layer.
- Source-level breakages that don't change JVM ABI (renaming a parameter that has a default
  value, removing the default from a parameter, etc.).
- Runtime artefacts: the HTTP transport's wire format, recording video output, helper
  extracted-binary layout, semantics-tree refresh ordering, etc.
- Sample apps (`sample-desktop`, `sample-intellij-plugin`) — not published, no baseline.

## Platform support tiers

Spectre is JVM-first and targets desktop OSes.

| Platform | Tier | Notes |
| --- | --- | --- |
| **macOS** | Primary | Full support. AWT Robot input, ScreenCaptureKit recording (with bundled Swift helper), TCC permission diagnostics. |
| **Windows** | Full | AWT Robot input + `gdigrab` region and window-targeted recording. |
| **Linux Xorg** | Full | AWT Robot input + `x11grab` region recording. Validated against Xvfb in CI. |
| **Linux Wayland** | Best-effort | Portal-mediated capture via `WaylandPortalRecorder` / `WaylandPortalWindowRecorder`. **Validated on GNOME/Mutter only**; KDE / sway / wlroots compositors may behave differently and are not exercised in CI. Real Robot input is unavailable on Wayland — use the synthetic adapter for tests. |
| **BSD** | Unsupported | Not built or tested. |

## AndroidX-style stability expectations

Spectre adopts the cultural convention pioneered by [AndroidX](https://developer.android.com/jetpack/androidx):

- Experimental APIs are explicit opt-in via a `@RequiresOptIn` annotation. The annotation
  carries the rationale ("not yet stable", "may change", etc.) and the opt-in is per file or
  per call site, not project-wide.
- The compiler — not just docs — enforces the opt-in. Forgetting it is a warning (for
  `@ExperimentalSpectreHttpApi`) or an error (for `@InternalSpectreApi`).
- Stability promotions follow a deprecation cycle when feasible: when an experimental API
  graduates to stable, the marker is removed in the release that promotes it; when an
  experimental API is removed, it's done in a single release with the rationale documented.

## Reporting incompatibilities

If a change in a Spectre release breaks your build or runtime in a way that wasn't called out
in the release notes, please open an issue at
<https://github.com/rock3r/spectre/issues> with the diff (a `git log -p api/`-style snippet
of the BCV baseline change is most useful) and what you observed.
