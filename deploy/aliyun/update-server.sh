#!/usr/bin/env bash
# Replace application only; preserve environment, database, backup jobs and DNS.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash update-server.sh'; exit 1; }
cd /home/admin
sha256sum -c qingzhou.jar.sha256
[[ -f /opt/qingzhou/app.jar && -f /etc/qingzhou/server.env ]] || {
  echo 'Existing server installation not found.'; exit 1;
}
if cmp -s qingzhou.jar /opt/qingzhou/app.jar; then
  echo 'This package is already installed.'; exit 0;
fi
# Database migrations may run on startup. Require a fresh PostgreSQL backup first.
systemctl start qingzhou-backup.service
echo 'Pre-update database backup completed.'
install -d -m 0700 /opt/qingzhou/releases
previous="/opt/qingzhou/releases/app-$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}.jar"
install -m 0600 /opt/qingzhou/app.jar "$previous"
install -m 0644 qingzhou.jar /opt/qingzhou/app.jar.next
changing=0
complete=0
rollback() {
  local result=$?
  if [[ $changing == 1 && $complete == 0 ]]; then
    echo 'Update failed. Restoring previous application package.'
    install -m 0644 "$previous" /opt/qingzhou/app.jar
    systemctl restart qingzhou || true
    echo 'Inspect: sudo journalctl -u qingzhou -n 80 --no-pager'
  fi
  return "$result"
}
trap rollback EXIT
changing=1
systemctl stop qingzhou
mv /opt/qingzhou/app.jar.next /opt/qingzhou/app.jar
systemctl reset-failed qingzhou
systemctl start qingzhou
for attempt in {1..60}; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null; then
    complete=1
    echo 'Update healthy. Database and private configuration preserved.'
    echo "Previous package: $previous"
    exit 0
  fi
  if systemctl is-failed --quiet qingzhou; then break; fi
  sleep 2
done
exit 1
