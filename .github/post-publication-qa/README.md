# Scratch hosted post-publication QA

This directory is an isolated, verification-only probe of artifacts already public on GitHub and
Maven Central. It neither checks out nor builds the release source, and the workflow has only
read-only repository permission. The checked-in control intentionally remains at `0.9.0`; updating
it to the public `0.10.0` version and its **peeled** 40-character tag SHA is the acceptance hold.

Automatic execution is deliberately narrow: a push to the scratch `work` branch must change only
the control path selected by the workflow and its head commit must start with
`Probe published Spectre`. `workflow_dispatch` is intentionally absent.

`verify_release.py` rejects drafts, non-exact versions, tag/SHA disagreement, absent API digests,
and download/digest disagreement. `verify_native_archive.py` runs the published CLI and inspects
the release jar's genuine packaged ELF helper. The 0.9 control verifies its real recording helper
but does not claim the 0.10 accessibility snapshot surface. For 0.10 the helper's public
`accessibility-snapshot` response is captured as visible version-specific evidence and checked
against `accessibility-snapshot-0.10.0.json`.

`verify_consumer.sh` creates a new project outside the checkout, uses a new empty Maven local
repository, pins Central to `https://repo.maven.apache.org/maven2`, resolves the eleven declared
0.10 modules (the documented published subset for 0.9), checks POM/JAR/Gradle-module identity, and
runs a Java consumer against the public recording API. Repository build files are reference only.

Evidence is split into per-architecture native archives and an external-consumer archive. It
contains commands, outputs, SHA-256 values, and module identities, never tokens or environment
dumps.
