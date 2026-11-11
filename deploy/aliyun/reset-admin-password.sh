#!/usr/bin/env bash
# Local recovery through the server administrator; no public recovery endpoint.
set -euo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash reset-admin-password.sh'; exit 1; }
[[ -f /opt/qingzhou/app.jar ]] || { echo 'Application package not found.'; exit 1; }
trap 'unset password confirm password_hash' EXIT
read -r -s -p 'New website admin password (at least 8 characters): ' password </dev/tty
printf '\n'
read -r -s -p 'Repeat website admin password: ' confirm </dev/tty
printf '\n'
[[ $password == "$confirm" ]] || { echo 'Passwords do not match.'; exit 1; }
password_hash=$(printf '%s\n' "$password" | /usr/bin/java -Xmx96m -Dspring.profiles.active=password-recovery -Dspring.datasource.url=jdbc:unsupported:recovery-only -Dapp.demo=false -jar /opt/qingzhou/app.jar --password-hash-stdin)
unset password confirm
[[ $password_hash =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]] || {
  echo 'Password hashing failed. Install the updated application package first.'; exit 1;
}
systemctl start qingzhou-backup.service
sudo -u postgres psql -X -d qingzhou -v ON_ERROR_STOP=1 -v new_hash="$password_hash" <<'SQL'
BEGIN;
SELECT id AS admin_id FROM app_user WHERE username='admin' AND role='ADMIN' AND enabled=true FOR UPDATE
\gset
UPDATE app_user SET password_hash=:'new_hash',version=version+1,auth_epoch=auth_epoch+1 WHERE id=:'admin_id';
INSERT INTO audit_log(actor_id,action,target,detail)
VALUES (:'admin_id','服务器重置管理员密码','admin','由服务器管理员执行密码恢复，旧登录已失效');
COMMIT;
SQL
unset password_hash
echo 'Admin password reset successfully. Login with admin and the new password.'
