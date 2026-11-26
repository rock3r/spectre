"""Compare the same packaged native capture on isolated and real disposable GNOME buses."""
import contextlib
import json
import shutil
import threading
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys
import time

repo = Path(os.environ['SMOKE_REPO'])
out = Path(os.environ['SMOKE_ARTIFACTS'])
sys.path.insert(0, str(repo/'scripts'))
release = runpy.run_path(str(repo/'scripts/release-smoke.py'))
mcp = runpy.run_path(str(repo/'scripts/mcp-stdio-smoke.py'))
cli = Path(os.environ['QA_EXTRACTED_ROAST_CLI'])
assert cli.is_file()
assert subprocess.check_output([cli,'--version'],text=True).strip() == 'spectre 0.10.0'
assert os.environ.get('SMOKE_HOSTED_FINAL_QA') == '1'
assert os.environ.get('XDG_SESSION_TYPE') == 'wayland'
assert Path(os.environ['SPECTRE_REGION_SESSION_SOCKET']).is_socket()
assert Path('/proc', os.environ['SPECTRE_REGION_SESSION_OWNER_PID']).is_dir()
classpath='/usr/share/java/java-atk-wrapper.jar:'+(out/'attached-fixture-classpath.txt').read_text()
provider=next(Path(p) for p in subprocess.check_output(['dpkg-query','-L','libatk-wrapper-java-jni'],text=True).splitlines() if p.endswith('/libatk-wrapper.so'))
java=Path(os.environ['JAVA_HOME'])/'bin/java'
state_get=release['_linux_accessibility_enabled']
# Keep ordinary host commands available while making a Java PATH fallback impossible.
nojava=out.parent/'spectre-private-path-without-java'
nojava.mkdir(exist_ok=True)
for entry in os.get_exec_path():
    directory=Path(entry)
    if not directory.is_dir(): continue
    for binary in directory.iterdir():
        if binary.name in {'java','javaw','javac','jcmd','jshell'} or not binary.is_file() or not os.access(binary,os.X_OK): continue
        target=nojava/binary.name
        if not target.exists() and not target.is_symlink(): target.symlink_to(binary.resolve())
assert shutil.which('java',path=str(nojava)) is None
def observed_processes(mode, env, fixture_pid, mcp_pid):
    relevant=[]
    key=('SPECTRE_DAEMON_SOCKET_DIR='+env['SPECTRE_DAEMON_SOCKET_DIR']).encode()
    for p in Path('/proc').glob('[0-9]*'):
        try:
            if key not in (p/'environ').read_bytes().split(b'\0'): continue
            if int(p.name)==fixture_pid: continue
            exe=os.readlink(p/'exe')
            argv=(p/'cmdline').read_bytes().split(b'\0')
            maps=[line.split()[-1] for line in (p/'maps').read_text().splitlines() if 'libjvm.so' in line]
            if not maps and 'spectre' not in exe: continue
            relevant.append({'pid':int(p.name),'exe':exe,'vm':sorted(set(maps)),
                             'mcp':int(p.name)==mcp_pid,'daemon':any(b'daemon' in a.lower() for a in argv)})
        except (FileNotFoundError,PermissionError,ProcessLookupError): pass
    return relevant
setcmd=['gdbus','call','--session','--dest','org.a11y.Bus','--object-path','/org/a11y/bus','--method','org.freedesktop.DBus.Properties.Set','org.a11y.Status','IsEnabled']
note='enabled Linux accessibility for this snapshot; restored the previous setting afterwards'

def trace(label, **obj):
    with (out/'actual-attached-gnome.jsonl').open('a') as stream:
        print(json.dumps({'phase':label,**obj}),file=stream,flush=True)

def proof(mode):
    seat = mode == 'seat'
    bus = contextlib.nullcontext({}) if seat else release['_private_linux_dbus'](True)
    with bus as selected, release['_private_daemon_environment']() as sockets:
        env={**os.environ,**selected,**sockets}
        env.pop('AT_SPI_BUS_ADDRESS',None)
        original=state_get(env)
        trace(mode,originalEnabled=original,privateSocketDir=sockets['SPECTRE_DAEMON_SOCKET_DIR'],portalSessionOwner=os.environ['SPECTRE_REGION_SESSION_OWNER_PID'])
        for name in ['org.freedesktop.portal.Desktop','dev.sebastiano.Spectre.WindowIdentity','org.gnome.Mutter.ScreenCast']:
            command=['gdbus','call','--session','--dest','org.freedesktop.DBus','--object-path','/org/freedesktop/DBus','--method','org.freedesktop.DBus.NameHasOwner',name]
            trace(mode,busService=name,hasOwner=subprocess.check_output(command,env=env,text=True).strip())
        app=None
        server=None
        stop=threading.Event()
        sampler=None
        seen={}
        try:
            subprocess.run([*setcmd,'<false>'],env=env,check=True,stdout=subprocess.DEVNULL)
            assert state_get(env) is False
            app_log=out/f'{mode}-attached-fixture.log'
            with app_log.open('w') as stream:
                app=subprocess.Popen([java,'-Djavax.accessibility.assistive_technologies=org.GNOME.Accessibility.AtkWrapper',
                    f'-Djava.library.path={provider.parent}','-XX:+EnableDynamicAgentLoading','-Djava.awt.headless=false',
                    '-Dcompose.application.configure.swing.globals=true','-cp',classpath,
                    'dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt'],cwd=repo/'cli',env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            deadline=time.monotonic()+60
            while 'SPECTRE-FIXTURE-READY' not in app_log.read_text():
                if app.poll() is not None or time.monotonic()>=deadline:
                    raise RuntimeError(f'{mode} fixture failed to start: {app_log.read_text()[-1500:]}')
                time.sleep(.15)
            env=dict(env)
            for variable in ('JAVA_HOME','JDK_HOME','JRE_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
                env.pop(variable,None)
            env['PATH']=str(nojava)
            server=subprocess.Popen([cli,'mcp'],cwd=repo,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(out/f'{mode}-mcp.stderr').open('w'),text=True,bufsize=1,start_new_session=True)
            def sample():
                while not stop.wait(.06):
                    for item in observed_processes(mode,env,app.pid,server.pid):
                        if item['pid'] not in seen or item['vm']:
                            seen[item['pid']]=item
            sampler=threading.Thread(target=sample,daemon=True)
            sampler.start()
            def send(obj):
                server.stdin.write(json.dumps(obj)+'\n')
                server.stdin.flush()
            send({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'spectre-release','version':'1'}}})
            response=mcp['_read_json_line'](server,'initialize')
            assert response['result']['serverInfo']['version']=='0.10.0'
            send({'jsonrpc':'2.0','method':'notifications/initialized'})
            seq=2
            def tool(name,args):
                nonlocal seq
                result=mcp['_tool_call'](server,seq,name,args)
                seq+=1
                trace(mode,tool=name,args=args,result=result,enabledAfter=state_get(env))
                return result
            for allow in [None,False,True]:
                args={'pid':app.pid,'timeout_ms':5000}
                if allow is not None: args['allow_accessibility_enable']=allow
                r=tool('accessibility_tree',args)
                data=json.loads(mcp['_text_content'](r))
                if allow is True:
                    assert data['windows'][0]['title']=='Spectre Agent Test Fixture' and note in data['notes']
                else:
                    assert r.get('isError') and data['code']=='accessibility-disabled'
                assert state_get(env) is False
            attach=tool('attach',{'pid':app.pid})
            session=json.loads(mcp['_text_content'](attach))['sessionId']
            dest=out/f'{mode}-captures'
            dest.mkdir(exist_ok=True)
            actual=[]
            for allow in [False,True]:
                command=[str(cli),'capture',session,'--source','accessibility','--out-dir',str(dest),'--json']
                if allow: command.append('--allow-accessibility-enable')
                r=subprocess.run(command,env=env,cwd=repo,capture_output=True,text=True,timeout=75)
                trace(mode,cli=command,code=r.returncode,stdout=r.stdout,stderr=r.stderr,enabledAfter=state_get(env))
                assert state_get(env) is False
                if not allow: assert r.returncode==5 and 'AccessibilityDisabled' in r.stderr and not r.stdout
                elif r.returncode==0:
                    data=json.loads(r.stdout)
                    mcp['_check_accessibility_artifacts'](data['captureJson'],data['screenshotPng'],expected_title='Spectre Agent Test Fixture',expected_note=note)
                    assert note in r.stderr
                if allow: actual.append(r.returncode==0)
            for allow in [False,True]:
                r=tool('capture',{'session_id':session,'source':'accessibility','out_dir':str(dest),'allow_accessibility_enable':allow})
                data=json.loads(mcp['_text_content'](r)) if not r.get('isError') or not allow else None
                assert state_get(env) is False
                if not allow: assert r.get('isError') and data['code']=='accessibility-disabled'
                elif not r.get('isError'):
                    mcp['_check_accessibility_artifacts'](data['captureJsonPath'],data['screenshotPngPath'],expected_title='Spectre Agent Test Fixture',expected_note=note)
                    assert note in data['notes']
                if allow: actual.append(not bool(r.get('isError')))
            tool('detach',{'session_id':session})
            verified=any(x['daemon'] and x['exe'].startswith(str(cli.parent/'runtime')+'/')
                         and any(m.startswith(str(cli.parent/'runtime')+'/') for m in x['vm']) for x in seen.values())
            assert verified, f'no bundled child daemon JVM in actual process paths: {seen}'
            return all(actual)
        finally:
            stop.set()
            if sampler: sampler.join(timeout=8)
            trace(mode,nativeProcesses=list(seen.values()),javaOnPath=shutil.which('java',path=str(nojava)),
                  afterDaemonModes={str(k.relative_to(cli.parent)):oct(k.stat().st_mode&0o777) for k in
                    (cli.parent/'runtime/bin/java',cli.parent/'runtime/lib/jspawnhelper')})
            if server:
                server.stdin.close()
                try: server.wait(timeout=8)
                except subprocess.TimeoutExpired: release['_terminate_owned_process'](server)
            subprocess.run([cli,'daemon','kill'],cwd=repo,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=25,check=False)
            if app: release['_terminate_owned_process'](app)
            subprocess.run([*setcmd,'<true>' if original else '<false>'],env=env,stdout=subprocess.DEVNULL,check=True)
            trace(mode,restored=state_get(env),original=original)
            assert state_get(env)==original

outcomes={}
for mode in ['split','seat']:
    try:
        outcomes[mode]=proof(mode)
    except Exception as e:
        trace(mode,exception=repr(e))
        outcomes[mode]=False
(out/'actual-attached-gnome-outcomes.json').write_text(json.dumps(outcomes)+'\n')
print(outcomes,flush=True)
if not outcomes['seat']:
    raise SystemExit('actual attached capture failed on the disposable GNOME seat bus; see JSONL')
