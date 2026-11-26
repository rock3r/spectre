#!/usr/bin/env python3
import argparse, json, os, pathlib, platform, shutil, stat, struct, subprocess, tempfile, zipfile

MACHINES={"x64":("x86_64",62),"arm64":("aarch64",183)}

def run(command, log, stdin=None, timeout=20):
    log.write("$ "+" ".join(map(str,command))+"\n"); log.flush()
    try:
        result=subprocess.run(command,input=stdin,text=True,capture_output=True,timeout=timeout)
    except subprocess.TimeoutExpired as error:
        log.write((error.stdout or "")+(error.stderr or "")+f"[timeout after {timeout}s]\n")
        raise SystemExit(f"command timed out: {command}")
    log.write(result.stdout+result.stderr+f"[exit {result.returncode}]\n"); log.flush()
    if result.returncode: raise SystemExit(f"command failed: {command}")
    return result.stdout

def extract_zip_preserving_unix(archive, destination):
    """Extract safely while honoring every stored Unix type/mode, including symlinks."""
    destination.mkdir(parents=True)
    root=destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            target=(destination/item.filename).resolve(strict=False)
            if target != root and root not in target.parents: raise SystemExit(f"unsafe archive path: {item.filename}")
            mode=item.external_attr >> 16
            kind=stat.S_IFMT(mode)
            if item.is_dir() or kind == stat.S_IFDIR:
                target.mkdir(parents=True,exist_ok=True)
            elif kind == stat.S_IFLNK:
                target.parent.mkdir(parents=True,exist_ok=True)
                link=source.read(item).decode()
                if os.path.isabs(link): raise SystemExit(f"absolute archive symlink: {item.filename}")
                target.symlink_to(link)
            elif kind in (0,stat.S_IFREG):
                target.parent.mkdir(parents=True,exist_ok=True)
                with source.open(item) as incoming,target.open("wb") as outgoing: shutil.copyfileobj(incoming,outgoing)
            else: raise SystemExit(f"unsupported archive entry type: {item.filename}")
            if kind != stat.S_IFLNK and mode: target.chmod(stat.S_IMODE(mode))

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--version",required=True); p.add_argument("--arch",choices=MACHINES,required=True)
    p.add_argument("--archive",required=True); p.add_argument("--evidence",required=True)
    a=p.parse_args(); machine=platform.machine().lower()
    expected_name,expected_elf=MACHINES[a.arch]
    if machine != expected_name: raise SystemExit(f"runner machine must be exactly {expected_name}, got {machine}")
    root=pathlib.Path(a.evidence); root.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        unpacked=pathlib.Path(temporary)/"archive"; extract_zip_preserving_unix(a.archive,unpacked)
        executables=[p for p in unpacked.rglob("spectre") if p.is_file() and not p.is_symlink()]
        if len(executables)!=1: raise SystemExit(f"expected one regular spectre executable, found {len(executables)}")
        exe=executables[0]
        if not os.access(exe,os.X_OK): raise SystemExit("archive spectre path is not executable")
        with (root/"commands.txt").open("w") as log:
            version_text=run([exe,"--version"],log)
            if version_text.strip()!=f"spectre {a.version}": raise SystemExit(f"CLI version mismatch: {version_text!r}")
            # Locate only the known helper entry; never materialize every class or scan outside jars.
            candidates=[]
            for jar in unpacked.rglob("*.jar"):
                with zipfile.ZipFile(jar) as contents:
                    for info in contents.infolist():
                        if info.filename.startswith("native/linux/") and "spectre-wayland-helper" in info.filename:
                            with contents.open(info) as entry: header=entry.read(20)
                            if header[:4]==b"\x7fELF" and struct.unpack_from("<H",header,18)[0]==expected_elf:
                                candidates.append((jar,info.filename,info))
            if len(candidates)!=1: raise SystemExit(f"expected one architecture-matching helper, found {len(candidates)}")
            jar,name,info=candidates[0]; helper=pathlib.Path(temporary)/"spectre-wayland-helper"
            with zipfile.ZipFile(jar) as contents,contents.open(info) as incoming,helper.open("wb") as outgoing: shutil.copyfileobj(incoming,outgoing)
            helper.chmod(0o755)
            if a.version=="0.9.0":
                # The historical helper has a recording JSON-lines protocol; --help is not its contract.
                response=json.loads(run([helper],log,stdin='{"command":"stop"}\n',timeout=5))
                if response.get("event") != "error" or response.get("kind") != "Protocol":
                    raise SystemExit("historical helper did not return its documented protocol error")
                claim="finite genuine stdin JSON recording-protocol probe; accessibility not claimed"
            else:
                claim="CLI accessibility acceptance is performed against the independent GTK fixture"
            identity={"archive":pathlib.Path(a.archive).name,"runnerMachine":machine,"expectedElfMachine":expected_elf,"jar":jar.name,"entry":name,"historicalProbe":claim}
            (root/"native-identity.json").write_text(json.dumps(identity,indent=2)+"\n")

if __name__=="__main__": main()
