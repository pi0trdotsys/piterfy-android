"""Back up and fix daily HTML mail, then restart only the idle discovery daemon."""
import datetime
import shutil
import subprocess
from pathlib import Path
from patch_mail import patch

root=Path('/home/user/piterfy-stack')
directory=root/'discover'
stage=directory/'connectserver'
source=directory/'discover.py'
backup=directory/'backups'/('mail-fix-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(parents=True)
for name in ('discover.py','report_formatter.py'):shutil.copyfile(directory/name,backup/name)
updated=patch(source.read_text())
try:
    shutil.copyfile(stage/'report_formatter.py',directory/'report_formatter.py')
    source.write_text(updated)
    subprocess.run(['docker','compose','restart','--timeout','15','piterfy-discover'],cwd=root,check=True)
except Exception:
    for name in ('discover.py','report_formatter.py'):shutil.copyfile(backup/name,directory/name)
    subprocess.run(['docker','compose','restart','--timeout','15','piterfy-discover'],cwd=root,check=False)
    raise
print('Daily HTML mail restored; backup:',backup)
