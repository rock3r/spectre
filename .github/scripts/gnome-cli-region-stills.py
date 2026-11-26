#!/usr/bin/env python3
"""Launch and independently inspect the genuine public REGION still fixture."""
import json, os, pathlib, re, subprocess
from PIL import Image

A = pathlib.Path(os.environ["SMOKE_ARTIFACTS"])
R = pathlib.Path(os.environ["SMOKE_REPO"])
V = pathlib.Path(__file__).parents[1] / "verification/wayland-cli-region-stills"
env = os.environ.copy()
env["SPECTRE_REGION_VERIFICATION_SOURCE"] = str(V)
init = str(V / "build.init.gradle")

def sanitized(text):
    return re.sub(r'(?i)(authorization|bearer|restore[_ -]?token)(["=: ]+)[^\s",}]+',
                  r'\1\2<redacted>', text)

compile_command = [str(R / "gradlew"), "--no-daemon", "-I", init,
                   ":recording:compileTestKotlin", "--console=plain"]
run_command = [str(R / "gradlew"), "--no-daemon", "-I", init,
               ":recording:runPublicWaylandRegionStills", "--console=plain"]
for label, command, timeout in (("compile", compile_command, 600), ("run", run_command, 180)):
    result = subprocess.run(command, cwd=R, env=env, text=True, capture_output=True, timeout=timeout)
    (A / f"region-{label}.stdout").write_text(result.stdout)
    (A / f"region-{label}.stderr").write_text(sanitized(result.stderr))
    if result.returncode:
        raise RuntimeError(f"region fixture {label} failed with {result.returncode}")

expected = {
    "region-a-red.png": ((311, 173), "red"),
    "region-b-red.png": ((229, 191), "red"),
    "region-a-green-fresh.png": ((311, 173), "green"),
    "region-b-green-repeated.png": ((229, 191), "green"),
}
proof = {}
for name, (size, phase) in expected.items():
    image = Image.open(A / name).convert("RGB")
    if image.size != size:
        raise RuntimeError(f"{name}: expected {size}, got {image.size}")
    pixels = list(image.getdata())
    positive = sum((r > 170 and g < 70) if phase == "red" else
                   (g > 160 and r < 70) for r, g, b in pixels) / len(pixels)
    black = sum(r < 8 and g < 8 and b < 8 for r, g, b in pixels) / len(pixels)
    if positive < .70 or black > 0:
        raise RuntimeError(f"{name}: positive={positive}, black={black}")
    proof[name] = {"dimensions": list(image.size), "phase": phase,
                   "positiveInteriorRatio": positive, "blackRatio": black}

receipts = [json.loads(line) for line in (A / "region-java-receipts.jsonl").read_text().splitlines()]
if [item["name"] for item in receipts] != list(expected):
    raise RuntimeError(f"unexpected capture order: {receipts}")
(A / "public-region-stills-evidence.json").write_text(json.dumps({
    "publicCaller": "LinuxNativeScreenshotter.captureRegion(Rectangle)",
    "productCommit": os.environ["CANDIDATE_SHA"],
    "productTree": "c2bdbe1c9aa55eb536ee727231d0e33877fbcafd",
    "captures": proof,
    "receipts": receipts,
}, indent=2) + "\n")
