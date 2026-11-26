#!/usr/bin/env python3
import argparse, json, pathlib, platform, subprocess, zipfile

MACHINES = {"x64": 62, "arm64": 183}

def run(command, evidence, stdin=None):
    result = subprocess.run(command, input=stdin, text=True, capture_output=True)
    evidence.write("$ " + " ".join(map(str, command)) + "\n" + result.stdout + result.stderr + f"[exit {result.returncode}]\n")
    if result.returncode: raise SystemExit(f"command failed: {command}")
    return result.stdout

def main():
    p=argparse.ArgumentParser(); p.add_argument("--version", required=True); p.add_argument("--arch", choices=MACHINES, required=True); p.add_argument("--archive", required=True); p.add_argument("--evidence", required=True); p.add_argument("--golden", required=True); a=p.parse_args()
    host = "arm64" if platform.machine().lower() in {"aarch64", "arm64"} else "x64"
    if host != a.arch: raise SystemExit(f"native runner is {host}, archive is {a.arch}")
    root=pathlib.Path(a.evidence); root.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(a.archive) as z: z.extractall(root/"archive")
    exe=next((root/"archive").rglob("spectre")); exe.chmod(0o755)
    with (root/"commands.txt").open("w") as log:
        help_text=run([exe,"--help"],log); version_text=run([exe,"--version"],log)
        if f"spectre {a.version}" not in version_text: raise SystemExit("CLI version mismatch")
        if "record" not in help_text: raise SystemExit("published CLI lacks the 0.9 recording command")
        helpers=[]
        for jar in (root/"archive").rglob("*.jar"):
            with zipfile.ZipFile(jar) as contents:
                for name in contents.namelist():
                    data=contents.read(name)
                    if name.startswith("native/linux/") and data[:4] == b"\x7fELF":
                        machine=int.from_bytes(data[18:20], "little")
                        if machine == MACHINES[a.arch]: helpers.append((jar,name,data))
        recording=[h for h in helpers if "spectre-wayland-helper" in h[1]]
        if len(recording) != 1: raise SystemExit("expected exactly one matching packaged recording helper")
        jar,name,data=recording[0]; helper=root/pathlib.Path(name).name; helper.write_bytes(data); helper.chmod(0o755)
        identity={"archive":pathlib.Path(a.archive).name,"runnerArchitecture":host,"jar":jar.name,"entry":name,"elfMachine":MACHINES[a.arch]}
        if a.version == "0.10.0":
            response=json.loads(run([helper,"accessibility-snapshot"],log,stdin='{"pid":1}\n'))
            golden=json.loads(pathlib.Path(a.golden).read_text())
            if not set(golden["requiredTopLevel"]).issubset(response) or (response.get("ok") and not set(golden["requiredSnapshot"]).issubset(response["snapshot"])): raise SystemExit("0.10 accessibility response violates golden shape")
            (root/"accessibility-snapshot-actual.json").write_text(json.dumps(response,indent=2)+"\n")
        else:
            run([helper,"--help"],log)
            identity["accessibilitySnapshot"]="not claimed for historical 0.9.0"
        (root/"native-identity.json").write_text(json.dumps(identity,indent=2)+"\n")

if __name__=="__main__": main()
