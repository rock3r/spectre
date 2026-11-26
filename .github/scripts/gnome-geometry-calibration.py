#!/usr/bin/env python3
"""GNOME 46-only, owned-window geometry calibration; never feeds product behavior."""
import json, os, re, subprocess, time
from pathlib import Path
import gi
gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib
from PIL import Image

A = Path(os.environ["SMOKE_ARTIFACTS"]); HERE = Path(__file__).parent
BUS = "dev.sebastiano.Spectre.MutterControlVerification"; PATH = "/dev/sebastiano/Spectre/MutterControlVerification"
SC = "org.gnome.Mutter.ScreenCast"; SCP = "/org/gnome/Mutter/ScreenCast"
C = Gio.bus_get_sync(Gio.BusType.SESSION, None)

def call(dest, path, interface, method, params, reply):
    return C.call_sync(dest, path, interface, method, params, GLib.VariantType.new(reply), Gio.DBusCallFlags.NONE, 15000, None).unpack()

def record(window_id):
    session, = call(SC,SCP,SC,"CreateSession",GLib.Variant("(a{sv})",({},)),"(o)")
    stream, = call(SC,session,SC+".Session","RecordWindow",GLib.Variant("(a{sv})",({"window-id":GLib.Variant("t",window_id)},)),"(o)")
    answer=[]; loop=GLib.MainLoop()
    sub=C.signal_subscribe(SC,SC+".Stream","PipeWireStreamAdded",stream,None,Gio.DBusSignalFlags.NONE,lambda *_args:(answer.extend(_args[-1].unpack()),loop.quit()))
    call(SC,session,SC+".Session","Start",None,"()")
    timer=GLib.timeout_add_seconds(15,lambda:(loop.quit(),GLib.SOURCE_REMOVE)[1]); loop.run(); GLib.source_remove(timer); C.signal_unsubscribe(sub)
    if not answer: raise RuntimeError("PipeWireStreamAdded absent")
    return session, stream, answer[0]

def landmarks(path):
    im=Image.open(path).convert("RGB"); pix=im.load(); green=[]; magenta=[]
    for y in range(im.height):
      for x in range(im.width):
        r,g,b=pix[x,y]
        if g>190 and r<50 and b<140: green.append((x,y))
        if r>190 and b>160 and g<80: magenta.append((x,y))
    def bounds(v): return None if not v else {"left":min(x for x,y in v),"top":min(y for x,y in v),"right":max(x for x,y in v),"bottom":max(y for x,y in v),"count":len(v)}
    return {"imagePixels":{"width":im.width,"height":im.height},"contentStripe":bounds(green),"centerMarker":bounds(magenta)}

def x11_geometry(xinfo, xprop):
    def number(label):
        match=re.search(rf"^\s*{re.escape(label)}:\s*(-?\d+)",xinfo,re.MULTILINE)
        return None if match is None else int(match.group(1))
    ext=re.search(r"_NET_FRAME_EXTENTS\(CARDINAL\) = (\d+), (\d+), (\d+), (\d+)",xprop)
    return {"units":"X11 root pixels","absoluteUpperLeftX":number("Absolute upper-left X"),"absoluteUpperLeftY":number("Absolute upper-left Y"),"relativeUpperLeftX":number("Relative upper-left X"),"relativeUpperLeftY":number("Relative upper-left Y"),"width":number("Width"),"height":number("Height"),"frameExtentsLeftTopRightBottom":None if ext is None else [int(v) for v in ext.groups()]}

def main():
    subprocess.run(["javac","-d",str(A),str(HERE/"GnomeGeometryFixture.java")],check=True)
    subprocess.run(["cc","-Wall","-Wextra","-Werror","-o",str(A/"geometry-pw"),str(HERE/"gnome-geometry-pw.c"),*subprocess.check_output(["pkg-config","--cflags","--libs","libpipewire-0.3"],text=True).split()],check=True)
    summary={"schemaVersion":1,"purpose":"diagnostic-only; no product calibration values","environment":{"sessionType":os.environ.get("XDG_SESSION_TYPE"),"display":os.environ.get("DISPLAY"),"waylandDisplay":os.environ.get("WAYLAND_DISPLAY")},"windows":[]}
    (A/"display-config.txt").write_text(subprocess.check_output(["gdbus","call","--session","--dest","org.gnome.Mutter.DisplayConfig","--object-path","/org/gnome/Mutter/DisplayConfig","--method","org.gnome.Mutter.DisplayConfig.GetCurrentState"],text=True))
    (A/"xrandr.txt").write_text(subprocess.check_output(["xrandr","--current","--verbose"],text=True))
    stage,=call(BUS,PATH,BUS,"GetStageDiagnostics",None,"(s)"); summary["stage"]=json.loads(stage)
    (A/"geometry-calibration.partial.json").write_text(json.dumps(summary,indent=2)+"\n")
    for index,kind in enumerate(("decorated","undecorated")):
      proc=subprocess.Popen(["java","-cp",str(A),"GnomeGeometryFixture",kind,str(200+index*40),str(237+index*30)],stdout=subprocess.PIPE,text=True)
      try:
        awt=json.loads(proc.stdout.readline()); title=awt["title"]
        xid_text=subprocess.check_output(["xdotool","search","--sync","--onlyvisible","--pid",str(proc.pid),"--name",f"^{re.escape(title)}$"],text=True,timeout=15).splitlines()[0]
        xid=int(xid_text); xinfo=subprocess.check_output(["xwininfo","-id",str(xid),"-frame","-stats","-tree"],text=True)
        xprop=subprocess.check_output(["xprop","-id",str(xid),"_NET_FRAME_EXTENTS","_NET_WM_PID","WM_CLASS"],text=True)
        (A/f"{kind}-xwininfo.txt").write_text(xinfo); (A/f"{kind}-xprop.txt").write_text(xprop)
        wid,geometry=call(BUS,PATH,BUS,"InspectDescendantX11Window",GLib.Variant("(tu)",(xid,proc.pid)),"(ts)")
        session,stream,node=record(wid); raw=A/f"{kind}.raw"
        pw=json.loads(subprocess.check_output([str(A/"geometry-pw"),str(node),str(raw)],text=True,timeout=20))
        png=A/f"{kind}.png"
        subprocess.run(["gst-launch-1.0","-q","pipewiresrc",f"path={node}","num-buffers=1","!","videoconvert","!","pngenc","!",f"filesink location={png}"],check=True,timeout=20)
        marks=landmarks(png)
        stripe=marks["contentStripe"]; content=awt["contentLocationOnScreen"]
        capture=marks["imagePixels"]; top=awt["topLevelBounds"]; content_bounds=awt["contentBounds"]
        mapping={"units":"signed pixel differences; capture pixels versus AWT user-space pixels","captureMinusTopLevelWidth":capture["width"]-top["width"],"captureMinusTopLevelHeight":capture["height"]-top["height"],"captureMinusContentWidth":capture["width"]-content_bounds["width"],"captureMinusContentHeight":capture["height"]-content_bounds["height"],"contentStripeTopInCapture":None if stripe is None else stripe["top"],"awtContentScreenY":content["y"],"note":"all values are observations; no decoration delta is inferred"}
        summary["windows"].append({"kind":kind,"pid":proc.pid,"xidNumeric":xid,"mutterWindowIdUint64":wid,"awt":awt,"x11":{**x11_geometry(xinfo,xprop),"xwininfoArtifact":f"{kind}-xwininfo.txt","xpropArtifact":f"{kind}-xprop.txt"},"mutter":json.loads(geometry),"pipeWire":{"nodeIdNumeric":node,**pw},"landmarks":marks,"measuredMapping":mapping,"png":png.name,"raw":raw.name})
        (A/"geometry-calibration.partial.json").write_text(json.dumps(summary,indent=2)+"\n")
        call(SC,session,SC+".Session","Stop",None,"()")
      finally:
        proc.terminate(); proc.wait(timeout=5)
    (A/"geometry-calibration.json").write_text(json.dumps(summary,indent=2)+"\n")
    assert len(summary["windows"])==2 and all(w["landmarks"]["contentStripe"] and w["landmarks"]["centerMarker"] for w in summary["windows"])
    print(json.dumps(summary,indent=2))
if __name__=="__main__": main()
