#!/usr/bin/env bash
# Contract tests for scripts/windows-release-smoke.ps1 launch reliability (#385).
# Proves: ASCII-only source (WinPS 5.1 parse without BOM), documented one-liners present.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/windows-release-smoke.ps1"
docs="$repo_root/docs/RELEASE-SMOKE.md"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -f "$script" ]] || fail "missing $script"
[[ -f "$docs" ]] || fail "missing $docs"

# --- ASCII-only (no multi-byte UTF-8; no UTF-8 BOM required) ---
if command -v python3 >/dev/null 2>&1; then
  python3 - "$script" <<'PY' || fail "ASCII check failed"
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = path.read_bytes()
if data.startswith(b"\xef\xbb\xbf"):
    # BOM is an allowed alternative for WinPS 5.1, but this tree standardizes on ASCII-only.
    # Accept BOM only if the rest is valid; still flag non-ASCII payload after BOM.
    payload = data[3:]
else:
    payload = data
non_ascii = [i for i, b in enumerate(payload) if b > 127]
if non_ascii:
    # Report first few offsets for operators.
    sample = ", ".join(str(i) for i in non_ascii[:8])
    print(
        f"scripts/windows-release-smoke.ps1 has non-ASCII bytes at offsets: {sample}",
        file=sys.stderr,
    )
    sys.exit(1)
print("OK: windows-release-smoke.ps1 is ASCII-only (WinPS 5.1-safe without BOM)")
PY
else
  # Fallback without python3: unsigned-byte scan (128-255 only; do not match ASCII 'x'=120).
  if od -An -t u1 "$script" | tr -s '[:space:]' '\n' | grep -E '^(1[3-9][0-9]|12[89]|2[0-4][0-9]|25[0-5])$' >/dev/null; then
    fail "byte > 127 found in $script (install python3 for offset details)"
  fi
  echo "OK: windows-release-smoke.ps1 ASCII check (od fallback)"
fi

# --- Documented one-liners must exist in script help and RELEASE-SMOKE.md ---
# Exact operator commands (not loose fragments) so the -File target cannot drift silently.
for needle in \
  'pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1' \
  'powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1'
do
  grep -F -q "$needle" "$script" || fail "script missing documented one-liner: $needle"
  grep -F -q "$needle" "$docs" || fail "docs/RELEASE-SMOKE.md missing documented one-liner: $needle"
done

# --- Timeout / cleanup contract ---
# Every external command must be bounded and a timed-out wrapper must clean its child tree.
grep -F -q '[int] $TimeoutSeconds' "$script" || fail "script missing configurable timeout parameters"
grep -F -q '[System.Diagnostics.ProcessStartInfo]::new()' "$script" || fail "native process does not use a reliable process handle"
grep -F -q '.WaitForExit($TimeoutSeconds * 1000)' "$script" || fail "native process wait is not bounded"
grep -F -q 'taskkill.exe /PID $p.Id /T /F' "$script" || fail "timeout does not clean the Windows process tree"
grep -F -q 'timed out after {1}s' "$script" || fail "timeout diagnostic is missing"
grep -F -q '"--stop"' "$script" || fail "packaged Gradle-ish launch does not reset attach-tainted daemons"

# --- Shared schemaVersion report + stable scenario IDs (#398) ---
grep -F -q 'schemaVersion' "$script" || fail "Windows report missing schemaVersion"
grep -F -q 'Save-VersionedSmokeReport' "$script" || fail "Windows report writer missing"
grep -F -q 'hard skip without N/A reason' "$script" || fail "fail-closed hard N/A policy missing"
for scenario_id in \
  preflight check junit-live agent-attach-core agent-contract-corpus agent-inject \
  agent-launch-and-attach cli-packaged cli-native-helper-layout cli-user-flow \
  mcp-sdk-flow host-native-recording maven-local-consumer
do
  grep -F -q "$scenario_id" "$script" || fail "Windows runner missing stable scenario id: $scenario_id"
done
grep -F -q 'windows-ssh' "$script" || fail "SSH displayMode honesty for WGC missing"
grep -F -q 'WGC requires native interactive console' "$script" || fail "WGC interactive-console N/A reason missing"
grep -F -q 'displayMode' "$script" || fail "displayMode field missing from Windows harness"
grep -F -q 'windows-release-smoke.json' "$script" || fail "Windows JSON report path missing"
# Fail-closed matrix completeness + maven consumer + preflight-only (#398 harden)
grep -F -q 'RequiredScenarioIds' "$script" || fail "Windows runner missing RequiredScenarioIds constant"
grep -F -q 'missing required scenario id' "$script" || fail "Windows runner missing fail-closed required-ID validation"
grep -F -q 'spectre-core-' "$script" || fail "Windows maven-local-consumer missing fresh jar resolve"
grep -F -q 'PreflightOnly' "$script" || fail "Windows runner missing -PreflightOnly switch"
grep -F -q 'preflight-only mode' "$script" || fail "Windows preflight-only N/A reason missing"

# --- Optional: parse with pwsh when present (macOS/Linux CI agents may have it) ---
# Note: this is PowerShell Core parse, not Desktop 5.1; ASCII byte check is the 5.1 stand-in.
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -Command "
    \$path = '$script'
    \$text = Get-Content -LiteralPath \$path -Raw
    \$tokens = \$null; \$errors = \$null
    [void][System.Management.Automation.Language.Parser]::ParseInput(\$text, [ref]\$tokens, [ref]\$errors)
    if (\$errors -and \$errors.Count -gt 0) {
      \$errors | ForEach-Object { Write-Host \$_ }
      exit 1
    }
    Write-Host 'OK: pwsh Parser::ParseInput accepted windows-release-smoke.ps1'
  " || fail "pwsh parse check failed"
else
  echo "SKIP: pwsh not installed; ASCII + docs checks only"
fi

echo "OK: windows-release-smoke.ps1 contract checks passed"
