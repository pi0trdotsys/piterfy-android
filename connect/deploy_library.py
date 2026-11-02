"""Deploy shared discovery/podcast features to this Piterfy stack, with backups and rollback."""
from pathlib import Path
import datetime
import re
import shutil
import subprocess

root = Path('/home/user/piterfy-stack')
stage = root / 'discover/connectserver'
ui = root / 'discover/navidrome-ui'
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup = stage / ('before-library-' + stamp)
backup.mkdir()
caddy = root / 'Caddyfile'
main = ui / 'piterfy.js'
targets = [(stage / 'connect_api.py', stage / 'library-connect_api.py'),
           (stage / 'library_core.py', stage / 'library-core-staged.py'),
           (ui / 'library.js', stage / 'library.js'), (ui / 'library.css', stage / 'library.css'),
           (ui / 'experience.css', stage / 'experience.css')]
for _, source in targets:
    assert source.is_file(), 'Missing staged asset: ' + source.name
existed = {target: target.exists() for target, _ in targets}
restart_api = any(not target.exists() or target.read_bytes() != asset.read_bytes() for target, asset in targets[:2])
for target, _ in targets:
    if target.exists():
        shutil.copyfile(target, backup / target.name)
for target in (caddy, main):
    shutil.copyfile(target, backup / target.name)
source = main.read_text('utf-8')
source = source.replace('const chips = [["Wszystko", HOME, true],',
    'const chips = [["Muzyka", HOME, true], ["Podcasty", "#/podcasty"], ["Radia", "#/radio"],')
source = source.replace('["Ulubione", "#/ulubione"], ["Podcasty", "#/podcasty"]];',
    '["Ulubione", "#/ulubione"]];')
source = re.sub(r'\n// piterfy-library-loader\n.*?// piterfy-library-loader-end\n', '\n', source, flags=re.S)
source += "\n// piterfy-library-loader\n;(() => { const s = document.createElement('script'); s.src = '/piterfy-ui/library.js?v=" + stamp + "'; document.head.appendChild(s); })();\n// piterfy-library-loader-end\n"
old = 'return songRecords(pool.slice(0, count));'
if old in source:
    assert source.count(old) == 1
    source = source.replace(old, "const ranked = window.PiterfyLibrary ? await window.PiterfyLibrary.rankSongs(pool).catch(() => pool) : pool;\n    return songRecords(ranked.slice(0, count));")
original = caddy.read_text('utf-8')
updated, count = re.subn(r'pfJs\.src="/piterfy-ui/piterfy\.js\?v="[^;]*',
    'pfJs.src="/piterfy-ui/piterfy.js?v="+Math.floor(Date.now()/6e5)+"-' + stamp + '"', original)
assert count == 1
try:
    for target, asset in targets:
        shutil.copyfile(asset, target)
    main.write_text(source, 'utf-8')
    caddy.write_text(updated, 'utf-8')
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True)
    if restart_api:
        subprocess.run(['docker', 'restart', '--timeout', '2', 'piterfy-connect'], check=True)
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=True)
except Exception:
    for target, _ in targets:
        if existed[target]:
            shutil.copyfile(backup / target.name, target)
        else:
            target.unlink(missing_ok=True)
    for target in (caddy, main):
        shutil.copyfile(backup / target.name, target)
    if restart_api:
        subprocess.run(['docker', 'restart', '--timeout', '2', 'piterfy-connect'], check=False)
    subprocess.run(['docker', 'exec', 'piterfy-caddy', 'caddy', 'reload', '--config', '/etc/caddy/Caddyfile'], check=False)
    raise
print('Shared library deployed; backup:', backup)
