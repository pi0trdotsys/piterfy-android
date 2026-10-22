"""Copy staged lyrics assets into the existing Piterfy web extension, with a backup."""
from pathlib import Path
import datetime
import shutil

root = Path('/home/user/piterfy-stack/discover')
stage = root / 'connectserver'
ui = root / 'navidrome-ui'
backup = stage / ('before-lyrics-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir()
for name in ('lyrics-core.js', 'lyrics.js', 'lyrics.css', 'connect.js'):
    if (ui / name).exists():
        shutil.copyfile(ui / name, backup / name)
    shutil.copyfile(stage / name, ui / name)
main = ui / 'piterfy.js'
source = main.read_text(encoding='utf-8')
shutil.copyfile(main, backup / 'piterfy.js')
if '// piterfy-lyrics-loader' not in source:
    source += '''
// piterfy-lyrics-loader
;(() => {
  const version = Math.floor(Date.now()/6e5);
  const core = document.createElement('script');
  core.src = '/piterfy-ui/lyrics-core.js?v=' + version;
  core.onload = () => { const ui = document.createElement('script'); ui.src = '/piterfy-ui/lyrics.js?v=' + version; document.head.appendChild(ui); };
  document.head.appendChild(core);
})();
'''
    main.write_text(source, encoding='utf-8')
print('Lyrics deployed; backup:', backup)
