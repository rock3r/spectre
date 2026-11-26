# Packaged native AT-SPI multi-window contract QA

This scratch-only hosted lane first builds the **actual CLI distribution** from immutable public
PR #718 commit `ce03a8cea810a173e4941908a3a2adf7a4da5814` (tree
`504a2b1e1481afe4e409105ba2d8546f319fc851`). Its Linux accessibility source is blob
`9a8ecd3bdfbccbca394d818011515a23563ad744`, identical to public PR #716 commit `3e831add`.
It runs the packaged launcher, its normal daemon, MCP over normal stdio, and the native resource
extracted from that same package. It never changes production sources.

A GTK3 fixture owns two real mapped X11 windows and registers one cacheless application on the
actual private AT-SPI bus. Each regular, early-error, and real-timeout probe is independent. Raw
native requests/responses, CLI stdout/stderr, complete MCP JSON-RPC, provider journal, D-Bus wire
log, X properties, package/version/resource identities and receipts are uploaded even when another
probe fails. The immutable baseline is observational; only candidate receipts gate acceptance.

The native early structural diagnostic is deliberately split into two facts: the candidate-facing
context must be exactly `failed to read an AT-SPI child: AT-SPI GetChildren failed`, while the raw
provider journal separately retains `fixture ordinary child read rejected before deadline`. The
provider payload is not incorrectly required verbatim in the native public diagnostic.

## Candidate pinning

Leave both selectors in `candidate.env` empty for baseline-first evidence. A review may then set
exactly one of:

* `CANDIDATE_SHA` to an exact public descendant of the baseline; or
* `CANDIDATE_PATCH` to one cumulative patch beside the environment file.

The diff is rejected unless its only path is
`recording/native/linux/src/accessibility.rs`. `CANDIDATE_ACCESSIBILITY_BLOB` is mandatory and pins
the resulting blob. `CANDIDATE_NATIVE_TREE` and `CANDIDATE_FULL_TREE` may additionally pin the
containing trees. Candidate acceptance requires PID traversal of both windows, explicit A/B native
window-ID isolation, linked/global-limit partials with later descendants unread, a meaningful linked
timeout partial, and the contextual no-partial early failure through native, packaged CLI and MCP
without a helper-protocol error.
