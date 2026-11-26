#!/usr/bin/env python3
import argparse,json,pathlib
p=argparse.ArgumentParser(); p.add_argument("--snapshot",required=True); p.add_argument("--pid",type=int,required=True); a=p.parse_args()
data=json.loads(pathlib.Path(a.snapshot).read_text())
source=data.get("source")
source_kind=source if isinstance(source,str) else source.get("kind") if isinstance(source,dict) else None
if data.get("schemaVersion") != 2 or source_kind != "os-accessibility": raise SystemExit("wrong schemaVersion/source")
windows=data.get("windows",[]); nodes=data.get("nodes",[])
target=source.get("target") if isinstance(source,dict) else data.get("target")
target_pid=target.get("pid") if isinstance(target,dict) else target
if target_pid != a.pid: raise SystemExit(f"Linux helper target PID mismatch: {target_pid!r} != {a.pid}")
matches=[x for x in windows if x.get("title")=="Spectre QA Window"]
if len(matches)!=1 or "platformId" not in matches[0] or matches[0]["platformId"] is None: raise SystemExit("unique authentic fixture window title/platformId absent")
buttons=[x for x in nodes if x.get("role")=="button" and x.get("name")=="Spectre QA Button"]
if len(buttons)!=1: raise SystemExit("expected unique fixture role/button name")
if any("id" in x or "parentId" in x for x in nodes): raise SystemExit("schema v2 must use key/parentKey, not id/parentId")
keys={x.get("key") for x in nodes}
if None in keys or len(keys)!=len(nodes): raise SystemExit("node keys must be present and unique")
if any(x.get("parentKey") is not None and x.get("parentKey") not in keys for x in nodes): raise SystemExit("snapshot contains a dangling parentKey")
if buttons[0].get("parentKey") not in keys: raise SystemExit("button has no valid parentKey")
