#!/usr/bin/env bash
# Regression guards for package-manifest host dependencies (#400).
# Ensures structural checks never require Ruby, and missing Ruby yields an
# actionable preflight on the install-semantics path (not a deep command-not-found).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
script_dir="$(cd "$(dirname "$0")" && pwd)"
structural="$script_dir/test-generate-cli-package-manifests.sh"
semantics="$script_dir/test-homebrew-formula-install-semantics.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "$structural" ]] || fail "missing $structural"
[[ -f "$semantics" ]] || fail "missing $semantics"
[[ -x "$structural" && -x "$semantics" ]] || chmod +x "$structural" "$semantics"

# 1) Structural script source must not invoke ruby (comments may mention it).
if awk '
  /^[[:space:]]*#/ { next }
  /(^|[^[:alnum:]_\/.-])ruby([[:space:]]|$)/ { print NR ": " $0; found=1 }
  END { exit found ? 0 : 1 }
' "$structural"; then
  fail "structural script invokes ruby; move Ruby work to install-semantics (#400)"
fi

# 2) Build a PATH without ruby (and without accidental ruby stubs).
strip_bin="$(mktemp -d)"
trap 'rm -rf "$strip_bin"' EXIT
# Resolve real tools from the current PATH, skipping any ruby binary.
export PATH="${PATH:-/usr/bin:/bin}"
for cmd in bash sh python3 grep mktemp rm dirname pwd printf tr cat sed head uname env true false chmod awk; do
  p="$(command -v "$cmd" 2>/dev/null)" || continue
  # Skip if this path is the ruby interpreter itself.
  base="$(basename "$p")"
  [[ "$base" == ruby || "$base" == ruby3* ]] && continue
  ln -sf "$p" "$strip_bin/$cmd"
done
# Intentionally do not link ruby into strip_bin.

# 3) Structural checks must pass without ruby.
if ! env PATH="$strip_bin" bash "$structural" >/dev/null; then
  fail "structural package-manifest checks must pass without ruby on PATH (#400)"
fi

# 4) Semantics path must fail closed with an actionable preflight (not bare command-not-found).
sem_out="$(mktemp)"
set +e
env PATH="$strip_bin" bash "$semantics" >"$sem_out" 2>&1
sem_ec=$?
set -e
[[ "$sem_ec" -ne 0 ]] || fail "install-semantics must fail when ruby is missing"
grep -q "ruby" "$sem_out" || fail "preflight must name missing tool 'ruby'"
grep -qiE "install|apt |brew |required" "$sem_out" || fail "preflight must hint how to install ruby"
# Must not look like an uncaught shell command-not-found mid-script.
if grep -qE 'line [0-9]+: ruby: command not found' "$sem_out"; then
  fail "missing ruby must use preflight, not deep 'ruby: command not found'"
fi
grep -q "verifyHomebrewFormulaInstallSemantics\|install-semantics\|Homebrew formula" "$sem_out" \
  || fail "preflight must name the task or semantics path"

echo "test-cli-package-manifest-host-deps: OK"
