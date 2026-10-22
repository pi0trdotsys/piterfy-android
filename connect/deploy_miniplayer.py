"""Install the optional web mini player without restarting the music server."""
from pathlib import Path
import datetime
import shutil
import re
import subprocess

root = Path('/home/user/piterfy-stack/discover')
stage = root / 'connectserver'
ui = root / 'navidrome-ui'
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup = stage / ('before-miniplayer-' + stamp)
backup.mkdir()
for name in ('miniplayer.js', 'miniplayer.css', 'connect.js'):
    if (ui / name).exists():
        shutil.copyfile(ui / name, backup / name)
    shutil.copyfile(stage / name, ui / name)
main = ui / 'piterfy.js'
source = main.read_text(encoding='utf-8')
shutil.copyfile(main, backup / 'piterfy.js')
if '// piterfy-miniplayer-loader' not in source:
    source += '''
// piterfy-miniplayer-loader
;(() => { const s = document.createElement('script'); s.src = '/piterfy-ui/miniplayer.js?v=' + Math.floor(Date.now()/6e5); document.head.appendChild(s); })();
'''
source = re.sub(r"s.src = '/piterfy-ui/miniplayer.js\?v[^;]*;", "s.src = '/piterfy-ui/miniplayer.js?v=" + stamp + "';", source)
source = re.sub(r's.src = "/piterfy-ui/connect.js\?v[^;]*;', 's.src = "/piterfy-ui/connect.js?v=' + stamp + '";', source)
main.write_text(source, encoding='utf-8')
# Force the new loader through the CDN cache on the next page refresh.
caddy = root.parent / 'Caddyfile'
original = caddy.read_text(encoding='utf-8')
shutil.copyfile(caddy, backup / 'Caddyfile')
updated, count = re.subn(r'pfJs\.src="/piterfy-ui/piterfy\.js\?v="[^;]*',
    'pfJs.src="/piterfy-ui/piterfy.js?v="+Math.floor(Date.now()/6e5)+"-' + stamp + '"', original)
assert count == 1, 'Unexpected loader configuration; inspect Caddyfile before deployment.'
caddy.write_text(updated, encoding='utf-8')  # Preserve the Docker bind-mounted inode.
try:
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True)
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=True)
except subprocess.CalledProcessError:
    caddy.write_text(original, encoding='utf-8')
    raise
print('Mini player deployed; backup:', backup)
