#!/bin/sh
set -eu

: "${DISK_ALERT_TO:?Set the recipient of the disk alert}"
threshold=${DISK_ALERT_THRESHOLD:-80}
path=${DISK_ALERT_PATH:-/}
usage=$(df --output=pcent "$path" | tail -1 | tr -dc '0-9')

if [ "$usage" -ge "$threshold" ]; then
    printf 'To: %s\nSubject: Piterfy: zajętość dysku %s%%\n\n%s\n' \
        "$DISK_ALERT_TO" "$usage" "$(df -h "$path")" |
        msmtp -a "${DISK_ALERT_ACCOUNT:-gmail}" "$DISK_ALERT_TO"
fi
