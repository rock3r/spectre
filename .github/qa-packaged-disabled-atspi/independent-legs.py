"""Launch the exact source test or its standalone fixture consumer on a fresh session bus."""
from pathlib import Path
import os
import runpy
import subprocess
import sys

root = Path.cwd()
sys.path.insert(0, str(root / 'scripts'))
smoke = runpy.run_path(str(root / 'scripts/release-smoke.py'))
cli = os.environ['PACKAGED_CLI']
version = os.environ['VERSION_NAME']

def run(command, environment, timeout=600):
    subprocess.run(command, cwd=root, env=environment, check=True, timeout=timeout)

with smoke['_private_daemon_environment']() as daemon, smoke['_private_linux_dbus'](True) as bus:
    env = {**os.environ, **daemon, **bus}
    env.pop('AT_SPI_BUS_ADDRESS', None)
    check_disabled = lambda operation: smoke['_assert_linux_accessibility_disabled'](env, operation)
    check_disabled('before test')
    if sys.argv[1] == 'attached':
        run([str(root/'gradlew'), '--no-daemon', ':cli:test', '--tests',
             '*DaemonFixtureIntegrationTest.packaged CLI and MCP capture*',
             f'-PVERSION_NAME={version}', f'-Dspectre.cli.distributionExecutable={cli}',
             '--console=plain', '--rerun-tasks'], env, 1200)
        check_disabled('after attached captures')
    elif sys.argv[1] == 'standalone':
        manifest = root/'fixtures/accesskit/Cargo.toml'
        run(['cargo', 'build', '--locked', '--manifest-path', str(manifest)], env)
        process = subprocess.Popen([manifest.parent/'target/debug/spectre-accesskit-fixture'],
            cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, start_new_session=True)
        try:
            ready = smoke['_wait_for_accesskit_ready'](process)
            pid = smoke['_accesskit_fixture_pid'](ready)
            print(f'Real standalone AccessKit provider: {pid} {ready}', flush=True)
            check_disabled('fixture startup')
            run([sys.executable, 'scripts/mcp-stdio-smoke.py', '--expected-version', version,
                 '--accessibility-pid', str(pid), '--', cli], env, 150)
            check_disabled('after standalone MCP')
        finally:
            smoke['_terminate_owned_process'](process)
            subprocess.run([cli,'daemon','kill'], cwd=root, env=env, timeout=30, check=False)
    else:
        raise ValueError(sys.argv[1])
