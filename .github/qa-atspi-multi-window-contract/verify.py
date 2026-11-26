#!/usr/bin/python3
"""Drive the native stdin protocol and preserve observations before enforcing candidate rules."""
import argparse,json,subprocess,time
from pathlib import Path
import dbus

def invoke(helper,body,out):
    raw=json.dumps(body,separators=(",",":")); out.with_suffix(".request.json").write_text(raw+"\n"); start=time.monotonic_ns()
    p=subprocess.run([str(helper),"accessibility-snapshot"],input=raw,text=True,capture_output=True,timeout=15)
    elapsed=(time.monotonic_ns()-start)//1_000_000; out.write_text(p.stdout); out.with_suffix(".stderr").write_text(p.stderr)
    try: response=json.loads(p.stdout)
    except Exception: response={"unparseable":p.stdout,"returnCode":p.returncode}
    return {"request":body,"rawRequest":raw,"response":response,"stdout":p.stdout,"stderr":p.stderr,"returnCode":p.returncode,"elapsedMs":elapsed}

def snap(r):
    x=r["response"]; return x.get("snapshot") or x.get("error",{}).get("partialSnapshot")
def ids(r):
    s=snap(r); return [] if not s else [n.get("id") for n in s.get("nodes",[])]
def links_valid(r):
    s=snap(r)
    if not s: return False
    nodes=s.get("nodes",[]); keys={n.get("key") for n in nodes}
    roots={"alpha-root","beta-root"}
    return len(keys)==len(nodes) and all((n.get("parentKey") is None) == (n.get("id") in roots) and (n.get("parentKey") is None or n.get("parentKey") in keys) for n in nodes)

def main():
    p=argparse.ArgumentParser(); p.add_argument("--helper",type=Path,required=True); p.add_argument("--ready",type=Path,required=True); p.add_argument("--journal",type=Path,required=True); p.add_argument("--artifacts",type=Path,required=True); p.add_argument("--label",required=True); p.add_argument("--candidate",action="store_true"); a=p.parse_args(); ready=json.loads(a.ready.read_text()); a.artifacts.mkdir(parents=True,exist_ok=True)
    session=dbus.SessionBus(); address=str(session.get_object("org.a11y.Bus","/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus")); bus=dbus.bus.BusConnection(address); admin=dbus.Interface(bus.get_object("org.freedesktop.DBus","/org/freedesktop/DBus"),"org.freedesktop.DBus"); registry=dbus.Interface(bus.get_object("org.a11y.atspi.Registry","/org/a11y/atspi/accessible/root"),"org.a11y.atspi.Accessible")
    matches=[(str(o),str(path)) for o,path in registry.GetChildren() if int(admin.GetConnectionUnixProcessID(o))==ready["pid"]]
    cache={"address":address}
    try: dbus.Interface(bus.get_object(ready["owner"],"/org/a11y/atspi/cache",introspect=False),"org.a11y.atspi.Cache").GetItems(timeout=2); cache["unexpectedSuccess"]=True
    except dbus.DBusException as e: cache.update(errorName=e.get_dbus_name(),message=str(e))
    base={"maximumDepth":64,"maximumNodeCount":100,"timeoutMs":5000,"allowAccessibilityEnable":True}; reqs=[]
    def run(tag,target,**kw):
        before=a.journal.stat().st_size; b=dict(base,target=target); b.update(kw); r=invoke(a.helper,b,a.artifacts/f"{a.label}-{tag}.json"); time.sleep(.1)
        with a.journal.open() as f:
            f.seek(before); r["orderedProviderCalls"]=[json.loads(x) for x in f if x.strip()]
        r["tag"]=tag; reqs.append(r); return r
    fault=ready["fault"]
    if fault=="none":
        pid=run("pid-both",{"type":"process-id","value":ready["pid"]}); alpha=run("alpha-window",{"type":"window-id","value":ready["owner"]+"|"+ready["windows"]["alpha"]}); beta=run("beta-window",{"type":"window-id","value":ready["owner"]+"|"+ready["windows"]["beta"]}); cap=run("pid-shared-cap",{"type":"process-id","value":ready["pid"]},maximumNodeCount=5)
    elif fault=="error": alpha=run("early-error",{"type":"window-id","value":ready["owner"]+"|"+ready["windows"]["alpha"]})
    else: alpha=run("slow-timeout",{"type":"window-id","value":ready["owner"]+"|"+ready["windows"]["alpha"]},timeoutMs=650)
    calls=[json.loads(x) for x in a.journal.read_text().splitlines() if x.strip()]; helper_pids=sorted({r.get("senderPid") for r in calls if r.get("senderPid") and r.get("senderPid")!=ready["pid"]})
    checks={"registrySingleOwner":matches==[(ready["owner"],ready["application"])],"ownerPid":int(admin.GetConnectionUnixProcessID(ready["owner"]))==ready["pid"],"nativeCacheFailure":cache.get("errorName") in ("org.freedesktop.DBus.Error.UnknownMethod","org.freedesktop.DBus.Error.UnknownInterface","org.freedesktop.DBus.Error.UnknownObject")}
    if fault=="none":
        checks.update(pidHasBoth=ids(pid)==["alpha-root","alpha-group","alpha-interior","alpha-marker","beta-root","beta-group","beta-interior","beta-marker"],pidWindowKeys=[w.get("key") for w in (snap(pid) or {}).get("windows",[])]==["w0","w1"],pidParentsLinked=links_valid(pid),alphaOnly=ids(alpha)==["alpha-root","alpha-group","alpha-interior","alpha-marker"],betaOnly=ids(beta)==["beta-root","beta-group","beta-interior","beta-marker"],distinctWindows=(snap(alpha) or {}).get("windows")!=(snap(beta) or {}).get("windows"),sharedCapTyped=(not cap["response"].get("ok",True) and cap["response"].get("error",{}).get("code")=="limit-reached" and len(ids(cap))==5 and links_valid(cap)),lateUnread=not any(c["path"].startswith(ready["windows"]["beta"]+"/group") for c in cap["orderedProviderCalls"]))
    elif fault=="error":
        e=alpha["response"].get("error",{}); checks.update(ordinaryFailure=e.get("code")=="no-accessibility-tree",originalContext="fixture ordinary child read rejected before deadline" in e.get("message",""),noPartial="partialSnapshot" not in e or e.get("partialSnapshot") is None)
    else:
        e=alpha["response"].get("error",{}); checks.update(typedTimeout=e.get("code")=="timeout",meaningfulPrefix=len(ids(alpha))>=1)
    receipt={"label":a.label,"candidate":a.candidate,"provider":ready,"atspiAddress":address,"registryMatches":matches,"rawCache":cache,"helperSenderPids":helper_pids,"requests":reqs,"orderedProviderCalls":calls,"checks":checks,"passed":all(checks.values())}
    (a.artifacts/f"{a.label}-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n"); print(json.dumps(receipt))
    if a.candidate and not receipt["passed"]: raise SystemExit(1)
if __name__=="__main__":main()
