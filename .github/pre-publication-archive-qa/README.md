# Temporary pre-publication exact-archive QA

This scratch workflow reads already-built GitHub Actions artifacts before publication. It cannot
build, alter, upload, or publish a product. Its job token has only `actions: read` and
`contents: read`. This is deliberately separate from the public-release verifier in
`post-publication-qa`.

The checked-in control is inert. A preparation commit is expected to remain `PHASE=HOLD`; only a
later, control-only commit with subject `Stage candidate Linux archives` may set `PHASE=READY`.
Use unquoted literal values:

```text
PHASE=READY
VERSION=0.10.0
REPOSITORY=rock3r/spectre
WORKFLOW_RUN_ID=<decimal Actions run id>
UPSTREAM_RUN_HEAD_SHA=<40-lowercase-hex commit at which the building workflow ran>
SOURCE_SHA=<40-lowercase-hex immutable product source commit>
X64_ARTIFACT_ID=<decimal artifact id>
ARM64_ARTIFACT_ID=<decimal artifact id; it may equal X64_ARTIFACT_ID>
X64_ZIP_SHA256=<parent-supplied SHA-256 of the inner spectre-linuxX64.zip>
ARM64_ZIP_SHA256=<parent-supplied SHA-256 of the inner spectre-linuxArm64.zip>
```

`UPSTREAM_RUN_HEAD_SHA` and `SOURCE_SHA` are intentionally distinct facts. The former is checked
against the Actions run and every selected artifact. The latter identifies the product tree, is
resolved independently through the repository commit API, and is compared with a producer
provenance receipt if the downloaded artifact includes `source-sha.txt`. It is never inferred from
the QA commit or the Actions head. The QA/tooling checkout identity is recorded automatically from
`GITHUB_SHA`; it is not a self-referential control input.

The run can be approved while queued or in progress. A completed run must have conclusion
`success`; its observed state is retained in the gate receipt. Artifact owner, run, head SHA,
expiry, source commit, IDs, and hashes remain hard gates.

One shared artifact may contain both uniquely named release ZIPs (and unrelated files), or each
architecture may use its own artifact. Each matrix cell counts only its own exact basename and
requires exactly one regular, non-symlink member under the download root. It verifies the supplied
inner SHA-256 before and after inspection. Before any archive CLI can start, every packaged ELF
runtime library is inventoried with path, SHA-256, and machine, and all must match the runner; the
launcher and `libjvm.so` are specifically required. No system JDK is substituted. The existing
public native verifier then checks positive `--version`/`--help` behavior.

Finally, the shipped CLI is invoked publicly as `spectre a11y tree --pid <actual GTK PID>` in a
private D-Bus/Xvfb session. A visible GTK child is checked by its real title and canonical button
label, unique keys, and valid parent/child link while a second process exposes countermarkers that
must not leak into the PID-scoped snapshot. The v2 contract is `source: "os-accessibility"`; it has
no invented top-level target/PID or window `platformId` requirement. Only small receipts are
uploaded; archives and extraction trees stay in runner temporary storage.
