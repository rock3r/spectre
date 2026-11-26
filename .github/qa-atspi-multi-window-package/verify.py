#!/usr/bin/python3
"""Exercise the shipped native reader, CLI/daemon and MCP stdio without a JVM attach."""
import argparse,json,os,subprocess,time
from pathlib import Path
import dbus
CONTEXT="failed to read an AT-SPI child: AT-SPI GetChildren failed"
PROVIDER="fixture ordinary child read rejected before deadline"
def execute(argv,stdin="",timeout=20,env=None):
 start=time.monotonic_ns(); p=subprocess.run(argv,input=stdin,text=True,capture_output=True,timeout=timeout,env=env)
 return {"argv":argv,"stdin":stdin,"stdout":p.stdout,"stderr":p.stderr,"returnCode":p.returncode,"elapsedMs":(time.monotonic_ns()-start)//1_000_000}
def native(helper,body):
 wire=json.dumps(body,separators=(",",":")); r=execute([str(helper),"accessibility-snapshot"],wire)
 try:r["response"]=json.loads(r["stdout"])
 except Exception:r["response"]={"unparseable":r["stdout"]}
 r["request"]=body; return r
def snapshot(r):
 x=r.get("response",{}); return x.get("snapshot") or x.get("error",{}).get("partialSnapshot")
def node_ids(r): return [n.get("id") for n in (snapshot(r) or {}).get("nodes",[])]
def linked(r):
 nodes=(snapshot(r) or {}).get("nodes",[]); keys={n.get("key") for n in nodes}
 return bool(nodes) and len(keys)==len(nodes) and all(n.get("parentKey") is None or n["parentKey"] in keys for n in nodes)
def mcp(cli,title,env):
 messages=[{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"qa-atspi","version":"1"}}},{"jsonrpc":"2.0","method":"notifications/initialized","params":{}},{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"accessibility_tree","arguments":{"window":title,"maximum_depth":64,"maximum_node_count":100,"timeout_ms":5000}}}]
 return execute([str(cli),"mcp"],"".join(json.dumps(x,separators=(",",":"))+"\n" for x in messages),30,env)
def main():
 ap=argparse.ArgumentParser(); ap.add_argument("--helper",type=Path,required=True); ap.add_argument("--cli",type=Path,required=True); ap.add_argument("--ready",type=Path,required=True); ap.add_argument("--journal",type=Path,required=True); ap.add_argument("--artifacts",type=Path,required=True); ap.add_argument("--label",required=True); ap.add_argument("--candidate",action="store_true"); a=ap.parse_args(); ready=json.loads(a.ready.read_text()); base={"maximumDepth":64,"maximumNodeCount":100,"timeoutMs":5000,"allowAccessibilityEnable":True}
 def call(tag,target,**changes):
  request=dict(base,target=target); request.update(changes); before=a.journal.stat().st_size; result=native(a.helper,request); time.sleep(.08)
  with a.journal.open() as f:f.seek(before); result["providerCalls"]=[json.loads(x) for x in f if x.strip()]
  (a.artifacts/f"{a.label}-{tag}-native.json").write_text(json.dumps(result,indent=2)+"\n"); return result
 window=lambda name:{"type":"window-id","value":ready["owner"]+"|"+ready["windows"][name]}; fault=ready["fault"]; requests={}
 if fault=="none":
  requests["pid"]=call("pid",{"type":"process-id","value":ready["pid"]}); requests["alpha"]=call("alpha",window("alpha")); requests["beta"]=call("beta",window("beta")); requests["cap"]=call("cap",{"type":"process-id","value":ready["pid"]},maximumNodeCount=5)
 elif fault=="error":requests["error"]=call("error",window("alpha"))
 else:requests["timeout"]=call("timeout",window("alpha"),timeoutMs=650)
 env=os.environ.copy(); env.update(HOME=str(a.artifacts/f"{a.label}-home"),XDG_RUNTIME_DIR=str(a.artifacts/f"{a.label}-runtime")); Path(env["HOME"]).mkdir(exist_ok=True); Path(env["XDG_RUNTIME_DIR"]).mkdir(mode=0o700,exist_ok=True)
 cli_args=[str(a.cli),"a11y","tree","--window","Alpha","--maximum-depth","64","--maximum-node-count","100","--timeout-ms","5000"]; cli=execute(cli_args,timeout=30,env=env); mcp_result=mcp(a.cli,"Alpha",env); version=execute([str(a.cli),"version","--json"],env=env)
 (a.artifacts/f"{a.label}-cli.json").write_text(json.dumps(cli,indent=2)+"\n"); (a.artifacts/f"{a.label}-mcp.json").write_text(json.dumps(mcp_result,indent=2)+"\n")
 session=dbus.SessionBus(); addr=str(session.get_object("org.a11y.Bus","/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus")); bus=dbus.bus.BusConnection(addr); admin=dbus.Interface(bus.get_object("org.freedesktop.DBus","/org/freedesktop/DBus"),"org.freedesktop.DBus"); all_calls=[json.loads(x) for x in a.journal.read_text().splitlines() if x.strip()]; helper_pids=sorted({x["senderPid"] for x in all_calls if x.get("senderPid") not in (None,ready["pid"])})
 xprops=[execute(["xprop","-id",hex(x),"_NET_WM_PID","WM_NAME"]) for x in ready["xids"]]
 checks={"realOwnedWindowIds":all(x["returnCode"]==0 and f"= {ready['pid']}" in x["stdout"] for x in xprops),"distinctXids":len({x["xid"] for x in ready["x11"]})==2,"providerOwnerPid":int(admin.GetConnectionUnixProcessID(ready["owner"]))==ready["pid"],"helperPidObserved":bool(helper_pids),"packagedVersion":version["returnCode"]==0}
 if fault=="none":
  pid,alpha,beta,cap=(requests[x] for x in ("pid","alpha","beta","cap")); expected=["alpha-root","alpha-group","alpha-interior","alpha-marker","beta-root","beta-group","beta-interior","beta-marker"]
  checks.update(pidBothMarkers=node_ids(pid)==expected,pidTwoWindows=[w.get("title") for w in (snapshot(pid) or {}).get("windows",[])]==["Alpha","Beta"],alphaIsolated=node_ids(alpha)==expected[:4],betaIsolated=node_ids(beta)==expected[4:],betaRootFirst=node_ids(beta)[:1]==["beta-root"],allParentsLinked=all(linked(x) for x in (pid,alpha,beta,cap)),globalCap=len(node_ids(cap))==5 and cap["response"].get("error",{}).get("code")=="limit-reached",laterDescendantsUntouched=not any(x["path"].startswith(ready["windows"]["beta"]+"/group") for x in cap["providerCalls"]),cliMachineJson=cli["returnCode"]==0 and cli["stdout"].lstrip().startswith("{"),mcpComplete='"id":2' in mcp_result["stdout"] and '"isError":false' in mcp_result["stdout"])
 elif fault=="error":
  error=requests["error"]["response"].get("error",{}); combined=cli["stdout"]+cli["stderr"]+mcp_result["stdout"]+mcp_result["stderr"]
  checks.update(nativeTyped=error.get("code")=="no-accessibility-tree",originalContext=error.get("message")==CONTEXT,providerDetailSeparate=any(x.get("detail")==PROVIDER for x in requests["error"]["providerCalls"]),noForbiddenPartial=not error.get("partialSnapshot"),notHelperProtocolFailure="Accessibility helper failed" not in combined,cliContext=CONTEXT in combined,mcpContext=CONTEXT in mcp_result["stdout"] and '"isError":true' in mcp_result["stdout"])
 else:
  error=requests["timeout"]["response"].get("error",{}); checks.update(realTimeout=error.get("code")=="timeout" and requests["timeout"]["elapsedMs"]>=500,meaningfulLinkedPartial=len(node_ids(requests["timeout"]))>=3 and linked(requests["timeout"]))
 receipt={"schemaVersion":2,"label":a.label,"candidate":a.candidate,"provider":ready,"xprop":xprops,"atspiAddress":addr,"helperSenderPids":helper_pids,"version":version,"requests":requests,"cli":cli,"mcp":mcp_result,"checks":checks,"passed":all(checks.values())}; (a.artifacts/f"{a.label}-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n"); print(json.dumps(receipt))
 if a.candidate and not receipt["passed"]:raise SystemExit(1)
if __name__=="__main__":main()
