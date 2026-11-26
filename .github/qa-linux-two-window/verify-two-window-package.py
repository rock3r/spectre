#!/usr/bin/env python3
"""Fail-closed proof of packaged v5 same-PID, two-window CLI and MCP attach."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
helper_dir = Path(__file__).resolve().parent
out = Path(os.environ["TWO_WINDOW_EVIDENCE"]).resolve()
out.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(root / "scripts"))
from smoke_lib import packaged_cli_executable

cli = str(packaged_cli_executable(root))
env = os.environ.copy()
for key in ("SPECTRE_WINDOWS_GRAPHICS_CAPTURE_HELPER", "SPECTRE_SCREENCAPTURE_HELPER", "SPECTRE_WAYLAND_HELPER"):
    env.pop(key, None)
env["SPECTRE_DAEMON_SOCKET_DIR"] = str(out / "daemon")
fixture_env = env.copy()
fixture_env["HOME"] = os.environ["FIXTURE_HOME"]
fixture_env["JAVA_TOOL_OPTIONS"] = os.environ["FIXTURE_JAVA_TOOL_OPTIONS"]
titles = {f"Spectre attached fixture {tag}": (tag, color) for tag, color in (("A", "174ea6"), ("B", "e8710a"))}
command_log = (out / "commands.log").open("a", encoding="utf-8")

def run(args, limit=45):
    args = list(map(str, args))
    result = subprocess.run(args, env=env, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace", timeout=limit)
    command_log.write(f"$ {' '.join(args)}\n--- stdout ---\n{result.stdout}\n--- stderr ---\n{result.stderr}\n")
    command_log.flush()
    if result.returncode:
        raise RuntimeError(f"COMMAND_FAILED {args[1:4]} exited {result.returncode}: {result.stderr[-2400:]} {result.stdout[-800:]}")
    return result.stdout

def read(*args):
    return json.loads(run([cli, *args]))

def all_text(doc):
    return json.dumps(doc, ensure_ascii=False)

sample_cp = (root / "sample-desktop/build/two-window-qa-classpath.txt").read_text()
session = None
fixture = None
try:
    with (out / "fixture.log").open("w", encoding="utf-8") as log:
        fixture = subprocess.Popen(["java", "-cp", sample_cp,
            "dev.sebastiano.spectre.sample.validation.AttachedTwoWindowFixtureKt"],
            stdout=log, stderr=subprocess.STDOUT, env=fixture_env)
    print("verifying packaged CLI", cli, "fixture PID", fixture.pid, "evidence", out, flush=True)
    deadline = time.monotonic() + 70
    registration = None
    while time.monotonic() < deadline:
        if fixture.poll() is not None:
            raise RuntimeError("FIXTURE_EXITED " + (out / "fixture.log").read_text()[-2400:])
        try:
            registration = read("a11y", "tree", "--pid", str(fixture.pid), "--timeout-ms", "3000")
            if titles.keys() <= {w.get("title") for w in registration.get("windows", [])}:
                break
        except (RuntimeError, json.JSONDecodeError):
            pass
        time.sleep(.3)
    else:
        raise AssertionError("ATSPIROOT_PID_OWNER_REGISTRATION_FAILED: two titled windows were not registered for the exact fixture PID")
    (out / "atspiroot-registration.raw.json").write_text(json.dumps(registration, indent=2, ensure_ascii=False))

    session = read("attach", "--json", str(fixture.pid))["sessionId"]
    for _ in range(40):
        windows = read("windows", "--json", session)["windows"]
        if titles.keys() <= {w.get("title") for w in windows}:
            break
        time.sleep(.25)
    target_windows = [w for w in windows if w.get("title") in titles]
    if len(target_windows) != 2 or len({w.get("index") for w in target_windows}) != 2:
        raise AssertionError(f"TWO_DISTINCT_WINDOW_INDICES_FAILED: {windows}")

    for window in target_windows:
        title = window["title"]
        tag, color = titles[title]
        other = "B" if tag == "A" else "A"
        index = str(window["index"])
        native = read("a11y", "tree", "--session", session, "--window-index", index, "--timeout-ms", "5000")
        (out / f"native-{tag}.raw.json").write_text(json.dumps(native, indent=2, ensure_ascii=False))
        if [item.get("title") for item in native.get("windows", [])] != [title]:
            raise AssertionError(f"WINDOW_TITLE_OWNERSHIP_FAILED_{tag}: {native.get('windows')}")
        native_text = all_text(native)
        if f"STABLE WINDOW {tag}" not in native_text or f"STABLE WINDOW {other}" in native_text:
            raise AssertionError(f"NATIVE_SELECTED_WINDOW_CONTENT_FAILED_{tag}")
        for source in ("compose", "accessibility"):
            capture_dir = out / f"{tag}-{source}"
            report = read("capture", "--json", "--source", source, "--window", index,
                          "--out-dir", capture_dir, session)
            (out / f"report-{tag}-{source}.json").write_text(json.dumps(report, indent=2))
            document = json.loads(Path(report["captureJson"]).read_text(encoding="utf-8"))
            if document.get("schemaVersion") != 2 or not document.get("nodes"):
                raise AssertionError(f"EMPTY_{source.upper()}_TREE_{tag}")
            exposed = all_text(document)
            if f"STABLE WINDOW {tag}" not in exposed or f"STABLE WINDOW {other}" in exposed:
                raise AssertionError(f"{source.upper()}_SELECTED_WINDOW_CONTENT_FAILED_{tag}")
            pixel = run(["java", helper_dir / "WindowQaPixel.java", report["screenshotPng"], color]).strip()
            print(title, "index", index, source, len(document["nodes"]), pixel, flush=True)

    run([cli, "detach", session]); session = None
    for window in target_windows:
        tag, _ = titles[window["title"]]
        stdout = run([sys.executable, root / "scripts/mcp-stdio-smoke.py", "--expected-version", "0.10.0",
            "--attach-pid", fixture.pid, "--accessibility-capture", "--window-index", window["index"],
            "--expected-text", f"STABLE WINDOW {tag}", "--", cli], 120)
        (out / f"mcp-{tag}.stdout.log").write_text(stdout)
        print(f"MCP_{tag}_ROW", stdout.strip(), flush=True)
finally:
    if session:
        try: run([cli, "detach", session])
        except Exception as error: print("cleanup detach:", error, file=sys.stderr)
    try: run([cli, "daemon", "kill"])
    except Exception as error: print("cleanup daemon:", error, file=sys.stderr)
    if fixture is not None and fixture.poll() is None:
        fixture.terminate()
        try: fixture.wait(timeout=8)
        except subprocess.TimeoutExpired:
            fixture.kill(); fixture.wait(timeout=8)
    command_log.close()
    # Retain full reports while removing nondeterministic generated helper installation paths.
    for path in out.glob("*.log"):
        data = path.read_text(encoding="utf-8", errors="replace")
        data = data.replace(str(Path.home()), "<FIXTURE_HOME>").replace(str(root), "<CANDIDATE>")
        path.write_text(data, encoding="utf-8")
