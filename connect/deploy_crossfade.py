"""Install web crossfade in the existing Piterfy stack without restarting Navidrome."""
from pathlib import Path
import datetime
import shutil
import re
import subprocess

root = Path('/home/user/piterfy-stack/discover')
stage = root / 'connectserver'
ui = root / 'navidrome-ui'
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
names = ('crossfade-core.js', 'crossfade.js', 'crossfade.css', 'connect.js')
main = ui / 'piterfy.js'
caddy = root.parent / 'Caddyfile'
source = main.read_text(encoding='utf-8')
original = caddy.read_text(encoding='utf-8')
loader = """
// piterfy-crossfade-loader
;(() => {
  const core = document.createElement('script');
  core.src = '/piterfy-ui/crossfade-core.js?v=VERSION';
  core.onload = () => { const ui = document.createElement('script'); ui.src = '/piterfy-ui/crossfade.js?v=VERSION'; document.head.appendChild(ui); };
  document.head.appendChild(core);
})();
// piterfy-crossfade-loader-end
""".replace('VERSION', stamp)
source = re.sub(r'\n// piterfy-crossfade-loader\n.*?// piterfy-crossfade-loader-end\n', '\n', source, flags=re.S) + loader
source, connect_count = re.subn(r's.src = "/piterfy-ui/connect.js\?v[^;]*;', 's.src = "/piterfy-ui/connect.js?v=' + stamp + '";', source)
updated, count = re.subn(r'pfJs\.src="/piterfy-ui/piterfy\.js\?v="[^;]*',
    'pfJs.src="/piterfy-ui/piterfy.js?v="+Math.floor(Date.now()/6e5)+"-' + stamp + '"', original)
assert count == 1 and connect_count == 1, 'Unexpected loader configuration; deployment stopped.'
assert all((stage / name).is_file() for name in names), 'Stage all assets first.'
backup = stage / ('before-crossfade-' + stamp)
backup.mkdir()
targets = [*(ui / name for name in names), main, caddy]
existed = {path: path.exists() for path in targets}
for path in targets:
    if existed[path]:
        shutil.copyfile(path, backup / path.name)
try:
    for name in names:
        shutil.copyfile(stage / name, ui / name)
    main.write_text(source, encoding='utf-8')
    caddy.write_text(updated, encoding='utf-8')  # Preserve the Docker bind-mounted inode.
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True)
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=True)
except Exception:
    for path in targets:
        if existed[path]:
            shutil.copyfile(backup / path.name, path)
        elif path.exists():
            path.unlink()
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=False)
    raise
print('Crossfade deployed; backup:', backup)
