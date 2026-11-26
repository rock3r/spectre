"""Read-only trace of actual packaged responses in the temporary integration runner."""
import json
import os
import sys
import shutil
from pathlib import Path

if 'QA_RESPONSE_LOG' in os.environ and (sys.argv[0].endswith('mcp-stdio-smoke.py') or sys.argv[0] == '-'):
    if '--attach-pid' in sys.argv:
        pid = sys.argv[sys.argv.index('--attach-pid')+1]
        target = Path('/proc') / pid
        output = Path(os.environ['QA_RESPONSE_LOG']).parent
        command = (target/'cmdline').read_bytes().split(b'\x00')
        (output/'fixture-command.json').write_text(json.dumps(dict(
            cwd=os.readlink(target/'cwd'), argv=[os.fsdecode(v) for v in command if v])))
    def observe(frame, event, result):
        if event == 'call' and frame.f_code.co_name == '_check_accessibility_artifacts':
            output = Path(os.environ['QA_RESPONSE_LOG']).parent
            for key in ('tree_path', 'png_path'):
                source = Path(frame.f_locals[key])
                if source.is_file():
                    shutil.copyfile(source, output / f'{source.parent.name}-{source.name}')
        if event != 'return':
            return
        name = frame.f_code.co_name
        if name == '_tool_call' and frame.f_code.co_filename.endswith('mcp-stdio-smoke.py'):
            obj = dict(tool=frame.f_locals.get('name'), arguments=frame.f_locals.get('arguments'), result=result)
        elif name == 'run' and frame.f_code.co_filename.endswith('subprocess.py') and hasattr(result, 'args'):
            if not isinstance(result.args, (list,tuple)) or Path(result.args[0]).name not in {'spectre','gdbus'}:
                return
            obj = dict(command=result.args, exit=result.returncode, stdout=result.stdout, stderr=result.stderr)
        else:
            return
        with open(os.environ['QA_RESPONSE_LOG'], 'a') as stream:
            print(json.dumps(obj, default=str), file=stream)
    sys.setprofile(observe)
