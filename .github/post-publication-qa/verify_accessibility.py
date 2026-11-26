#!/usr/bin/env python3
import argparse,json,pathlib

p=argparse.ArgumentParser()
p.add_argument("--snapshot",required=True)
p.add_argument("--pid",type=int,required=True,help="positive PID independently observed when invoking the CLI")
p.add_argument("--title",required=True); p.add_argument("--button",required=True); p.add_argument("--forbid-button",required=True)
a=p.parse_args()
if a.pid <= 0: raise SystemExit("invoked target PID must be positive")
data=json.loads(pathlib.Path(a.snapshot).read_text())
if data.get("schemaVersion") != 2 or data.get("source") != "os-accessibility": raise SystemExit("wrong schemaVersion/source")
windows=data.get("windows"); nodes=data.get("nodes")
if not isinstance(windows,list) or not isinstance(nodes,list): raise SystemExit("windows/nodes must be arrays")
matches=[x for x in windows if x.get("title")==a.title]
if len(matches)!=1 or not isinstance(matches[0].get("key"),str) or not matches[0]["key"]: raise SystemExit("unique expected window title/key absent")
if not isinstance(matches[0].get("bounds"),dict): raise SystemExit("expected window bounds absent")
if any(x.get("name")==a.forbid_button for x in nodes): raise SystemExit("opposite process marker leaked into snapshot")
buttons=[x for x in nodes if x.get("role")=="button" and x.get("name")==a.button]
if len(buttons)!=1: raise SystemExit("expected unique canonical button role/name")
keys=[x.get("key") for x in nodes]
if any(not isinstance(x,str) or not x for x in keys) or len(set(keys))!=len(keys): raise SystemExit("node keys must be present strings and unique")
key_set=set(keys)
if any("parentKey" not in x for x in nodes): raise SystemExit("every node must carry parentKey")
if any(x["parentKey"] is not None and x["parentKey"] not in key_set for x in nodes): raise SystemExit("snapshot contains a dangling parentKey")
button=buttons[0]
if button["parentKey"] not in key_set: raise SystemExit("button has no valid parentKey")
if "id" not in button: raise SystemExit("actual node id field absent")
if button["id"] is not None and not isinstance(button["id"],str): raise SystemExit("node id must be null or a string")
print(json.dumps({"invokedPid":a.pid,"windowKey":matches[0]["key"],"buttonKey":button["key"],"buttonParentKey":button["parentKey"],"buttonId":button["id"]}))
