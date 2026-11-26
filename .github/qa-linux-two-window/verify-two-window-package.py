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

def wait_for_atspi_windows(pid, expected_titles, limit=70):
    probe = r'''
import json
import sys
import time

import dbus

pid = int(sys.argv[1])
deadline = time.monotonic() + int(sys.argv[2])
expected = set(sys.argv[3:])
accessible = "org.a11y.atspi.Accessible"
observations = []
errors = []
session_bus = dbus.SessionBus()
bus_address = session_bus.get_object("org.a11y.Bus", "/org/a11y/bus").GetAddress(
    dbus_interface="org.a11y.Bus")
bus = dbus.bus.BusConnection(str(bus_address))
admin = dbus.Interface(bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus"),
                       "org.freedesktop.DBus")

while time.monotonic() < deadline:
    try:
        registry_object = bus.get_object("org.a11y.atspi.Registry",
                                         "/org/a11y/atspi/accessible/root")
        applications = dbus.Interface(registry_object, accessible).GetChildren()
        matches = []
        for advertised_service, application_path in applications:
            advertised_service = str(advertised_service)
            owner = str(admin.GetNameOwner(advertised_service))
            owner_pid = int(admin.GetConnectionUnixProcessID(owner))
            if owner_pid != pid:
                continue
            if not owner.startswith(":"):
                raise AssertionError("AT-SPI application owner is not a unique bus name: " + owner)
            application = dbus.Interface(bus.get_object(owner, str(application_path)), accessible)
            windows = []
            for child_service, child_path in application.GetChildren():
                child_service = str(child_service)
                child_path = str(child_path)
                child_owner = str(admin.GetNameOwner(child_service))
                child = bus.get_object(child_owner, child_path)
                properties = dbus.Interface(child, "org.freedesktop.DBus.Properties")
                name = str(properties.Get(accessible, "Name"))
                try:
                    role = str(dbus.Interface(child, accessible).GetRoleName())
                except Exception as error:
                    role = None
                    errors.append({"operation": "GetRoleName", "service": child_owner,
                                   "path": child_path, "error": repr(error)})
                child_pid = int(admin.GetConnectionUnixProcessID(child_owner))
                windows.append({"name": name, "path": child_path, "service": child_owner,
                                "pid": child_pid,
                                "roleName": role})
            matches.append({"advertisedService": advertised_service, "service": owner,
                            "pid": owner_pid, "applicationPath": str(application_path),
                            "windows": windows})
        observation = {"applications": matches}
        observations.append(observation)
        observations = observations[-20:]
        names = {window["name"] for match in matches for window in match["windows"]
                 if window["pid"] == pid}
        if len(matches) == 1 and expected <= names:
            print(json.dumps({"matched": True, "expectedTitles": sorted(expected),
                              "observation": observation, "observations": observations,
                              "errors": errors}, indent=2))
            sys.exit(0)
    except Exception as error:
        errors.append({"operation": "registry-enumeration", "error": repr(error)})
    time.sleep(.3)

print(json.dumps({"matched": False, "expectedTitles": sorted(expected),
                  "observations": observations, "errors": errors}, indent=2))
sys.exit(1)
'''
    args = ["/usr/bin/python3", "-c", probe, str(pid), str(limit), *sorted(expected_titles)]
    result = subprocess.run(args, env=env, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace", timeout=limit + 5)
    evidence = out / "atspi-registration.raw.json"
    evidence.write_text(result.stdout or json.dumps({"matched": False, "stderr": result.stderr}, indent=2))
    command_log.write(f"$ /usr/bin/python3 -c <AT-SPI registration probe> {pid}\n"
                      f"--- stdout ---\n{result.stdout}\n--- stderr ---\n{result.stderr}\n")
    command_log.flush()
    try:
        registration = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise AssertionError(f"ATSPIROOT_PID_OWNER_REGISTRATION_INVALID: {result.stderr[-2400:]}") from error
    if result.returncode or not registration.get("matched"):
        raise AssertionError(f"ATSPIROOT_PID_OWNER_REGISTRATION_FAILED: {registration}")
    return registration

sample_cp = (root / "sample-desktop/build/two-window-qa-classpath.txt").read_text()
session = None
fixture = None
try:
    with (out / "fixture.log").open("w", encoding="utf-8") as log:
        fixture = subprocess.Popen(["java", "-cp", sample_cp,
            "dev.sebastiano.spectre.sample.validation.AttachedTwoWindowFixtureKt"],
            stdout=log, stderr=subprocess.STDOUT, env=fixture_env)
    print("verifying packaged CLI", cli, "fixture PID", fixture.pid, "evidence", out, flush=True)
    registration = wait_for_atspi_windows(fixture.pid, titles)
    if fixture.poll() is not None:
        raise RuntimeError("FIXTURE_EXITED " + (out / "fixture.log").read_text()[-2400:])

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
