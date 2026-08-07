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

# 2) PATH without ruby that still runs real toolchains (not broken shims).
# Linking `command -v python3` can point at a pyenv/asdf shim that needs the rest of
# PATH; resolve the real interpreter via the current python3 instead.
strip_bin="$(mktemp -d)"
trap 'rm -rf "$strip_bin"' EXIT

link_abs() {
  local name="$1"
  local target="$2"
  [[ -n "$target" && -e "$target" ]] || return 1
  ln -sf "$target" "$strip_bin/$name"
}

# Core shell utilities: prefer absolute paths from the live PATH, resolve symlinks
# when possible so we get the real binary (Homebrew cellar, not a thin wrapper).
resolve_bin() {
  local cmd="$1"
  local p
  p="$(command -v "$cmd" 2>/dev/null)" || return 1
  # Prefer a real filesystem path over shell builtins (pwd, true, …).
  if [[ "$p" != /* ]]; then
    return 1
  fi
  if command -v realpath >/dev/null 2>&1; then
    realpath "$p" 2>/dev/null || echo "$p"
  elif command -v readlink >/dev/null 2>&1 && readlink -f "$p" >/dev/null 2>&1; then
    readlink -f "$p"
  else
    echo "$p"
  fi
}

for cmd in bash sh grep mktemp rm dirname tr cat sed head uname env chmod awk ln mkdir; do
  target="$(resolve_bin "$cmd" 2>/dev/null)" || continue
  link_abs "$cmd" "$target" || true
done

# python3: use the running interpreter's sys.executable (real binary, not pyenv shim).
if command -v python3 >/dev/null 2>&1; then
  real_py="$(python3 -c 'import sys; print(sys.executable)' 2>/dev/null || true)"
  if [[ -n "${real_py:-}" && -x "$real_py" ]]; then
    link_abs python3 "$real_py"
  else
    target="$(resolve_bin python3)" && link_abs python3 "$target"
  fi
fi

# printf / true / false may be builtins only — provide tiny sh wrappers if missing.
for builtin_cmd in printf true false pwd; do
  if [[ ! -e "$strip_bin/$builtin_cmd" ]]; then
    case "$builtin_cmd" in
      printf) printf '%s\n' '#!/bin/sh' 'builtin printf "$@"' >"$strip_bin/printf" ;;
      true) printf '%s\n' '#!/bin/sh' 'exit 0' >"$strip_bin/true" ;;
      false) printf '%s\n' '#!/bin/sh' 'exit 1' >"$strip_bin/false" ;;
      pwd) printf '%s\n' '#!/bin/sh' 'echo "$PWD"' >"$strip_bin/pwd" ;;
    esac
    chmod +x "$strip_bin/$builtin_cmd"
  fi
done

# Intentionally do not provide ruby.
[[ ! -e "$strip_bin/ruby" ]] || fail "strip PATH must not include ruby"

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
