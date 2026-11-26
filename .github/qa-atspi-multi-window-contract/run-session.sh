#!/usr/bin/env bash
set -uo pipefail
: "${BASELINE_HELPER:?}" "${ARTIFACTS:?}" "${QA_ROOT:?}"
mkdir -p "$ARTIFACTS"
fail=0
run_case() {
  local label=$1 fault=$2 helper=$3 candidate=$4
  local ready="$ARTIFACTS/$label-ready.json" journal="$ARTIFACTS/$label-provider.jsonl"
  rm -f "$ready" "$journal"
  NO_AT_BRIDGE=1 GTK_MODULES= /usr/bin/python3 "$QA_ROOT/provider.py" --ready "$ready" --journal "$journal" --fault "$fault" >"$ARTIFACTS/$label-provider.stdout" 2>"$ARTIFACTS/$label-provider.stderr" & provider=$!
  for _ in {1..200}; do [[ -s $ready ]] && break; kill -0 "$provider" 2>/dev/null || break; sleep .05; done
  if [[ ! -s $ready ]]; then fail=1; return; fi
  owner=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["owner"])' "$ready")
  address=$(gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus --method org.a11y.Bus.GetAddress | sed "s/^('//;s/',)$//")
  dbus-monitor --address "$address" "destination='$owner'" "sender='$owner'" >"$ARTIFACTS/$label-wire.log" 2>&1 & monitor=$!
  python3 - "$ready" "$ARTIFACTS/$label-x11.json" <<'PY'
import json,subprocess,sys
r=json.load(open(sys.argv[1])); observations=[]
for xid in r["xids"]:
 p=subprocess.run(["xprop","-id",hex(xid),"_NET_WM_PID","WM_NAME"],text=True,capture_output=True)
 observations.append({"xid":xid,"stdout":p.stdout,"stderr":p.stderr,"returnCode":p.returncode})
json.dump({"fixturePid":r["pid"],"distinctXids":len(set(r["xids"]))==2,"windows":observations},open(sys.argv[2],"w"),indent=2)
PY
  sha256sum "$helper" >"$ARTIFACTS/$label-helper.sha256"
  args=(); [[ $candidate == true ]] && args+=(--candidate)
  if ! timeout --signal=KILL 90s /usr/bin/python3 "$QA_ROOT/verify.py" --helper "$helper" --ready "$ready" --journal "$journal" --artifacts "$ARTIFACTS" --label "$label" "${args[@]}" >"$ARTIFACTS/$label-verify.stdout" 2>"$ARTIFACTS/$label-verify.stderr"; then fail=1; fi
  kill "$monitor" "$provider" 2>/dev/null || true; wait "$monitor" 2>/dev/null || true; wait "$provider" 2>/dev/null || true
}
trap 'kill ${monitor:-} ${provider:-} 2>/dev/null || true' EXIT
# Every baseline scenario runs independently. Observed old behavior is evidence, never a reason to
# skip the remaining controls or suppress their native receipts.
for spec in regular:none early:error timeout:slow; do run_case "baseline-${spec%%:*}" "${spec#*:}" "$BASELINE_HELPER" false; done
if [[ -n ${CANDIDATE_HELPER:-} ]]; then
  for spec in regular:none early:error timeout:slow; do run_case "candidate-${spec%%:*}" "${spec#*:}" "$CANDIDATE_HELPER" true; done
fi
python3 - "$ARTIFACTS" <<'PY'
import json,sys,tarfile
from pathlib import Path
p=Path(sys.argv[1]); receipts=[]
for f in sorted(p.glob("*-receipt.json")): receipts.append(json.load(open(f)))
summary={"schemaVersion":1,"receipts":[{"label":r["label"],"passed":r["passed"],"checks":r["checks"]} for r in receipts]}
(p/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")
with tarfile.open(p/"native-receipts.tar.gz","w:gz") as t:
 for f in sorted(p.iterdir()):
  if f.name!="native-receipts.tar.gz": t.add(f,arcname=f.name)
PY
exit "$fail"
