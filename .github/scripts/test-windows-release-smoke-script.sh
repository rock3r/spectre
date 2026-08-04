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
  # Fallback without python: reject any high bit via LC_ALL=C grep.
  if LC_ALL=C grep -n '[^[:print:][:space:]]' "$script" >/dev/null 2>&1; then
    fail "non-ASCII or non-printable bytes in $script (install python3 for details)"
  fi
  # Also reject bytes > 127 that might still be "printable" in some locales: od scan.
  if od -An -t u1 "$script" | tr -s ' ' '\n' | grep -E '^(1[2-9][0-9]|2[0-9]{2})$' >/dev/null; then
    fail "byte > 127 found in $script"
  fi
  echo "OK: windows-release-smoke.ps1 ASCII check (grep/od fallback)"
fi

# --- Documented one-liners must exist in script help and RELEASE-SMOKE.md ---
# Exact operator commands (not loose fragments) so the -File target cannot drift silently.
for needle in \
  'pwsh -NoProfile -File .\scripts\windows-release-smoke.ps1' \
  'powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1'
do
  grep -F -q "$needle" "$script" || fail "script missing documented one-liner: $needle"
  grep -F -q "$needle" "$docs" || fail "docs/RELEASE-SMOKE.md missing documented one-liner: $needle"
done

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
