# Security notes

Spectre is a JVM-first library for **automating live Compose Desktop UIs in trusted local /
test environments**. It can drive synthetic or real OS input, captures screenshots, and
records screen content by design. This page documents the trust boundaries Spectre relies on, the security-sensitive
capabilities it exposes, and the risks that are explicitly accepted for the pre-1.0 release.

Spectre is **pre-1.0**. The HTTP transport and cooperative desktop input coordinator are
**experimental** and expected to change.

## Reporting a vulnerability

Please email security reports to **spectre@sebastiano.dev**. Do not open a public GitHub
issue for security reports.

GitHub's private vulnerability reporting flow may also be used once it is enabled on this
repository.

## Trust boundaries

Spectre assumes the following trust model. Capabilities outside these boundaries are
out of scope.

1. **Test code is trusted.** A malicious test process can already run arbitrary JVM code.
   Spectre does not try to constrain what its callers can do; it only tries not to expose
   capabilities to unintended callers.
2. **The local OS is trusted.** Real `RobotDriver`, screenshots, and recording act with the
   privileges of the JVM process and the OS permissions granted to it (macOS TCC, Wayland
   portal, X server access, etc.).
3. **The HTTP transport assumes an authenticated deployment peer.** Routes registered by
   `installSpectreRoutes` expose click, keystroke, and screenshot capture to a caller holding the
   deployment bearer. HTTPS is mandatory except for an explicit loopback-only test mode. The bearer
   authorizes the whole transport; Spectre does not provide users, roles, or per-operation grants.
4. **The agent transport assumes a same-user peer.** `:agent`'s Unix Domain Socket is created
   under a short private directory (in `/tmp/` on Linux/macOS; under `%TEMP%` on Windows, or
   `%LOCALAPPDATA%\Temp` when `%TEMP%` is too deep to leave room for the socket path). On
   Linux/macOS the directory is mode 0700 and the socket is mode 0600. On Windows/NTFS, where
   POSIX modes are meaningless, `IpcServer` instead applies an **owner-only ACL** — a single
   ALLOW full-control ACE for the owning user, with inherited ACEs dropped — to both the private
   directory and the socket file. Either way the protection is set explicitly by `IpcServer` to
   defend against permissive umasks or inherited ACLs, and the same-user preflight compares the
   attacher's and target's process owners (numeric UID on POSIX when available, otherwise
   `ProcessHandle` usernames; see #166) rather than trusting the socket alone.
   If callers override `AttachOptions.udsPath` with a path under an existing directory, they own
   that parent directory's permissions; Spectre only tightens directories it creates itself.
   Any process running as the same OS user can connect and drive the target. There is no
   authentication, no encryption, and no origin check. The agent transport is intentionally
   a testing affordance for the same machine and the same user, not a remote-control
   protocol. See [Agent attach](guide/agent.md).

5. **The input coordinator is a same-user cooperative boundary.** Its canonical endpoint lives in a
   short owner-checked directory and rejects symlink/path substitution: neither the endpoint
   directory nor any ancestor of it may be a symbolic link, with macOS's root-level `/tmp`, `/var`,
   and `/etc` aliases into `/private` as the only exemption. The default endpoint is canonical
   before it is prepared. A caller-supplied endpoint must be an absolute path beneath a directory
   the caller already trusts: Spectre detects substitution there but does not verify who owns the
   ancestors. On POSIX filesystems it enforces directory mode 0700 and socket mode 0600. On Windows
   it uses the current user's `LOCALAPPDATA` and inherits the existing directory ACL; unlike the
   agent transport, the coordinator layer does not replace that ACL with an owner-only one.
   Traversal to the endpoint is `openat`-style on Linux — each component is resolved exactly once,
   relative to a descriptor already open on the component above it — so an ancestor cannot be
   substituted midway through the walk. This is not a POSIX-wide property: the JDK only offers an
   `openat`-backed `SecureDirectoryStream` where all six `*at` calls resolve, which excludes macOS,
   and Windows has no equivalent at all. On those hosts the walk stays lexical and re-resolves the
   whole path on every check, leaving a component accepted a moment earlier open to being swapped
   before the next call reaches through it (#487); the endpoint is still owner-only and every
   component is still rejected on sight. Creating a missing component resolves an absolute path
   everywhere; on Linux a walk that finds its parent substituted at that point fails rather than
   adopting what it created. Java
   Unix-domain sockets do not expose portable peer credentials, so requester labels are
   self-reported attribution, not authentication. The coordinator never records typed text,
   clipboard contents, selectors, credentials, or prompts.

   Revocation requires the exact observed lease ID. Normal revoke fences the owner and waits for
   cleanup acknowledgement. Session EOF also fences a live holder rather than advancing FIFO,
   because transport loss does not prove that native input has stopped. `--force` is an explicit
   unsafe decision recorded as `unsafeTakeover=true`; it cannot retract an uninterruptible native
   call and never kills a process. Coordinator restart enters recovery quarantine rather than
   assuming the desktop is free, and that quarantine never expires automatically. An operator
   must inspect it and force recovery with the exact predecessor lease ID.
6. **Bundled native helpers are trusted artifacts.** Spectre extracts and executes Swift
   (`spectre-screencapture`), Rust/Linux (`spectre-wayland-helper`), and Windows
   (`spectre-window-capture.exe`) helpers from the published jar
   resources. The extraction path is process-private; the helpers are launched with `argv`
   lists (never shell strings). Developer-only override env vars exist for local iteration
   and are explicitly documented as such — see the
   [`SPECTRE_WAYLAND_HELPER` note](#developer-only-override-env-vars) below.
7. **External binaries (ffmpeg, GStreamer, xprop, osascript) come from the host PATH.** Spectre does not
   pin versions and treats them as prerequisites of the host environment.

## Capabilities and their exposure

| Capability | Surface | Default exposure |
| --- | --- | --- |
| Move mouse / press keys | `RobotDriver.click`, `moveTo`, `moveBy`, `swipe`, `pressKey`, `scrollWheel` | In-process; trusted-local HTTP via `/spectre/click` |
| Modify clipboard | `RobotDriver.pasteText` (save / set / paste / restore) | In-process |
| Capture pixels | `RobotDriver.screenshot(region)` — **captures any rectangle of the virtual desktop**, not just the app under test; `AutoScreenshotter` for native/window-targeted still screenshots where available | In-process; trusted-local HTTP via `/spectre/screenshot` for `RobotDriver`; `AutoScreenshotter` is in-process only |
| Record video | `AutoRecorder`, native recorders, deprecated explicit `FfmpegRecorder`, `WaylandPortalRecorder` | In-process only |
| Execute a helper binary | `HelperBinaryExtractor` (SCK), `WaylandHelperBinaryExtractor` | Local file system, JVM process |
| Expose any of the above over HTTP | `installSpectreRoutes` mounts windows, nodes/`node`, tree/`printTree`, input verbs, and screenshot (full-frame or `?nodeKey=`) | Deployment bearer on every non-preflight request; **HTTPS required by default**; CORS denied unless exactly allowlisted |
| Expose any of the above over UDS | `:agent`'s `IpcServer` mounts the same surface plus detach over a Unix Domain Socket | **Unauthenticated** — owner-only filesystem access (POSIX mode 0600 on Linux/macOS, owner-only ACL on Windows/NTFS); same OS user only. Supported on Linux, macOS, and Windows (10 version 1803 / Server 2019+) |

The HTTP exposure column is the most important one to internalise: possession of one deployment
bearer grants every route, including real input and screenshots. Generate a high-entropy token,
deliver it through a secret manager or environment variable, and never put it in source,
command-line arguments, URLs, or logs.

## HTTP exposure controls

- **Bearer authentication.** `SpectreHttpSecurity` is required by both server and client. Every
  request except a valid CORS preflight carries `Authorization: Bearer …`; comparison uses
  `MessageDigest.isEqual`. Spectre does not log or echo the configured or presented token, and its
  client does not install Ktor's logging plugin. Applications that add request logging must redact
  `Authorization`.
- **HTTPS by default.** `HttpComposeAutomator` constructs `https://` URLs and the server rejects
  non-HTTPS requests. `allowInsecureLoopback = true` is a test-only escape hatch: the client accepts
  only `localhost` or a literal loopback address, and the server checks that the plaintext TCP peer
  address is loopback (not the reverse-DNS name, which on Windows is the computer name). Scoped
  IPv6 loopback addresses count. TLS keys, certificates, and connector lifecycle remain the host
  Ktor application's responsibility.
- **Reverse proxies.** When TLS terminates at a proxy, configure Ktor's forwarded-header handling
  only if the application's direct peers are trusted proxies. Spectre uses Ktor's resolved origin;
  trusting arbitrary client-supplied forwarding headers can bypass the HTTPS check.
- **CORS fails closed.** With the default empty `allowedOrigins`, any request carrying `Origin` and
  every browser preflight is rejected. Configured origins are exact matches; `*` is rejected.
  Preflights may request only `GET` or `POST` and the `Authorization` / `Content-Type` headers.
  Requests without `Origin` still require the bearer.

## What R5 changed

The pre-publishing security review (R5) made the following changes; everything else listed
under [Accepted risks](#accepted-risks-deferred-follow-ups) was deferred for a future,
separately reviewed pass.

- **Trust-boundary documentation** added on the user-facing cross-JVM guide, on
  `installSpectreRoutes`, on `ComposeAutomator.http(...)`, on `RobotDriver.screenshot`, and on
  the `SPECTRE_WAYLAND_HELPER` developer override.
- **Pinned loopback bind in the cross-JVM example.** The published worked example now passes
  `host = "127.0.0.1"` explicitly to `embeddedServer(...)`. Earlier guidance omitted the host
  argument; the explicit pin is engine-independent and removes any reliance on whichever
  default a given Ktor engine happens to apply.
- **Stopped echoing attacker-controlled content in HTTP error bodies.**
    - The server's decode-error mapping (`receiveOrRespond400`) responds with just the
      curated request type name (`Could not decode ClickRequest`) instead of interpolating
      the underlying `BadRequestException` message.
    - The `/click` malformed-key 400 response no longer interpolates the caller-supplied
      key string into the body.
    - The `/click` no-matching-node 404 response no longer echoes the caller-supplied
      `nodeKey`.
    - The HTTP client (`HttpComposeAutomator.click` / `typeText`) no longer interpolates
      `response.bodyAsText()` into the thrown `IllegalStateException` — peer body content
      cannot reflect into logs / test output through these exception messages.
- **New tests** in `HttpNegativeContractTest` pin all four no-echo behaviours so a future
  regression is caught at `./gradlew check`.

## Accepted risks / deferred follow-ups

These risks are accepted for the pre-1.0 release.

- **Coarse HTTP authorization.** The deployment bearer grants the complete HTTP surface. There are
  no identities, roles, route-specific grants, built-in token rotation, or rate limiting.
- **Host-managed TLS.** Spectre enforces the request scheme and defaults its client to HTTPS, but
  certificate issuance, private-key storage, connector configuration, and trusted-proxy policy
  belong to the host deployment.
- **Screenshot scope.** `RobotDriver.screenshot(region)` and full-frame HTTP `GET /screenshot` can
  capture pixels from unrelated windows. Node-targeted HTTP stills (`GET /screenshot?nodeKey=`)
  narrow the request but do not constrain the full-frame / Robot region path.
- **Recording output-path validation.** Spectre passes the caller-supplied output path
  through to ffmpeg, GStreamer, or the helpers without rejecting `/dev/`, `/proc/`, symlinks, or
  not-yet-existing parents. Standalone follow-up issue, separate from #96.
- **`pasteText` clipboard-restore robustness.** A failure during the post-paste restore is
  swallowed via `runCatching` (clipboard may be left holding the typed text). Standalone
  follow-up issue.
- **Windows agent socket: local administrators and SYSTEM retain access.** The owner-only ACL on
  the agent's private directory and socket blocks other standard users, but members of the local
  `Administrators` group and `SYSTEM` can take ownership of any file and therefore read or connect
  regardless — the same reality as `root` on a POSIX mode-0600 socket. The same-user preflight on
  Windows also treats elevated and non-elevated processes of the same account as the same user
  (they share the account SID); Windows may still deny the OS-level attach across integrity levels.
  These are accepted for the same-machine, same-user testing model.
- **Helper-extraction cleanup.** Extracted helper binaries are not `deleteOnExit`-registered.
  This is hygiene, not security (the extraction path is process-private and writable only by
  the user), but a tidy-up follow-up is worth tracking.
- **Supply-chain audit of Gradle plugins and external binaries** (ffmpeg, GStreamer,
  xdg-desktop-portal, OS APIs). Out of scope per the masterplan.

## Developer-only override env vars

The `SPECTRE_WAYLAND_HELPER` environment variable lets a developer point the recorder at a
locally-built helper binary without rebundling. It is **honored unconditionally** — there is
no signature check, hash check, or path constraint. Never set it in an environment that
ingests untrusted input. The published platform helper artifacts are the only supported
configuration for non-dev use.

On Linux Wayland, `spectre-wayland-helper --session` listens on a same-user unix socket under
`$XDG_RUNTIME_DIR/spectre/` (override with `SPECTRE_WAYLAND_SESSION_DIR`). Any process running
as that user can connect and drive pointer, keyboard, and monitor capture for the seat.
Treat that socket like the agent UDS: trusted local / same-user only.

`SPECTRE_CAPTURE_BACKEND` forces Linux still/video routing when auto-detection is wrong for a
nested setup: `x11` / `xorg` / `xvfb` → X11 helper path; `wayland` / `portal` → portal path;
unset or any other value → auto (pure-X11 `DISPLAY` probe, then session type /
`WAYLAND_DISPLAY`, then residual `wayland-*` sockets only if `DISPLAY` is unset — see #397).
Prefer fixing the environment (run under `xvfb-run` with a real Xvfb `DISPLAY`) over leaving
the override set in CI.

## Out of scope for this review

The R5 review explicitly did not cover:

- Making the HTTP transport safe for arbitrary untrusted networks.
- Designing authentication / authorization for a production remote-control service.
- Sandboxing untrusted test code.
- Full supply-chain audit of third-party dependencies.
- True Wayland window-targeted / window-following capture beyond what the portal helper
  exposes.

If a follow-up review uncovers an issue in any of the above areas, please use the reporting
flow at the top of this page.
