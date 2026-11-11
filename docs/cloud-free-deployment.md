# 免费云部署：example.com

当前确定的方案：保留 Java，使用 Render 免费 Web Service + Neon 免费 PostgreSQL，Cloudflare 管理域名入口。目标子域名为 `erp.example.com`，以免占用现有根域名。账号仍由本人登录授权，不把密码、API Token、数据库连接密钥写入 GitHub。

**当前是部署准备文件，不表示网站已经上线。** `render.yaml` 不会在本机自动创建任何云资源，也不会自动改 DNS 或上传本地数据库。

## 免费范围与取舍

- Render 选择 Free Web Service，闲置约 15 分钟后会休眠，下次访问可能等待约一分钟。电脑关机不影响云端访问，但免费额度、平台故障和休眠仍可能影响服务。
- 不创建 Render 免费 PostgreSQL，它有到期限制；数据库使用 Neon Free。
- Render 本地磁盘不是持久化数据库，不把 H2 文件或本地备份 ZIP 放到云容器中作为正式数据。
- 免费额度和平台政策会变化；不要选择收费实例、付费磁盘、付费 Cron 或升级账单。达到免费限额时应先查看控制台，不默认开通付费。
- Java 会话保存在应用内存，休眠后恢复、重新部署或重启可能要求重新登录。业务数据保存在独立 PostgreSQL。

官方说明：

- https://render.com/docs/free
- https://neon.com/docs/introduction/plans
- https://render.com/docs/configure-cloudflare-dns

## 已准备的文件

- 根目录 `Dockerfile`：构建 Vue 页面，将静态文件装入 Java JAR，用一个同源网站提供页面和 API，避免跨站 Cookie 和 CORS 配置。
- 根目录 `.dockerignore`：排除本地数据库、备份、密钥、环境变量和开发缓存。
- `render.yaml`：仅声明一个 `plan: free` 服务，不声明付费资源，也不声明 Render 数据库。
- `application-cloud.yml`：PostgreSQL、加密 Cookie、代理 HTTPS 识别、小连接池与端口设置。
- `CloudSafetyConfig`：云端拒绝 H2、无 TLS 数据库、公开演示密码和演示模式。
- `.env.cloud.example`：环境变量名称示例，不含真实凭据，也不会自动加载。

本地运行方式保留，默认仍可使用 `start.ps1`。

## 1. GitHub 私有仓库

在 GitHub 账号 `UNclezhoujunjin` 下创建或选定一个私有仓库，把项目源代码提交进去。建议仓库名称为 `qingzhou-procurement`，这是建议名称，不表示仓库已经创建。

上传前确认：

- `backend/data`、`backend/backups`、`.cache`、`.env`、`.env.*` 中真实配置均不上传。
- `.env.example` 与 `.env.cloud.example` 只是无凭据模板，可以上传。
- 不将 Cloudflare 账号密码、GitHub Token、Neon 密码或管理员新密码放入代码、提交记录或聊天。
- 给 Render 的 GitHub 授权优先限定到此仓库。

## 2. 创建 Neon 免费数据库

使用 GitHub 登录 https://console.neon.tech，选择 Free，创建独立 PostgreSQL 项目。地区尽量靠近 Render 服务；Render 模板为 Singapore，若 Neon 没有对应地区，可选邻近地区或一致调整两边。

在 Connect 页面选 Java，取得 JDBC 主机、数据库名、用户和密码。迁移/Flyway 推荐直接连接主机，先不要使用带 `-pooler` 的事务池连接地址。

在 Render 私密环境变量中分开填写：

| 名称 | 填写方式 |
| --- | --- |
| DB_URL | `jdbc:postgresql://实际主机/实际数据库?sslmode=verify-full`，不在 URL 中拼入密码 |
| DB_USER | Neon 给出的数据库用户名 |
| DB_PASSWORD | Neon 数据库密码，仅在 Render 私密环境变量填写 |
| BOOTSTRAP_ADMIN_PASSWORD | 自己设置至少 8 字符、最多 72 UTF-8 字节的新密码；不能使用演示密码 |

首次在空库启动只会创建 `admin`，不创建演示账号和演示业务数据。管理员初始密码变量只在没有用户时生效，修改已有密码请通过个人中心操作。

## 3. 先决定业务数据是否迁移

部署模板**不会自动把本机业务数据上传到 Neon**。上线前应明确是新建空白云端库，还是迁移当前库存、单据和成员。

迁移现有数据时应先创建并验证本地备份，再进行表结构、主外键、身份序列和数量核对，并处理公开演示账号。不要直接把 H2 SQL 导入 PostgreSQL，不要把本地备份上传到 GitHub。首次数据迁移需要另行执行和核验，不用“启动应用”代替数据迁移。

## 4. Render 免费部署

使用同一 GitHub 账号登录 https://dashboard.render.com，选择 New → Blueprint，连接此项目仓库，使用根目录 `render.yaml`。确认服务类型 Web Service、运行方式 Docker、计划 Free。

创建时填写上面的四个私密环境变量。模板已设置 `SPRING_PROFILES_ACTIVE=cloud`，不需要填写服务器 IP，也不要填写本机的 `127.0.0.1` 作为云端地址。

如果手动创建 Web Service：Dockerfile Path 为 `./Dockerfile`、构建上下文为仓库根目录，健康检查 `/actuator/health`，计划必须选 Free。Render 自动提供 PORT，Java 监听 0.0.0.0。

先用 Render 实际分配的 HTTPS 地址验证：页面、管理员登录、采购、库存、通知、重启后数据仍在。不要把文档里的示例地址当成已创建地址。

## 5. 接入 Cloudflare 与阿里云域名

若 `example.com` 尚未接入 Cloudflare：

1. 在 Cloudflare 添加已有域名，选择免费的域名/DNS计划。
2. 对照阿里云原解析记录检查 A、AAAA、CNAME、MX、TXT，保留已有网站和邮箱记录；自动扫描不保证完整。
3. 到阿里云把域名 DNS 服务器改成 Cloudflare **实际分配给此域名的** nameservers，无需转移域名注册商。
4. 等 Cloudflare 显示域名 Active。

网站子域名：

1. 在 Render 为服务添加 Custom Domain：`erp.example.com`。
2. 在 Cloudflare 为 `erp` 创建 CNAME，目标填写 Render **实际服务**的 `*.onrender.com` 主机名。
3. 初次验证保持 DNS only，先完成 Render 域名验证和证书签发。只处理 `erp` 同名冲突记录，不删除根域名、邮箱或其他子域名记录。
4. 证书有效后如需启用 Cloudflare 代理，再启用 Proxied，使用 Full (strict)；不要用 Flexible。
5. 不为 `/api/*` 或登录页面配置 Cache Everything，也不要缓存带会话的业务响应。

此文档仅提供接入顺序，当前尚未对任何实际 DNS 记录执行修改。

## 云端备份

本机 H2 的 ZIP 定时备份与 restore.ps1 不适用于 Neon。云端页面会提示在数据库控制台管理恢复窗口和导出。免费计划的恢复窗口有限，不把它当成永久离线备份；正式使用前应设置并验证 PostgreSQL 导出/恢复流程。

## 上线验收

- 域名 HTTPS 证书有效，页面和 API 都从同一域名访问。
- 页面不展示演示账号，公开演示密码无法登录；生产管理员使用新密码。
- PostgreSQL 数据库真实连接成功，Flyway 迁移通过，采购与库存测试通过。
- 站点重新部署后业务数据仍保留；重新登录属于当前会话设计的预期行为。
- 现有阿里云网站和邮箱记录未受影响。
- 控制台仍显示 Free，未创建付费资源；备份和数据迁移状态已明确。

当前本机没有可用的 Docker 构建环境，尚未实际运行云端镜像。上线完成只能以 Render 实际部署状态与域名访问验证为准。
