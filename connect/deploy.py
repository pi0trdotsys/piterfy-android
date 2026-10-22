"""Run on the Piterfy host after copying server/ and web/ into discover/connectserver/.
Backs up modified integration files; never restarts Navidrome or discover.
"""
from pathlib import Path
import datetime
import shutil
import subprocess

root = Path('/home/user/piterfy-stack')
stage = root / 'discover/connectserver'
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup = stage / ('before-' + stamp)
backup.mkdir()
override = root / 'docker-compose.override.yml'
if override.exists() and 'piterfy-connect:' not in override.read_text():
    raise SystemExit('Existing compose override needs manual merge.')
if not override.exists():
    shutil.copyfile(stage / 'compose.service.yml', override)
subprocess.run(['docker', 'compose', 'config', '--quiet'], cwd=root, check=True)
subprocess.run(['docker', 'compose', 'up', '-d', '--no-deps', 'piterfy-connect'], cwd=root, check=True)
subprocess.run(['docker', 'exec', 'piterfy-connect', 'python', '-c',
    'import urllib.request; print(urllib.request.urlopen("http://localhost:8091/health").read().decode())'], check=True)

caddy = root / 'Caddyfile'
original = caddy.read_text()
shutil.copyfile(caddy, backup / 'Caddyfile')
if 'handle /piterfy-api/connect/*' not in original:
    anchor = '\thandle /piterfy-api/* {'
    assert original.count(anchor) == 1
    caddy.write_text(original.replace(anchor, '\thandle /piterfy-api/connect/* {\n\t\treverse_proxy piterfy-connect:8091\n\t}\n\n' + anchor))
try:
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True)
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=True)
except subprocess.CalledProcessError:
    caddy.write_text(original)
    raise

ui = root / 'discover/navidrome-ui'
for name in ('connect.js', 'connect.css'):
    if (ui / name).exists():
        shutil.copyfile(ui / name, backup / name)
    shutil.copyfile(stage / name, ui / name)
main = ui / 'piterfy.js'
source = main.read_text()
shutil.copyfile(main, backup / 'piterfy.js')
if 'piterfy-connect-loader' not in source:
    source += '\n// piterfy-connect-loader\n;(() => { const s = document.createElement("script"); s.src = "/piterfy-ui/connect.js?v=" + Math.floor(Date.now()/6e5); document.head.appendChild(s); })();\n'
    main.write_text(source)
print('Connect deployed; backup:', backup)
