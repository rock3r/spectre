#!/usr/bin/env python3
"""GNOME 46-only, owned-window geometry calibration; never feeds product behavior."""
import json, os, re, subprocess, time, traceback
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
    def received(_connection, _sender, _path, _interface, _signal, parameters):
        answer.extend(parameters.unpack())
        loop.quit()
    sub=C.signal_subscribe(SC,SC+".Stream","PipeWireStreamAdded",stream,None,Gio.DBusSignalFlags.NONE,received)
    call(SC,session,SC+".Session","Start",None,"()")
    timer=GLib.timeout_add_seconds(15,lambda:(loop.quit(),GLib.SOURCE_REMOVE)[1]); loop.run()
    if answer: GLib.source_remove(timer)
    C.signal_unsubscribe(sub)
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
    return {"units":"X11 root pixels","absoluteUpperLeftX":number("Absolute upper-left X"),"absoluteUpperLeftY":number("Absolute upper-left Y"),"relativeUpperLeftX":number("Relative upper-left X"),"relativeUpperLeftY":number("Relative upper-left Y"),"width":number("Width"),"height":number("Height"),"frameExtentsLeftRightTopBottom":None if ext is None else [int(v) for v in ext.groups()]}

def select_x11_client(pid, title, kind):
    """Select the unique real client, never xdotool's first matching WM frame."""
    found=subprocess.check_output(["xdotool","search","--sync","--all","--onlyvisible",
      "--pid",str(pid),"--name",f"^{re.escape(title)}$"],text=True,timeout=15).splitlines()
    selections=[]
    for index,value in enumerate(found):
      xid=int(value)
      prefix=f"{kind}-candidate-{index}-{xid}"
      def snapshot(command,suffix):
        result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        name=f"{prefix}-{suffix}.txt"; (A/name).write_text(result.stdout)
        return result,name
      client,client_name=snapshot(["xwininfo","-id",str(xid),"-stats","-tree"],"client-xwininfo")
      frame,frame_name=snapshot(["xwininfo","-id",str(xid),"-frame","-stats","-tree"],"frame-xwininfo")
      props,prop_name=snapshot(["xprop","-id",str(xid),"_NET_FRAME_EXTENTS","_NET_WM_PID",
        "WM_CLASS","_NET_WM_WINDOW_TYPE"],"xprop")
      pid_match=re.search(r"_NET_WM_PID\(CARDINAL\) = (\d+)",props.stdout)
      actual_pid=None if pid_match is None else int(pid_match.group(1))
      normal="_NET_WM_WINDOW_TYPE_NORMAL" in props.stdout
      fixture_class="GnomeGeometryFixture" in props.stdout and "mutter-x11-frames" not in props.stdout
      accepted=(client.returncode==0 and frame.returncode==0 and props.returncode==0 and
        actual_pid==pid and normal and fixture_class)
      selections.append({"xidNumeric":xid,"accepted":accepted,"observedPid":actual_pid,
        "normalWindow":normal,"fixtureClass":fixture_class,"clientXwininfoArtifact":client_name,
        "frameXwininfoArtifact":frame_name,"xpropArtifact":prop_name,
        "clientXwininfo":client.stdout,"frameXwininfo":frame.stdout,"xprop":props.stdout})
    failures=[{key:value for key,value in candidate.items()
      if key not in ("clientXwininfo","frameXwininfo","xprop")}
      for candidate in selections if not candidate["accepted"]]
    (A/f"{kind}-selection-failures.json").write_text(json.dumps(failures,indent=2)+"\n")
    accepted=[candidate for candidate in selections if candidate["accepted"]]
    if len(accepted)!=1:
      raise RuntimeError(f"expected exactly one positive PID/class/normal X11 client; found {len(accepted)}")
    return accepted[0]

def main():
    subprocess.run(["javac","-d",str(A),str(HERE/"GnomeGeometryFixture.java")],check=True)
    compile_command=["cc","-Wall","-Wextra","-Werror","-o",str(A/"geometry-pw"),str(HERE/"gnome-geometry-pw.c"),*subprocess.check_output(["pkg-config","--cflags","--libs","libpipewire-0.3"],text=True).split()]
    (A/"geometry-pw-compile-command.txt").write_text(" ".join(compile_command)+"\n")
    subprocess.run(compile_command,check=True)
    summary={"schemaVersion":2,"purpose":"diagnostic-only; no product calibration values","environment":{"sessionType":os.environ.get("XDG_SESSION_TYPE"),"display":os.environ.get("DISPLAY"),"waylandDisplay":os.environ.get("WAYLAND_DISPLAY")},"windows":[]}
    partial=A/"geometry-calibration.partial.json"
    def checkpoint(): partial.write_text(json.dumps(summary,indent=2)+"\n")
    def artifact(command,name):
        result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        (A/name).write_text(result.stdout)
        if result.returncode: raise subprocess.CalledProcessError(result.returncode,command,result.stdout)
        return result.stdout
    checkpoint()
    artifact(["gdbus","call","--session","--dest","org.gnome.Mutter.DisplayConfig","--object-path","/org/gnome/Mutter/DisplayConfig","--method","org.gnome.Mutter.DisplayConfig.GetCurrentState"],"display-config.txt")
    artifact(["xrandr","--current","--verbose"],"xrandr.txt")
    stage,=call(BUS,PATH,BUS,"GetStageDiagnostics",None,"(s)"); summary["stage"]=json.loads(stage); checkpoint()
    live=[]
    try:
      # Persist both independent AWT/X11/Mutter readings before native video can fail.
      for kind in ("decorated","undecorated"):
        proc=subprocess.Popen(["java","-cp",str(A),"GnomeGeometryFixture",kind,"200","200"],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        live.append(proc)
        awt_line=proc.stdout.readline()
        (A/f"{kind}-awt.json").write_text(awt_line)
        awt=json.loads(awt_line)
        entry={"kind":kind,"pid":proc.pid,"awt":awt,"x11":None,"mutter":None,
          "pipeWire":{"attempted":False},"landmarks":None}
        summary["windows"].append(entry); checkpoint()
        try:
          selected=select_x11_client(proc.pid,awt["title"],kind); xid=selected["xidNumeric"]
          (A/f"{kind}-xid.txt").write_text(f"{xid}\n")
          entry["xidNumeric"]=xid
          entry["x11"]={**x11_geometry(selected["clientXwininfo"],selected["xprop"]),
            "clientXwininfoArtifact":selected["clientXwininfoArtifact"],
            "frameXwininfoArtifact":selected["frameXwininfoArtifact"],
            "xpropArtifact":selected["xpropArtifact"]}; checkpoint()
        except Exception as error:
          entry["x11SelectionError"]={"type":type(error).__name__,"message":str(error),
            "traceback":traceback.format_exc()}; checkpoint(); continue
        try:
          wid,geometry=call(BUS,PATH,BUS,"InspectDescendantX11Window",GLib.Variant("(tu)",(xid,proc.pid)),"(ts)")
          (A/f"{kind}-mutter.json").write_text(geometry+"\n")
          entry["mutterWindowIdUint64"]=wid; entry["mutter"]=json.loads(geometry); checkpoint()
        except Exception as error:
          entry["mutterLookupError"]={"type":type(error).__name__,"message":str(error),
            "traceback":traceback.format_exc()}; checkpoint()

      for entry in summary["windows"]:
        kind=entry["kind"]
        if "mutterWindowIdUint64" not in entry: continue
        try:
          session,stream,node=record(entry["mutterWindowIdUint64"])
          entry["pipeWire"].update({"attempted":True,"nodeIdNumeric":node,"sessionPath":session,"streamPath":stream}); checkpoint()
          png=A/f"{kind}.png"; gst_command=["gst-launch-1.0","-v","pipewiresrc",f"path={node}","num-buffers=1","do-timestamp=true","!","videoconvert","!","video/x-raw,format=RGBA","!","pngenc","snapshot=true","!","filesink",f"location={png}"]
          gst=subprocess.run(gst_command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
          (A/f"{kind}-gstreamer.log").write_text(gst.stdout)
          entry["pipeWire"]["gstreamer"]={"returnCode":gst.returncode,"command":gst_command,"logArtifact":f"{kind}-gstreamer.log","pngArtifact":png.name if png.exists() else None}; checkpoint()
          if gst.returncode or not png.exists(): raise RuntimeError(f"GStreamer receipt failed with {gst.returncode}")
          marks=landmarks(png); entry["landmarks"]=marks
          stripe=marks["contentStripe"]; capture=marks["imagePixels"]; awt=entry["awt"]; top=awt["topLevelBounds"]; content_bounds=awt["contentBounds"]
          entry["measuredMapping"]={"units":"signed pixel differences; capture pixels versus AWT user-space pixels","captureMinusTopLevelWidth":capture["width"]-top["width"],"captureMinusTopLevelHeight":capture["height"]-top["height"],"captureMinusContentWidth":capture["width"]-content_bounds["width"],"captureMinusContentHeight":capture["height"]-content_bounds["height"],"contentStripeTopInCapture":None if stripe is None else stripe["top"],"awtContentScreenY":awt["contentLocationOnScreen"]["y"],"note":"all values are observations; no decoration delta is inferred"}; checkpoint()
          raw=A/f"{kind}.raw"
          low=subprocess.run([str(A/"geometry-pw"),str(node),str(raw)],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
          (A/f"{kind}-pipewire-monitor.stdout").write_text(low.stdout); (A/f"{kind}-pipewire-monitor.stderr").write_text(low.stderr)
          try: low_evidence=json.loads(low.stdout) if low.stdout.strip() else None
          except json.JSONDecodeError as error: low_evidence={"parseError":str(error),"stdout":low.stdout}
          entry["pipeWire"]["lowerLevelMonitor"]={"returnCode":low.returncode,"stdoutArtifact":f"{kind}-pipewire-monitor.stdout","stderrArtifact":f"{kind}-pipewire-monitor.stderr","rawArtifact":raw.name,"evidence":low_evidence}; checkpoint()
          if low.returncode:
            entry["pipeWire"]["lowerLevelMonitor"]["negotiationFailed"] = True; checkpoint()
        except Exception as error:
          entry["pipeWire"]["consumerError"]={"type":type(error).__name__,"message":str(error),"traceback":traceback.format_exc()}; checkpoint()
        finally:
          if 'session' in locals():
            try: call(SC,session,SC+".Session","Stop",None,"()")
            except GLib.Error as error: entry["pipeWire"]["stopError"]=str(error)
            del session
          checkpoint()
    finally:
      for proc in live:
        proc.terminate()
        try: proc.wait(timeout=5)
        except subprocess.TimeoutExpired: proc.kill()
    (A/"geometry-calibration.json").write_text(json.dumps(summary,indent=2)+"\n")
    assert len(summary["windows"])==2
    assert all(w["landmarks"] and w["landmarks"]["contentStripe"] and w["landmarks"]["centerMarker"] for w in summary["windows"]), summary
    print(json.dumps(summary,indent=2))
if __name__=="__main__": main()
