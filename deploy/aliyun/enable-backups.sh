#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash enable-backups.sh'; exit 1; }
command -v pg_dump >/dev/null
command -v pg_restore >/dev/null
command -v flock >/dev/null
install -d -o postgres -g postgres -m 0700 /var/backups/qingzhou
install -d -o root -g root -m 0755 /usr/local/libexec/qingzhou
umask 022
cat > /usr/local/libexec/qingzhou/backup-postgres.sh <<'BACKUP'
#!/usr/bin/env bash
set -euo pipefail
umask 077
backup_dir=/var/backups/qingzhou
cd "$backup_dir"
exec 9> .automatic-backup.lock
flock -n 9 || { echo 'Another automatic backup is running.'; exit 1; }
name="auto-qingzhou-$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}.dump"
temporary=$(mktemp "$backup_dir/.automatic-XXXXXXXX.partial")
trap 'rm -f -- "$temporary"' EXIT
pg_dump --no-password --format=custom --dbname=qingzhou --file="$temporary"
pg_restore --list "$temporary" >/dev/null
mv -- "$temporary" "$backup_dir/$name"
sha256sum "$name" > "$name.sha256"
sha256sum --check "$name.sha256"
echo "Backup completed: $backup_dir/$name"
# Prune only this job's completed, timestamped backups, after a new successful backup.
# Manually created qingzhou-*.dump files are deliberately outside this pattern.
find "$backup_dir" -regextype posix-extended -maxdepth 1 -type f \
  -regex '/var/backups/qingzhou/auto-qingzhou-[0-9]{8}T[0-9]{6}Z-[0-9]+\.dump(\.sha256)?' \
  -mmin +20160 -print -delete
BACKUP
chmod 0755 /usr/local/libexec/qingzhou/backup-postgres.sh
bash -n /usr/local/libexec/qingzhou/backup-postgres.sh
cat > /etc/systemd/system/qingzhou-backup.service <<'SERVICE'
[Unit]
Description=Qingzhou PostgreSQL daily local backup
After=postgresql.service

[Service]
Type=oneshot
User=postgres
Group=postgres
ExecStart=/usr/local/libexec/qingzhou/backup-postgres.sh
UMask=0077
TimeoutStartSec=30min
Nice=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/backups/qingzhou
SERVICE
cat > /etc/systemd/system/qingzhou-backup.timer <<'TIMER'
[Unit]
Description=Qingzhou backup at 03:00 Asia/Shanghai

[Timer]
OnCalendar=*-*-* 03:00:00 Asia/Shanghai
Persistent=true
AccuracySec=1min
Unit=qingzhou-backup.service

[Install]
WantedBy=timers.target
TIMER
systemd-analyze calendar '*-*-* 03:00:00 Asia/Shanghai'
systemd-analyze verify /etc/systemd/system/qingzhou-backup.service /etc/systemd/system/qingzhou-backup.timer
systemctl daemon-reload
# Verify the backup job before enabling its schedule.
systemctl start qingzhou-backup.service
systemctl enable --now qingzhou-backup.timer
systemctl list-timers qingzhou-backup.timer --no-pager
journalctl -u qingzhou-backup.service -n 12 --no-pager
echo 'Daily local backup enabled. Retention: 14 days for automatic backups.'
echo 'This is same-server storage. Keep an additional secure off-server copy.'
