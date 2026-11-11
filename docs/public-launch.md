# 已安装服务器的域名上线流程

适用：Ubuntu 24.04，qingzhou.service 已健康运行在 127.0.0.1:8080，PostgreSQL 仅监听本机。
目标：https://www.example.com → Nginx → Spring Boot → 本机 PostgreSQL。
ICP 备案号由用户提供：示例备案号（部署时替换）。若阿里云的网站备案号另带 -1 等网站序号，应按控制台的完整网站备案号修正 SiteFiling.vue 后打包。
以下命令由用户在阿里云服务器终端执行。本地尚未连接服务器执行这些操作。

## 1. DNS 与防火墙

在域名实际 DNS 托管平台添加（域名购买平台不一定就是 DNS 托管平台）：

| 类型 | 主机记录 | 记录值 | TTL |
| --- | --- | --- | --- |
| A | www | 203.0.113.10 | 默认 |

若 www 已有 Render CNAME，先将该主机记录改为这条 A 记录；不要保留冲突记录。不要改其他子域名的记录。
若 DNS 在 Cloudflare，先使用“仅 DNS”（灰云），便于直接验证源站和证书；本流程不需要 Cloudflare 代理。
没有服务器 IPv6 地址时，不要给 www 添加 AAAA 记录。

阿里云轻量服务器 → 当前实例 → 防火墙：允许 TCP 80、443。保留现有 SSH 规则。8080、5432 不需要对公网放行。

服务器检查：

```bash
sudo systemctl is-active qingzhou
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
getent ahostsv4 www.example.com
sudo ss -lntp | grep -E ':(80|443|8080|5432)\b'
sudo ufw status
```

若 UFW 已 active，执行 `sudo ufw allow 80/tcp` 与 `sudo ufw allow 443/tcp`。本次不用额外启用 UFW。
健康检查应为 UP，域名应解析为 203.0.113.10。若 80/443 已被其他服务占用，先核对现有配置，不要直接覆盖。

## 2. 更新带备案号的应用

上传最新 qingzhou.jar、qingzhou.jar.sha256 到 /home/admin，然后用已有更新脚本：

```bash
sudo bash /home/admin/update-server.sh
```

本次不运行初次安装脚本。更新脚本保留数据库、密码和原配置。

## 3. 先部署证书验证入口

把 deploy/aliyun/nginx-http.conf、nginx-https.conf 上传到 /home/admin。
下面采用 Ubuntu 软件源的 Certbot；不要同时混用 snap 安装的另一套 Certbot。

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot
sudo install -d -m 755 /var/www/qingzhou-acme/.well-known/acme-challenge
```

如果 /etc/nginx/sites-available/qingzhou 已存在，先查看并备份，不要直接覆盖已经运行的 HTTPS 配置。首次配置执行：

```bash
sudo install -m 644 /home/admin/nginx-http.conf /etc/nginx/sites-available/qingzhou
sudo ln -s /etc/nginx/sites-available/qingzhou /etc/nginx/sites-enabled/qingzhou
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
```

临时入口只用于证书验证，其他请求返回 503。不要在这个阶段通过公网 HTTP 输入密码。

## 4. 申请证书并启用 HTTPS

```bash
sudo certbot certonly --webroot -w /var/www/qingzhou-acme --cert-name www.example.com -d www.example.com
```

按提示填自己常用邮箱、阅读并选择是否同意服务条款。成功后确认显示的证书目录为 /etc/letsencrypt/live/www.example.com/。
失败时先检查 DNS、80 端口、冲突的 AAAA 记录和域名接入状态；不要反复盲目申请。成功后继续：

```bash
sudo cp -a /etc/nginx/sites-available/qingzhou /etc/nginx/sites-available/qingzhou.before-https
sudo install -m 644 /home/admin/nginx-https.conf /etc/nginx/sites-available/qingzhou
sudo nginx -t
```

只有 nginx -t 成功才继续 reload；否则恢复 qingzhou.before-https，重新检查配置。

```bash
sudo systemctl reload nginx
curl --fail --show-error --resolve www.example.com:443:127.0.0.1 https://www.example.com/ -o /dev/null
```

HTTPS 配置还会强制应用会话 Cookie 使用 Secure，覆盖可信反向代理头，并隐藏公网健康检查接口。
应用本身的 127.0.0.1:8080 与数据库保持原状，原有本地 SSH 隧道仍可用于维护。

## 5. 自动续期与验收

webroot 续期需要证书更新后重载 Nginx，因此安装 deploy hook：

```bash
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nset -eu\n/usr/sbin/nginx -t\n/bin/systemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/qingzhou-nginx-reload >/dev/null
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/qingzhou-nginx-reload
sudo systemctl enable --now certbot.timer
sudo certbot renew --dry-run --run-deploy-hooks
sudo systemctl list-timers certbot.timer
```

浏览器访问 https://www.example.com，确认 HTTPS 无警告，登录成功、刷新后仍登录、退出成功，页面底部 ICP 链接可点开。
再确认 http://www.example.com 自动跳转 HTTPS；关闭电脑后用手机流量访问，确认不依赖 SSH 隧道。
本地健康检查仍可用：curl http://127.0.0.1:8080/actuator/health。
若发布失败，提供 nginx -t、systemctl status nginx 和相关错误输出，不要提供私钥、密码或 server.env 文件内容。

公安备案应在网站开通之日起 30 日内提交，通过后再添加真实公安备案号和官方图标链接。

参考：[阿里云域名解析](https://help.aliyun.com/zh/dns/pubz-add-website-parsing)、[Nginx 反向代理](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)、[Certbot webroot 与续期](https://eff-certbot.readthedocs.io/en/stable/using.html)。
