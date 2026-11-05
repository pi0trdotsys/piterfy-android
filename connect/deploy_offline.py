"""Install account-scoped browser offline favorites without restarting music services."""
from pathlib import Path
import datetime,re,shutil,subprocess
root=Path('/home/user/piterfy-stack');stage=root/'discover/connectserver';ui=root/'discover/navidrome-ui'
stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S');backup=stage/('before-offline-'+stamp);backup.mkdir()
names=('offline-core.js','offline-sync.js','offline-sw.js','offline.html','offline-player.js','media-kind-core.js')
main=ui/'piterfy.js';caddy=root/'Caddyfile'
targets=[*(ui/n for n in names),main,caddy];existed={p:p.exists() for p in targets}
for p in targets:
    if p.exists():shutil.copyfile(p,backup/p.name)
source=re.sub(r'\n// piterfy-offline-loader\n.*?// piterfy-offline-loader-end\n','\n',main.read_text(),flags=re.S)
source+='\n// piterfy-offline-loader\n;(() => {const core=document.createElement("script");core.src="/piterfy-ui/offline-core.js?v='+stamp+'";core.onload=()=>{const script=document.createElement("script");script.src="/piterfy-ui/offline-sync.js?v='+stamp+'";document.head.append(script);};document.head.append(core);})();\n// piterfy-offline-loader-end\n'
config=caddy.read_text()
if '# piterfy-offline-routes' not in config:
    anchor='\thandle_path /piterfy-ui/* {';assert config.count(anchor)==1
    routes='''\t# piterfy-offline-routes
\thandle /app/piterfy-offline-sw.js {
\t\troot * /srv/piterfy-ui
\t\trewrite * /offline-sw.js
\t\theader Content-Type application/javascript
\t\theader Cache-Control "no-cache"
\t\tfile_server
\t}
\thandle /app/offline/* {
\t\troot * /srv/piterfy-ui
\t\trewrite * /offline.html
\t\theader Cache-Control "no-cache"
\t\tfile_server
\t}
'''
    config=config.replace(anchor,routes+anchor)
config,count=re.subn(r'pfJs\.src="/piterfy-ui/piterfy\.js\?v="[^;]*','pfJs.src="/piterfy-ui/piterfy.js?v="+Math.floor(Date.now()/6e5)+"-'+stamp+'"',config)
assert count==1
try:
    for name in names:shutil.copyfile(stage/name,ui/name)
    main.write_text(source);caddy.write_text(config)
    subprocess.run(['docker','exec','piterfy-caddy','caddy','validate','--config','/etc/caddy/Caddyfile'],check=True)
    subprocess.run(['docker','exec','piterfy-caddy','caddy','reload','--config','/etc/caddy/Caddyfile'],check=True)
except Exception:
    for p in targets:
        if existed[p]:shutil.copyfile(backup/p.name,p)
        else:p.unlink(missing_ok=True)
    subprocess.run(['docker','exec','piterfy-caddy','caddy','reload','--config','/etc/caddy/Caddyfile'],check=False)
    raise
print('Browser offline favorites deployed; backup:',backup)
