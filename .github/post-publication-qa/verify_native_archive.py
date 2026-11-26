#!/usr/bin/env python3
import argparse, json, os, pathlib, platform, shutil, stat, struct, subprocess, tempfile, zipfile

MACHINES={"x64":("x86_64",62),"arm64":("aarch64",183)}

def run(command, log, stdin=None, timeout=20, env=None):
    log.write("$ "+" ".join(map(str,command))+"\n"); log.flush()
    try:
        result=subprocess.run(command,input=stdin,text=True,capture_output=True,timeout=timeout,env=env)
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

def elf_machine(header):
    return struct.unpack_from("<H",header,18)[0] if len(header)>=20 and header[:4]==b"\x7fELF" else None

def diagnose_archive(archive, root, expected_elf):
    """Write an actionable central-directory/runtime report before any launcher is started."""
    root.mkdir(parents=True,exist_ok=True)
    report={"expectedElfMachine":expected_elf,"expectedMachines":{"x64":62,"arm64":183}}
    problems=[]; helpers=[]
    with zipfile.ZipFile(archive) as source:
        infos=source.infolist()
        def inspect(label, predicate):
            matches=[i for i in infos if not i.is_dir() and predicate(pathlib.PurePosixPath(i.filename))]
            values=[]
            for info in matches:
                with source.open(info) as entry: header=entry.read(20)
                values.append({"path":info.filename,"unixMode":oct(stat.S_IMODE(info.external_attr>>16)),"elfMachine":elf_machine(header),"size":info.file_size})
            report[label]=values
            if not values: problems.append(f"absent {label}")
            elif len(values)!=1: problems.append(f"expected exactly one {label}, found {len(values)}")
            elif values[0]["elfMachine"]!=expected_elf: problems.append(f"found {label} with wrong ISA {values[0]['elfMachine']!r}, expected {expected_elf}")
            return matches
        launchers=inspect("launcher",lambda p:p.name=="spectre" and p.parent.name=="bin")
        inspect("libjvm.so",lambda p:p.name=="libjvm.so")
        inspect("libjava.so",lambda p:p.name=="libjava.so")
        # The helper is packaged by the recording component. Filter by basename before
        # opening ZIPs so diagnostics do not inflate or scan unrelated bundled libraries.
        for jar in (i for i in infos if i.filename.endswith(".jar") and "recording" in pathlib.PurePosixPath(i.filename).name.lower()):
            with source.open(jar) as incoming, tempfile.NamedTemporaryFile() as copy:
                shutil.copyfileobj(incoming,copy); copy.flush()
                try:
                    with zipfile.ZipFile(copy.name) as contents:
                        for info in contents.infolist():
                            if info.filename.startswith("native/linux/") and "spectre-wayland-helper" in info.filename:
                                with contents.open(info) as entry: machine=elf_machine(entry.read(20))
                                helpers.append({"jar":jar.filename,"path":info.filename,"unixMode":oct(stat.S_IMODE(info.external_attr>>16)),"elfMachine":machine,"size":info.file_size})
                except zipfile.BadZipFile: pass
        report["recordingHelpers"]=helpers
        matching=[x for x in helpers if x["elfMachine"]==expected_elf]
        if len(matching)!=1: problems.append(f"expected one packaged recording helper for ISA {expected_elf}, found {len(matching)}")
        if launchers and not (stat.S_IMODE(launchers[0].external_attr>>16)&0o111): problems.append("actual launcher lacks a stored executable mode")
    report["status"]="pass" if not problems else "fail"; report["problems"]=problems
    (root/"archive-diagnostic.json").write_text(json.dumps(report,indent=2)+"\n")
    (root/"archive-diagnostic-status.txt").write_text(report["status"]+"\n")
    if problems: raise SystemExit("archive diagnostic failed: "+"; ".join(problems))
    return report

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--version",required=True); p.add_argument("--arch",choices=MACHINES,required=True)
    p.add_argument("--archive",required=True); p.add_argument("--evidence",required=True)
    a=p.parse_args(); machine=platform.machine().lower()
    expected_name,expected_elf=MACHINES[a.arch]
    if machine != expected_name: raise SystemExit(f"runner machine must be exactly {expected_name}, got {machine}")
    root=pathlib.Path(a.evidence); root.mkdir(parents=True,exist_ok=True)
    diagnostic=diagnose_archive(a.archive,root,expected_elf)
    with tempfile.TemporaryDirectory() as temporary:
        unpacked=pathlib.Path(temporary)/"archive"; extract_zip_preserving_unix(a.archive,unpacked)
        launcher_path=diagnostic["launcher"][0]["path"]
        exe=unpacked/launcher_path
        if not exe.is_file() or exe.is_symlink(): raise SystemExit("diagnosed launcher is not a regular extracted file")
        if not os.access(exe,os.X_OK): raise SystemExit("archive spectre path is not executable")
        with (root/"commands.txt").open("w") as log:
            # A poisoned JAVA_HOME and restricted PATH make host-JDK substitution fail. Successful
            # startup therefore exercises the packaged launcher and its diagnosed bundled JVM.
            isolated_env=os.environ.copy(); isolated_env.update({"JAVA_HOME":"/nonexistent-host-jdk","PATH":"/usr/bin:/bin"})
            version_text=run([exe,"--version"],log,env=isolated_env)
            if version_text.strip()!=f"spectre {a.version}": raise SystemExit(f"CLI version mismatch: {version_text!r}")
            help_text=run([exe,"--help"],log,env=isolated_env)
            if not help_text.strip(): raise SystemExit("CLI --help output is empty")
            record_help=run([exe,"record","--help"],log,env=isolated_env)
            if not record_help.strip(): raise SystemExit("CLI record --help output is empty")
            # Reuse the pre-launch diagnostic's exact entry instead of scanning bundled libraries again.
            packaged=[x for x in diagnostic["recordingHelpers"] if x["elfMachine"]==expected_elf]
            jar=unpacked/packaged[0]["jar"]; name=packaged[0]["path"]
            with zipfile.ZipFile(jar) as contents: info=contents.getinfo(name)
            helper=pathlib.Path(temporary)/"spectre-wayland-helper"
            with zipfile.ZipFile(jar) as contents,contents.open(info) as incoming,helper.open("wb") as outgoing: shutil.copyfileobj(incoming,outgoing)
            helper.chmod(0o755)
            if a.version=="0.9.0":
                # The historical helper has a recording JSON-lines protocol; --help is not its contract.
                response=json.loads(run([helper],log,stdin='{"command":"stop"}\n',timeout=5))
                if response.get("event") != "error" or response.get("kind") != "Protocol" or not response.get("message"):
                    raise SystemExit("historical helper did not return its documented protocol error")
                claim="finite genuine stdin JSON recording-protocol probe; accessibility not claimed"
            else:
                claim="CLI accessibility acceptance is performed against the independent GTK fixture"
            identity={"archive":pathlib.Path(a.archive).name,"runnerMachine":machine,"expectedElfMachine":expected_elf,"launcher":launcher_path,"bundledJvm":diagnostic["libjvm.so"][0]["path"],"jar":jar.name,"entry":name,"historicalProbe":claim}
            (root/"native-identity.json").write_text(json.dumps(identity,indent=2)+"\n")

if __name__=="__main__": main()
