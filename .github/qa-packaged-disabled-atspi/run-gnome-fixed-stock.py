"""Invoke the exact patched stock case against the already extracted genuine Linux ZIP."""
from pathlib import Path
import base64, hashlib, json, os, runpy, shutil, subprocess, sys, tempfile, time
import xml.etree.ElementTree as ET
source = Path(os.environ['SMOKE_REPO'])
out = Path(os.environ['SMOKE_ARTIFACTS'])
cli = Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='spectre-stock-actual-') as repo_name:
    repo=Path(repo_name)
    subprocess.run(['git','clone','--quiet','--no-hardlinks',str(source),str(repo)],check=True)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
    assert head == '86f7ca858e4d453b8963c6e1807f940aee8c1e57',head
    data=base64.decodebytes(Path(__file__).with_name('private-consent-actual.patch.b64').read_bytes())
    patch=repo/'.git/verified-stock.patch'
    patch.write_bytes(data)
    assert hashlib.sha256(data).hexdigest() == '16c5f546f99c6ce21f8ce8d3f7ebe529193ed58484024b8a20ae82ae4598ad2c'
    subprocess.run(['git','apply','--index',str(patch)],cwd=repo,check=True)
    tree=subprocess.check_output(['git','write-tree'],cwd=repo,text=True).strip()
    assert tree == '8882ec99fac279a33b8dad29869378203c0ee23a',tree
    print(f'Running immutable stock case at tree {tree}',flush=True)
    sys.path.insert(0,str(repo/'scripts'))
    started=time.monotonic()
    try:
        smoke=runpy.run_path(str(repo/'scripts/release-smoke.py'))
        detail=smoke['_accesskit_cli_smoke'](cli,'0.10.0')
    finally:
        files=list((repo/'cli/build/test-results/test').glob('TEST-*DaemonFixtureIntegrationTest.xml'))
        for p in files: shutil.copyfile(p,out/'corrected-stock-attached.xml')
    assert len(files)==1,files
    suite=ET.parse(files[0]).getroot()
    assert [suite.get(k) for k in ['tests','skipped','errors','failures']]==['1','0','0','0'],suite.attrib
    (out/'corrected-stock-outcome.json').write_text(json.dumps(dict(tree=tree,detail=detail,junit=suite.attrib,elapsed=round(time.monotonic()-started,2)))+'\n')
