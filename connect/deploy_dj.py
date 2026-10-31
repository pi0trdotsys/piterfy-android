"""Install a separate bounded worker without restarting the daily discovery daemon."""
import json
import os
from pathlib import Path
import subprocess
import datetime
import shutil
import re
import sys

root = Path('/home/user/piterfy-stack')
stage = root/'discover/connectserver'
config = json.loads(subprocess.check_output(['docker','inspect','piterfy-discover']))[0]
allowed = ('NAVIDROME_', 'SLSKD_', 'LASTFM_')
env = [entry for entry in config['Config']['Env'] if entry.split('=',1)[0].startswith(allowed)]
private = root/'.dj-runtime.env'
private.touch(mode=0o600, exist_ok=True); os.chmod(private, 0o600)
# JSON strings in env files need raw parsing; use a private compose JSON file instead.
environment = dict(entry.split('=',1) for entry in env)
environment.update(CONNECT_STATE_DIR='/connect', DJ_STATE_DIR='/state/dj',DJ_VOICE_ENGINE=os.environ.get('DJ_VOICE_ENGINE','multilingual'))
service = {'services': {'piterfy-dj': {
    'build': {'context':'./discover/connectserver','dockerfile':'Dockerfile.dj'},
    'container_name':'piterfy-dj','restart':'unless-stopped','networks':['piterfy_net'],
    'user':str(os.getuid())+':'+str(os.getgid()),
    'environment':environment,
    'volumes':['./discover:/legacy:ro','./discover/dj-state:/state/dj','./discover/state/connect:/connect:ro'],
    'read_only':True,'tmpfs':['/tmp:size=64m'], 'cpus':0.75,'mem_limit':'512m','pids_limit':64,
    'security_opt':['no-new-privileges:true'],'cap_drop':['ALL']}}}
private.write_text(json.dumps(service))
(root/'discover/dj-state').mkdir(parents=True, exist_ok=True)
command=['docker','compose','-f','docker-compose.yml','-f','docker-compose.override.yml','-f',private.name]
if '--ui-only' not in sys.argv:
    subprocess.run(command+['up','-d','--no-deps','--build','piterfy-dj'],cwd=root,check=True)
    print('DJ worker built and started; no existing music service restarted.')
subprocess.run(['docker','exec','piterfy-connect','python','-c',
    'import urllib.request,json; r=urllib.request.Request("http://piterfy-dj:8092",data=json.dumps({"user":"health","request":{"action":"list"}}).encode()); assert "dj" in json.load(urllib.request.urlopen(r,timeout=8))'],check=True)
if (stage/'dj.js').exists():
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    ui=root/'discover/navidrome-ui';backup=stage/('before-dj-'+stamp);backup.mkdir()
    targets=[ui/name for name in ('connect.js','dj.js','piterfy.js')]
    existed={target:target.exists() for target in targets}
    for target in targets:
        if target.exists():shutil.copyfile(target,backup/target.name)
    try:
        for name in ('dj.js','connect.js'):shutil.copyfile(stage/name,ui/name)
        main=ui/'piterfy.js';source=main.read_text()
        source,count=re.subn(r's\.src\s*=\s*"/piterfy-ui/connect\.js[^;]*;',
            's.src = "/piterfy-ui/connect.js?v='+stamp+'";',source)
        assert count==1, 'Connect loader not found'
        main.write_text(source)
        subprocess.run(['python3',str(stage/'deploy_library.py')],check=True)
    except Exception:
        for target in targets:
            if existed[target]:shutil.copyfile(backup/target.name,target)
            else:target.unlink(missing_ok=True)
        raise
    print('DJ UI and authenticated API deployed; backup:',backup)
