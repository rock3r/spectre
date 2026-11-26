#!/usr/bin/env python3
"""Independent public-API crop proof for the temporary hosted GNOME workflow."""
import json, os, pathlib, subprocess, sys, time
from PIL import Image

A = pathlib.Path(os.environ["SMOKE_ARTIFACTS"])
R = pathlib.Path(os.environ["SMOKE_REPO"])
TITLE = "Spectre public crop same-title"
RECEIPTS = A / "crop-orchestrator-receipts.json"
receipts = []

def receipt(phase, **fields):
    receipts.append({"phase": phase, "monotonicNs": time.monotonic_ns(), **fields})
    RECEIPTS.write_text(json.dumps(receipts, indent=2) + "\n")

def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)

def all_xids(pid):
    # --all and the positional title are deliberate; title-only identity is never trusted.
    found = subprocess.check_output(["xdotool", "search", "--all", "--name", TITLE], text=True).split()
    unique = list(dict.fromkeys(found))
    owned = []
    for xid in unique:
        owner = subprocess.run(["xdotool", "getwindowpid", xid], text=True, capture_output=True)
        if owner.returncode == 0 and owner.stdout.strip() == str(pid): owned.append(xid)
    if len(owned) != 1: raise RuntimeError(f"expected one independently PID-owned decoy XID; all={unique}, owned={owned}")
    return unique, owned[0]

def inspect(path, expected):
    image = Image.open(path).convert("RGB")
    if image.size != (220, 140): raise RuntimeError(f"{path.name}: dimensions {image.size}")
    pixels = list(image.getdata())
    def ratio(test): return sum(test(*p) for p in pixels) / len(pixels)
    values = {"width": image.width, "height": image.height,
      "base": ratio(lambda r,g,b: (r > 150 and g < 80 and b < 90) if expected == "red" else (g > 120 and r < 80 and b < 100)),
      "blue": ratio(lambda r,g,b: b > 150 and r < 80 and g < 120),
      "yellow": ratio(lambda r,g,b: r > 180 and g > 180 and b < 100),
      "cyan": ratio(lambda r,g,b: r < 80 and g > 150 and b > 150),
      "magenta": ratio(lambda r,g,b: r > 150 and g < 100 and b > 150)}
    marker = list(image.crop((24, 19, 42, 37)).getdata())
    values["positiveMarkerYellowRatio"] = sum(r > 180 and g > 180 and b < 100 for r,g,b in marker) / len(marker)
    values["negativeMarkerCyanRatioAtPositiveRoi"] = sum(r < 80 and g > 150 and b > 150 for r,g,b in marker) / len(marker)
    values["negativeMarkerMagentaRatioAtPositiveRoi"] = sum(r > 150 and g < 100 and b > 150 for r,g,b in marker) / len(marker)
    if (values["base"] < .75 or values["positiveMarkerYellowRatio"] < .85 or
        values["negativeMarkerCyanRatioAtPositiveRoi"] > .02 or values["negativeMarkerMagentaRatioAtPositiveRoi"] > .02 or values["blue"] > .002):
        raise RuntimeError(f"{path.name}: marker/color contract failed: {values}")
    return values

def decode_frames(movie, case):
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
      "-show_entries", "stream=width,height,nb_read_frames,duration,avg_frame_rate", "-of", "json", movie], text=True))["streams"][0]
    count = int(probe["nb_read_frames"])
    duration = float(probe["duration"])
    if (int(probe["width"]), int(probe["height"])) != (220, 140) or count < 3 or not (2.9 <= duration <= 4.5):
        raise RuntimeError(f"bad video dimensions/frame count/duration: {probe}")
    indices = [0, count // 2, count - 1]
    evidence = []
    for label, index in zip(("first-frame-0", "middle", "final"), indices):
        output = A / f"{case}-{label}.png"
        run(["ffmpeg", "-v", "error", "-i", str(movie), "-vf", f"select=eq(n\\,{index})", "-vsync", "0", "-frames:v", "1", "-y", str(output)])
        evidence.append({"label": label, "decodedFrameIndex": index, "analysis": inspect(output, case.split('-')[0])})
    ticker_boxes = []
    for sample in evidence:
        image = Image.open(A / f"{case}-{sample['label']}.png").convert("RGB")
        ticker_boxes.append(image.crop((0, 100, 220, 140)).tobytes())
    if len(set(ticker_boxes)) < 2:
        raise RuntimeError(f"{case}: deterministic ticker did not move across decoded samples")
    return {"ffprobe": probe, "decodedFrameCount": count, "motionAcrossSamples": True, "samples": evidence}

A.mkdir(parents=True, exist_ok=True)
decoy_source = A / "CropBlueDecoy.java"
decoy_source.write_text('''
import java.awt.*; import javax.swing.*; import java.nio.file.*;
class CropBlueDecoy { public static void main(String[] a) throws Exception {
 SwingUtilities.invokeAndWait(() -> { JFrame f=new JFrame("Spectre public crop same-title");
 JPanel p=new JPanel(); p.setBackground(Color.BLUE); f.setContentPane(p); f.setBounds(200,200,480,320); f.setVisible(true); });
 Files.writeString(Path.of(a[0]), Long.toString(ProcessHandle.current().pid())); Thread.sleep(600000); } }
''')
decoy = subprocess.Popen([os.environ["JAVA_HOME"] + "/bin/java", str(decoy_source), str(A / "decoy.pid")], stdout=open(A / "decoy.stdout", "w"), stderr=open(A / "decoy.stderr", "w"))
try:
    for _ in range(100):
        if (A / "decoy.pid").exists(): break
        if decoy.poll() is not None: raise RuntimeError("blue decoy exited")
        time.sleep(.1)
    ids, xid = all_xids(decoy.pid)
    run(["xdotool", "windowactivate", "--sync", xid]); run(["xdotool", "windowraise", xid])
    (A / "decoy-identity.json").write_text(json.dumps({"pid": decoy.pid, "xid": int(xid), "deduplicatedTitleXids": list(map(int, ids))}, indent=2)+"\n")
    receipt("decoy-identified", pid=decoy.pid, xid=int(xid))
    env = os.environ.copy(); env.update(DECOY_PID=str(decoy.pid), DECOY_XID=xid,
      SPECTRE_CROP_VERIFICATION_SOURCE=str(pathlib.Path(__file__).parents[1] / "verification/wayland-public-crop"))
    command = [str(R / "gradlew"), "--no-daemon", "-I", str(pathlib.Path(__file__).parents[1] / "verification/wayland-public-crop/build.init.gradle"),
      ":recording:runPublicWaylandCropProof", "--console=plain"]
    compile_command = [str(R / "gradlew"), "--no-daemon", "-I", str(pathlib.Path(__file__).parents[1] / "verification/wayland-public-crop/build.init.gradle"),
      ":recording:compileTestKotlin", "--console=plain"]
    compile_result = subprocess.run(compile_command, cwd=R, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (A / "crop-compile.stdout").write_text(compile_result.stdout); (A / "crop-compile.stderr").write_text(compile_result.stderr)
    receipt("actual-kotlin-test-source-compiled", command=compile_command, exitCode=compile_result.returncode,
      source=env["SPECTRE_CROP_VERIFICATION_SOURCE"])
    if compile_result.returncode: raise RuntimeError("public crop Kotlin test source did not compile")
    result = subprocess.run(command, cwd=R, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (A / "crop-fixture.stdout").write_text(result.stdout); (A / "crop-fixture.stderr").write_text(result.stderr)
    receipt("fixture-finished", exitCode=result.returncode)
    if result.returncode: raise RuntimeError("public crop fixture failed")
    evidence = {}
    for case in ("red-decorated", "green-undecorated"):
        intended = json.loads((A / f"{case}-intended.json").read_text())
        whole = Image.open(A / f"{case}-whole-public-still.png")
        if whole.size != (intended["capturedWhole"]["width"], intended["capturedWhole"]["height"]):
            raise RuntimeError(f"{case}: public whole still dimensions disagree with receipt")
        if case.endswith("decorated") and not case.endswith("undecorated") and intended["contentInsets"]["top"] <= 0:
            raise RuntimeError(f"{case}: decorated public still has no verified top-bar inset")
        evidence[case] = {"wholePublicStill":{"dimensions":whole.size,"topBarInset":intended["contentInsets"]["top"]},
          "referenceCropDerivedFromPublicStillNotNativeEndpoint": inspect(A / f"{case}-reference-crop-derived-from-public-still.png", case.split('-')[0]),
          "genuinePublicAutoRecorderCrop": decode_frames(A / f"{case}-crop.mp4", case)}
    (A / "public-window-crop-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    receipt("artifacts-verified", cases=list(evidence))
finally:
    if decoy.poll() is None:
        decoy.terminate()
        try: decoy.wait(10)
        except subprocess.TimeoutExpired: decoy.kill(); decoy.wait(5)
