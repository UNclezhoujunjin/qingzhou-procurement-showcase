# 本机稳定运行与备份恢复

## 启动与停止

在项目根目录运行 `./start.ps1`，首次会构建前端并执行后端测试。已有构建使用 `./start.ps1 -SkipBuild`。

默认使用稳定模式：一个 Java 服务直接提供构建后的页面和 API，地址统一为 http://127.0.0.1:5173。无需开发服务器持续运行；只监听本机，不对局域网或互联网开放。

`supervise.ps1` 在后台管理 Java 进程，异常退出后自动重试。`./stop.ps1` 会先关闭重启标记，再停止本项目记录的进程，并核对进程启动时间以避免误停其他程序。

代码开发时可以使用 `./start.ps1 -Dev`，仍会分别启动 Vite 5173 与后端 8080。变更代码后应先停止服务，再重新构建；Windows 下不要覆盖运行中的 JAR。

## 登录 Windows 后自动启动

运行 `./install-autostart.ps1` 会在当前用户的 Startup 文件夹中创建 `Qingzhou Procurement.lnk`，仅启动此项目，不修改其他启动项。以后登录 Windows 后会隐藏启动本机站点；请保留项目路径、Java 和 PowerShell 运行环境。

取消自动启动运行 `./uninstall-autostart.ps1`。取消启动项不删除业务数据。关闭电脑时站点不可用；这是本机部署，不是公网云服务。

## 自动和手动备份

使用本机 H2 文件数据库时：

- 每天北京时间 03:00 检查并生成在线一致性备份。
- 应用启动时，如果没有备份或最近一份超过约一天，会补充一份。
- 管理员可在“运行与备份”中立即创建、校验、下载备份与 SHA-256 校验文件。
- 默认存储在 `backend/backups`，不自动删除历史备份；可通过 `app.backup-dir` 改变目录。
- 请定期将备份 ZIP 和同名 `.sha256` 一起复制到另一块磁盘或其他安全位置。仅保存在本机不能防止整块磁盘故障。

备份包含业务数据与密码哈希，仅管理员能通过 API 下载。PostgreSQL 模式不伪造本地备份成功，需要使用该数据库的备份工具。

## 恢复流程

先把备份 ZIP 与同名 `.sha256` 放在同一目录。可以在服务运行时先做无损验证：

```powershell
./restore.ps1 -BackupPath './backend/backups/实际备份文件名.zip' -ValidateOnly
```

验证会检查 SHA-256、解压到独立暂存目录，并以只读方式打开暂存数据库，检查必要业务表；不会覆盖当前数据库。

确定需要回到该备份时间点后：

```powershell
./stop.ps1
./restore.ps1 -BackupPath './backend/backups/实际备份文件名.zip'
./start.ps1 -SkipBuild
```

恢复会保留当前数据库副本到 `.cache/backups/before-restore-*.mv.db`，然后替换固定的 `backend/data/qingzhou.mv.db`。归档中的其他路径不会被解压到业务目录。服务未停止或校验失败时拒绝恢复。

若设置过数据库用户名和密码，验证/恢复时应提供相同的 `DB_USER`、`DB_PASSWORD` 环境变量。本地默认使用 `sa` 和空数据库密码；这与页面登录密码不同。

## 运行排查

- 页面与 API 健康状态：http://127.0.0.1:5173/actuator/health
- 后端日志：`.cache/backend.log`、`.cache/backend-error.log`
- 守护日志：`.cache/supervisor.log`、`.cache/supervisor-error.log`
- 进程记录：`.cache/running.json`
- 数据库：`backend/data/qingzhou.mv.db`

运行日志和数据库包含内部信息，不要提交到公共代码仓库。当前默认部署不需要域名和 HTTPS；以后改为公网访问时，再配置真实域名、证书、生产账号及服务器备份。
