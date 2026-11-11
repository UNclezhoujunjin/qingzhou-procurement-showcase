#!/usr/bin/env bash
# Initial Ubuntu installation only. Does not open public ports or change DNS.
set -euo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash install-server.sh'; exit 1; }
cd /home/admin
[[ -f qingzhou.jar && -f qingzhou.jar.sha256 ]] || { echo 'Upload the JAR and checksum to /home/admin first.'; exit 1; }
sha256sum -c qingzhou.jar.sha256
[[ ! -e /etc/qingzhou/server.env && ! -e /opt/qingzhou/app.jar && ! -e /etc/systemd/system/qingzhou.service ]] || {
  echo 'An installation already exists. Stop here; do not overwrite its configuration.'; exit 1;
}
/usr/bin/java -version
command -v psql >/dev/null
command -v curl >/dev/null
trap 'unset db_password admin_password admin_confirm PGPASSWORD' EXIT
read -r -s -p 'Existing qingzhou database password: ' db_password </dev/tty
printf '\n'
[[ -n $db_password ]] || { echo 'Database password cannot be empty.'; exit 1; }
PGPASSWORD="$db_password" psql -X -h 127.0.0.1 -U qingzhou -d qingzhou -w -v ON_ERROR_STOP=1 -c 'SELECT current_database();'
read -r -s -p 'New website admin password (at least 8 characters): ' admin_password </dev/tty
printf '\n'
read -r -s -p 'Repeat website admin password: ' admin_confirm </dev/tty
printf '\n'
[[ $admin_password == "$admin_confirm" ]] || { echo 'Admin passwords do not match.'; exit 1; }
admin_bytes=$(printf '%s' "$admin_password" | wc -c)
[[ ${#admin_password} -ge 8 && $admin_bytes -le 72 && $admin_password != 'Qingzhou@2026' ]] || {
  echo 'Use a NEW password, at least 8 characters, at most 72 UTF-8 bytes.'; exit 1;
}
if ! id -u qingzhou-svc >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /var/lib/qingzhou --shell /usr/sbin/nologin qingzhou-svc
fi
install -d -m 0755 -o root -g root /opt/qingzhou
install -d -m 0700 -o root -g root /etc/qingzhou
install -d -m 0750 -o qingzhou-svc -g qingzhou-svc /var/lib/qingzhou
install -m 0644 -o root -g root qingzhou.jar /opt/qingzhou/app.jar
# systemd EnvironmentFile double-quote escaping; values are never evaluated as shell code.
write_env() {
  local value=$2
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '%s="%s"\n' "$1" "$value"
}
{
  write_env SPRING_PROFILES_ACTIVE postgres
  write_env DB_URL 'jdbc:postgresql://127.0.0.1:5432/qingzhou'
  write_env DB_USER qingzhou
  write_env DB_PASSWORD "$db_password"
  write_env BOOTSTRAP_ADMIN_PASSWORD "$admin_password"
  write_env COOKIE_SECURE false
  write_env SPRING_DATASOURCE_DRIVER_CLASS_NAME org.postgresql.Driver
  write_env SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 3
  write_env SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE 0
  write_env APP_RUNTIME_MODE server
} > /etc/qingzhou/server.env
chmod 0600 /etc/qingzhou/server.env
unset db_password admin_password admin_confirm
cat > /etc/systemd/system/qingzhou.service <<'UNIT'
[Unit]
Description=Qingzhou procurement application
After=network.target postgresql.service
Wants=postgresql.service
StartLimitIntervalSec=600
StartLimitBurst=3

[Service]
Type=simple
User=qingzhou-svc
Group=qingzhou-svc
WorkingDirectory=/var/lib/qingzhou
EnvironmentFile=/etc/qingzhou/server.env
ExecStart=/usr/bin/java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Dserver.address=127.0.0.1 -Dserver.port=8080 -Dapp.demo=false -jar /opt/qingzhou/app.jar
Restart=on-failure
RestartSec=10
TimeoutStopSec=30
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/qingzhou
MemoryMax=640M

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/qingzhou.service
systemd-analyze verify /etc/systemd/system/qingzhou.service
systemctl daemon-reload
systemctl enable --now qingzhou
for attempt in {1..60}; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null; then
    echo 'Application healthy. Automatic startup enabled. Listening only on 127.0.0.1:8080.'
    echo 'Website account: admin. Use the NEW website password entered above.'
    exit 0
  fi
  if systemctl is-failed --quiet qingzhou; then break; fi
  sleep 2
done
echo 'Health check did not pass yet. Inspect: sudo journalctl -u qingzhou -n 80 --no-pager'
exit 1
