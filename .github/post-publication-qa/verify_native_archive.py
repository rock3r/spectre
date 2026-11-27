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
        files={i.filename:i for i in infos if not i.is_dir()}
        configurations=[i for i in infos if pathlib.PurePosixPath(i.filename).parts[-2:]==("app","spectre.json")]
        if len(configurations)!=1:
            raise SystemExit(f"expected exactly one app/spectre.json, found {len(configurations)}")
        configuration=configurations[0]
        archive_root=pathlib.PurePosixPath(configuration.filename).parent.parent
        try:
            with source.open(configuration) as incoming: app=json.load(incoming)
        except (json.JSONDecodeError,UnicodeDecodeError) as error:
            raise SystemExit(f"invalid Roast configuration {configuration.filename}: {error}") from error
        class_path=app.get("classPath")
        if not isinstance(class_path,list) or not class_path or not all(isinstance(x,str) for x in class_path):
            raise SystemExit(f"invalid classPath in {configuration.filename}")
        class_path_infos=[]
        for relative in class_path:
            relative_path=pathlib.PurePosixPath(relative)
            if relative_path.is_absolute() or ".." in relative_path.parts:
                raise SystemExit(f"unsafe classPath entry in {configuration.filename}: {relative}")
            packaged=(archive_root/relative_path).as_posix()
            if packaged not in files:
                raise SystemExit(f"absent configured classPath entry: {packaged}")
            class_path_infos.append(files[packaged])
        report["roastConfiguration"]={"path":configuration.filename,"root":archive_root.as_posix(),"classPath":[i.filename for i in class_path_infos]}
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
        launchers=inspect("launcher",lambda p:p==archive_root/"spectre")
        inspect("libjvm.so",lambda p:p==archive_root/"runtime/lib/server/libjvm.so")
        inspect("libjava.so",lambda p:p==archive_root/"runtime/lib/libjava.so")
        runtime=[]
        runtime_root=archive_root/"runtime"
        for info in infos:
            path=pathlib.PurePosixPath(info.filename)
            if info.is_dir() or runtime_root not in path.parents: continue
            with source.open(info) as entry: machine=elf_machine(entry.read(20))
            if machine is not None:
                runtime.append({"path":info.filename,"elfMachine":machine,"size":info.file_size})
        report["runtimeNativeElfs"]=runtime
        wrong_runtime=[x for x in runtime if x["elfMachine"]!=expected_elf]
        if not runtime: problems.append("no ELF files found in configured Roast runtime")
        if wrong_runtime:
            problems.append("runtime ELF files with wrong ISA: "+", ".join(f"{x['path']} ({x['elfMachine']})" for x in wrong_runtime))
        helper_arch="x86_64" if expected_elf==62 else "aarch64"
        helper_name=f"native/linux/{helper_arch}/spectre-wayland-helper"
        # Inspect every configured application archive. The helper lives directly in the
        # CLI fat JAR in historical and current Roast distributions; its JAR filename is
        # not part of the packaging contract.
        for jar in class_path_infos:
            with source.open(jar) as incoming, tempfile.NamedTemporaryFile() as copy:
                shutil.copyfileobj(incoming,copy); copy.flush()
                try:
                    with zipfile.ZipFile(copy.name) as contents:
                        for info in contents.infolist():
                            if info.filename==helper_name:
                                with contents.open(info) as entry: machine=elf_machine(entry.read(20))
                                helpers.append({"jar":jar.filename,"path":info.filename,"unixMode":oct(stat.S_IMODE(info.external_attr>>16)),"elfMachine":machine,"size":info.file_size})
                except zipfile.BadZipFile as error:
                    raise SystemExit(f"configured classPath entry is not a ZIP/JAR: {jar.filename}") from error
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
    root=pathlib.Path(a.evidence); root.mkdir(parents=True,exist_ok=True)
    diagnostic=diagnose_archive(a.archive,root,expected_elf)
    if machine != expected_name: raise SystemExit(f"runner machine must be exactly {expected_name}, got {machine}")
    with tempfile.TemporaryDirectory() as temporary:
        unpacked=pathlib.Path(temporary)/"archive"; extract_zip_preserving_unix(a.archive,unpacked)
        launcher_path=diagnostic["launcher"][0]["path"]
        exe=unpacked/launcher_path
        if not exe.is_file() or exe.is_symlink(): raise SystemExit("diagnosed launcher is not a regular extracted file")
        if not os.access(exe,os.X_OK): raise SystemExit("archive spectre path is not executable")
        with (root/"commands.txt").open("w") as log:
            # A poisoned JAVA_HOME and restricted PATH make host-JDK substitution fail. Successful
            # startup therefore exercises the packaged launcher and its diagnosed bundled JVM.
            tools = pathlib.Path(temporary) / "host-tools-without-java"
            tools.mkdir()
            for binary in pathlib.Path("/usr/bin").iterdir():
                if binary.name not in {"java","javaw","javac","jcmd","jshell"} and binary.is_file() and os.access(binary,os.X_OK):
                    (tools/binary.name).symlink_to(binary.resolve())
            isolated_env = os.environ.copy()
            for key in ("JAVA_HOME", "JRE_HOME", "JDK_HOME", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"):
                isolated_env.pop(key,None)
            isolated_env["PATH"] = str(tools)
            assert shutil.which("java", path=isolated_env["PATH"]) is None
            version_text=run([exe,"--version"],log,env=isolated_env)
            if version_text.strip()!=f"spectre {a.version}": raise SystemExit(f"CLI version mismatch: {version_text!r}")
            help_text=run([exe,"--help"],log,env=isolated_env)
            if not help_text.strip(): raise SystemExit("CLI --help output is empty")
            record_help=run([exe,"record","--help"],log,env=isolated_env)
            if not record_help.strip(): raise SystemExit("CLI record --help output is empty")
            if a.version == "0.10.0":
                sockets = pathlib.Path(temporary) / "daemon"
                sockets.mkdir(mode=0o700)
                isolated_env["SPECTRE_DAEMON_SOCKET_DIR"] = str(sockets)
                try:
                    status=json.loads(run([exe,"daemon","status","--json"],log,env=isolated_env,timeout=60))
                    assert status["sessions"] == []
                    expected_java=exe.parent/"runtime/bin/java"
                    expected_jvm=exe.parent/"runtime/lib/server/libjvm.so"
                    processes=[]
                    for proc in pathlib.Path("/proc").glob("[0-9]*"):
                        try:
                            if (proc/"exe").resolve() != expected_java.resolve():
                                continue
                            mappings={row.split()[-1] for row in (proc/"maps").read_text().splitlines() if "libjvm.so" in row}
                            if mappings == {str(expected_jvm.resolve())}:
                                processes.append({"pid":int(proc.name),"exe":str(expected_java.resolve()),"mappedJVM":sorted(mappings)})
                        except (PermissionError,FileNotFoundError,ProcessLookupError):
                            continue
                    assert processes, "no live daemon backed by the extracted runtime and JVM"
                    (root/"bundled-daemon.json").write_text(json.dumps({"javaOnPath":False,"globalJavaHome":None,"processes":processes},indent=2)+"\n")
                finally:
                    run([exe,"daemon","kill"],log,env=isolated_env)
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
