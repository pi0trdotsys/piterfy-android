#!/bin/sh
# Po restarcie serwera: czeka na dysk USB z biblioteką muzyki i dopiero wtedy uruchamia kontenery muzyczne.
# Woluminy /music w docker-compose.yml mają bind.create_host_path: false, więc bez dysku (np. jeszcze niezamontowanego)
# kontener nie wystartuje — zamiast pustej biblioteki, przy której Navidrome oznaczyłby wszystko jako brakujące.
# Wywołanie z crontaba użytkownika:  @reboot ~/piterfy-stack/ops/start-after-usb.sh >> ~/piterfy-stack/ops/start-after-usb.log 2>&1
MOUNT=/srv/media/USB
LIBRARY="$MOUNT/Muzyka"
cd "$(dirname "$0")/.." || exit 1
for i in $(seq 1 90); do                       # do 15 minut (90 × 10 s)
  if mountpoint -q "$MOUNT" && [ -d "$LIBRARY" ]; then
    echo "$(date '+%F %T') dysk gotowy po $((i * 10 - 10)) s — uruchamiam kontenery muzyczne"
    exec docker compose up -d piterfy-navidrome piterfy-discover piterfy-beets
  fi
  sleep 10
done
echo "$(date '+%F %T') dysk USB nie pojawił się w 15 minut — kontenery muzyczne zostają zatrzymane"
exit 1
