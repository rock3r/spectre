#!/usr/bin/env python3
"""Launch and independently inspect the genuine public REGION still fixture."""
import json, os, pathlib, re, subprocess, time
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

def invoke(label, command, timeout):
    started = time.monotonic_ns()
    stdout_path = A / f"region-{label}.stdout"
    stderr_path = A / f"region-{label}.stderr"
    try:
        with stdout_path.open("w") as stdout, stderr_path.open("w") as stderr:
            result = subprocess.run(command, cwd=R, env=env, text=True,
                                    stdout=stdout, stderr=stderr, timeout=timeout)
        returncode = result.returncode
    except subprocess.TimeoutExpired:
        returncode = "timeout"
    stdout_path.write_text(sanitized(stdout_path.read_text()))
    stderr_path.write_text(sanitized(stderr_path.read_text()))
    with (A / "region-python-boundaries.jsonl").open("a") as receipt:
        receipt.write(json.dumps({"stage": label, "startedNs": started,
                                  "endedNs": time.monotonic_ns(),
                                  "returnCode": returncode}) + "\n")
    if returncode != 0:
        raise RuntimeError(f"region fixture {label} failed with {returncode}")

invoke("compile", [str(R / "gradlew"), "--no-daemon", "-I", init,
                   ":recording:compileTestKotlin", "--console=plain"], 600)
invoke("run", [str(R / "gradlew"), "--no-daemon", "-I", init,
               ":recording:runPublicWaylandRegionStills", "--console=plain"], 240)

geometry = json.loads((A / "region-app-geometry.json").read_text())
origin = geometry["sceneOrigin"]
if origin is None:
    raise RuntimeError("fixture never reported its mapped scene origin")

def exact_box(image, box, rgb, label):
    x, y, width, height = box
    for py in range(y, y + height):
        for px in range(x, x + width):
            if image.getpixel((px, py)) != rgb:
                raise RuntimeError(f"{label}: mismatch at {px},{py}: {image.getpixel((px, py))}")

regions = {
    "region-a-red.png": ("red", "first", "cyan", (0, 255, 255), (32, 62)),
    "region-b-red.png": ("red", "second", "magenta", (255, 0, 255), (23, 91)),
    "region-a-green-fresh.png": ("green", "first", "cyan", (0, 255, 255), (32, 62)),
    "region-b-green-repeated.png": ("green", "second", "magenta", (255, 0, 255), (23, 91)),
}
proof = {}
references = {}
for phase in ("red", "green"):
    path = A / f"region-{phase}-full-reference.png"
    reference = Image.open(path).convert("RGB")
    device = geometry["device"]
    if reference.size != (device["width"], device["height"]):
        raise RuntimeError(f"{path.name}: full source dimensions {reference.size} != {device}")
    references[phase] = reference
    proof[path.name] = {"dimensions": list(reference.size),
                        "independentFullScreenReference": True}

for name, (phase, key, marker, rgb, local) in regions.items():
    image = Image.open(A / name).convert("RGB")
    request = geometry[key]
    if image.size != (request["width"], request["height"]):
        raise RuntimeError(f"{name}: expected request dimensions, got {image.size}")
    ref = references[phase].crop((origin["x"] + request["x"], origin["y"] + request["y"],
                                  origin["x"] + request["x"] + request["width"],
                                  origin["y"] + request["y"] + request["height"]))
    if image.tobytes() != ref.tobytes():
        raise RuntimeError(f"{name}: pixels differ from independently retained full reference")
    exact_box(image, (*local, 18, 18), rgb, f"{name} exact {marker}")
    proof[name] = {"dimensions": list(image.size), "phase": phase,
                   "exactReferenceMatch": True, "marker": marker,
                   "markerBox": [*local, 18, 18]}

receipts = [json.loads(line) for line in (A / "region-java-receipts.jsonl").read_text().splitlines()]
region_receipts = [item["name"] for item in receipts if item["kind"] == "public-region"]
expected_order = list(regions)
if region_receipts != expected_order:
    raise RuntimeError(f"unexpected capture order: {receipts}")
(A / "public-region-stills-evidence.json").write_text(json.dumps({
    "evidenceKind": "region",
    "publicCaller": "LinuxNativeScreenshotter.captureRegion(Rectangle)",
    "productCommit": os.environ["CANDIDATE_SHA"],
    "productTree": "c2bdbe1c9aa55eb536ee727231d0e33877fbcafd",
    "geometryReportedBeforeAssertions": True,
    "comparison": "exact RGB crop versus independently captured retained full-screen reference",
    "captures": proof,
    "receipts": receipts,
}, indent=2) + "\n")
