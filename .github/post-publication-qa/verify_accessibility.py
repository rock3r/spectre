#!/usr/bin/env python3
import argparse,json,pathlib
p=argparse.ArgumentParser(); p.add_argument("--snapshot",required=True); p.add_argument("--pid",type=int,required=True); a=p.parse_args()
data=json.loads(pathlib.Path(a.snapshot).read_text())
if data.get("schemaVersion") != 2 or data.get("source") != "os-accessibility": raise SystemExit("wrong schemaVersion/source")
windows=data.get("windows",[]); nodes=data.get("nodes",[])
window=next((x for x in windows if x.get("title")=="Spectre QA Window" and x.get("pid",a.pid)==a.pid),None)
button=next((x for x in nodes if x.get("name")=="Spectre QA Button" or x.get("title")=="Spectre QA Button"),None)
if not window or not button: raise SystemExit("fixture window/button marker absent")
ids={x.get("id") for x in nodes}
parent=button.get("parentId")
if parent is None or parent not in ids: raise SystemExit("button has no valid parent link")
