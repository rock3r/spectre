"""Read-only trace of actual packaged responses in the temporary integration runner."""
import json
import os
import sys

if sys.argv[0].endswith('mcp-stdio-smoke.py'):
    def observe(frame, event, result):
        if event != 'return':
            return
        name = frame.f_code.co_name
        if name == '_tool_call' and frame.f_code.co_filename.endswith('mcp-stdio-smoke.py'):
            obj = dict(tool=frame.f_locals.get('name'), arguments=frame.f_locals.get('arguments'), result=result)
        elif name == 'run' and frame.f_code.co_filename.endswith('subprocess.py') and hasattr(result, 'args'):
            obj = dict(command=result.args, exit=result.returncode, stdout=result.stdout, stderr=result.stderr)
        else:
            return
        with open(os.environ['QA_RESPONSE_LOG'], 'a') as stream:
            print(json.dumps(obj, default=str), file=stream)
    sys.setprofile(observe)
