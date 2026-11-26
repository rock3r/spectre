#!/usr/bin/env bash
set -uo pipefail
: "${QA_ROOT:?}" "${ARTIFACTS:?}" "${BASELINE_HELPER:?}" "${CANDIDATE_HELPER:?}"
mkdir -p "$ARTIFACTS"; fail=0
trap 'kill ${provider:-} 2>/dev/null || true' EXIT
for mode in control optional-absent missing-role missing-children; do
  ready="$ARTIFACTS/$mode-ready.json"; journal="$ARTIFACTS/$mode-provider.jsonl"; rm -f "$ready" "$journal"
  NO_AT_BRIDGE=1 GTK_MODULES= /usr/bin/python3 "$QA_ROOT/optional-fields-provider.py" --mode "$mode" --ready "$ready" --journal "$journal" >"$ARTIFACTS/$mode-provider.stdout" 2>"$ARTIFACTS/$mode-provider.stderr" & provider=$!
  for _ in {1..200}; do [[ -s $ready ]] && break; kill -0 "$provider" 2>/dev/null || break; sleep .05; done
  if [[ ! -s $ready ]]; then fail=1; continue; fi
  if ! timeout --signal=KILL 60s /usr/bin/python3 "$QA_ROOT/optional-fields-verify.py" --baseline "$BASELINE_HELPER" --candidate "$CANDIDATE_HELPER" --ready "$ready" --journal "$journal" --output "$ARTIFACTS/$mode-actual.json" >"$ARTIFACTS/$mode-verify.stdout" 2>"$ARTIFACTS/$mode-verify.stderr"; then fail=1; fi
  kill "$provider" 2>/dev/null || true; wait "$provider" 2>/dev/null || true; unset provider
done
python3 - "$ARTIFACTS" <<'PY'
import json, sys
from pathlib import Path
root=Path(sys.argv[1]); cases=[json.loads(path.read_text()) for path in sorted(root.glob("*-actual.json"))]
(root/"actual-results.json").write_text(json.dumps({"schemaVersion":1,"passed":len(cases)==4 and all(case["passed"] for case in cases),"cases":cases},indent=2)+"\n")
PY
exit "$fail"
