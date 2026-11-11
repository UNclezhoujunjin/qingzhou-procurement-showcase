# 轻舟 · 企业采购与库存协同平台

一个可以在本地直接运行的全栈项目：Vue 3 + TypeScript + Spring Boot + Spring Security + JDBC + Flyway。围绕真实业务实现申请、审批、下单、入库、领用及审计。

当前是**可运行的单组织项目**，已补齐五项业务与本机稳定运行功能。默认使用文件型 H2 数据库，数据在重启后保留；同时提供 PostgreSQL、Docker Compose 和 CI 配置。PostgreSQL 与容器启动尚未在此环境验证。

项目展示与 AI 实测结果见 [轻舟项目说明](docs/qingzhou-portfolio.md)。

## 快速开始（Windows）

需要 Java 17+、Maven 3.6.3+、Node.js 22（或满足 Vite 6 要求的版本）以及 npm。

在项目根目录运行：

```powershell
.\start.ps1
```

脚本会安装前端依赖、执行前端构建与后端集成测试，然后在后台启动稳定模式：Java 同时提供构建后的页面和 API，并由守护进程在异常退出后重启。日志在 `.cache/`，不会弹出额外窗口。

- 页面：http://127.0.0.1:5173
- 健康检查：http://127.0.0.1:5173/actuator/health
- 再次启动已有构建：`.\start.ps1 -SkipBuild`
- 停止脚本启动的服务：`.\stop.ps1`
- 安装当前用户登录后自动启动：`.\install-autostart.ps1`；取消：`.\uninstall-autostart.ps1`。
- 代码开发模式：`.\start.ps1 -Dev`，分别运行前端 5173 和后端 8080。
- 如果本站已运行，会提示已有站点；其他程序占用端口时会报错，不会结束其他程序。

项目使用自己的 `backend/settings.xml` 和 `.cache/m2`，不修改全局 Maven 配置。

也可以在开发时，在两个终端中分别运行：

```powershell
# 终端 1，从项目根目录执行
cd backend
mvn -B -s settings.xml -gs settings.xml "-Dmaven.repo.local=../.cache/m2" package
java '-Dfile.encoding=UTF-8' -jar target/procurement-0.1.0.jar
```

```powershell
# 终端 2，从项目根目录执行
cd frontend
npm.cmd ci --registry=https://registry.npmjs.org
npm.cmd run dev
```

## 演示账号

仅默认 `local` 开发配置自动初始化演示数据。本机演示登录入口显示在本地登录页；云端关闭演示模式，管理员密码仅通过部署平台的私密环境变量配置。

| 账号 | 角色 | 可操作内容 |
| --- | --- | --- |
| admin | 系统管理员 | 跨部门查看和处理业务、成员管理、审计 |
| employee | 产品研发部员工 | 提交申请，查看自己的申请 |
| manager | 产品研发部主管 | 提交申请，查看与审批本部门申请 |
| buyer | 采购专员 | 生成订单、创建商品和供应商 |
| warehouse | 仓库管理员 | 确认入库、登记领用、查看库存流水 |
| employee2 | 市场运营部员工 | 验证不同部门之间的数据隔离 |

任何角色都不能审批自己创建的采购申请。

## 一次完整演示

1. 使用 `employee` 登录，在“采购申请”新建需求，可添加多个商品。
2. 退出，使用 `manager` 登录，审批这笔申请。
3. 使用 `buyer` 登录，在“待下单”中生成订单，系统按供应商拆单。
4. 使用 `warehouse` 登录，在“采购订单”中填写本次验收数量，分批入库，也可关联原单退货并跟进补货。
5. 在“库存管理”登记领用，在“盘点与归还”中处理领用归还、库存盘点与差异。
6. 在“通知中心”查看审批与收货进展，在“个人中心”维护资料、修改密码。
7. 使用 `admin` 查看操作审计，在“运行与备份”中创建、校验与下载备份。

管理员也能对其他人的申请依次审批、下单、收货。首次初始化含 8 个商品、3 家供应商、6 笔申请、1 笔待收货订单和期初库存流水。

## 轻舟智能体

登录后从顶部打开「轻舟智能体」。先点「低库存商品」，选择一件商品查看现存量、安全库存缺口、可见待到货量和参考补货量。也可以输入「查一下办公纸库存和在途数量」。有权限的用户可将商品带入补货表单，提交和审批继续走原有流程。

快捷查询由后端直接执行。自然语言模式需要在启动应用前设置 `QINGZHOU_AI_API_KEY`。可用 `QINGZHOU_AI_MODEL` 和 `QINGZHOU_AI_ENDPOINT` 指定模型和 HTTPS 接口。模型负责选择工具，Spring Boot 校验计划并按当前用户权限查询业务数据。模型超时或返回无效计划时，页面会提示，可继续使用快捷查询。

### 运行真实模型评测

在一个新的 PowerShell 窗口执行以下命令。评测默认使用独立的内存 H2 和合成商品，向模型发送固定测试问句。密钥通过隐藏输入读取，仅在当前终端的进程环境中使用。请确认当前终端没有设置 `TEST_DB_URL`，以免接入自定义测试数据库。

```powershell
$env:QINGZHOU_AI_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host '模型 API Key' -AsSecureString)).Password
$env:QINGZHOU_AI_LIVE_EVAL = '1'
try {
    mvn.cmd -f backend/pom.xml -s backend/settings.xml -gs backend/settings.xml "-Dmaven.repo.local=$PWD/.cache/m2" "-Dtest=UsabilityPolishTest#liveInventoryAgentEvaluation" test
} finally {
    Remove-Item Env:QINGZHOU_AI_API_KEY,Env:QINGZHOU_AI_LIVE_EVAL
}
```

输出包含单商品补货、低库存、当前待办和商品重名四个用例的预期工具、实际工具、状态、耗时和通过数。常规构建会跳过外部模型调用，同时验证模拟超时、无效计划及快捷查询恢复路径。评测耗时反映测试机到模型接口的单次响应，可用于复现实验。

## 已实现

- 密码哈希、会话 Cookie 登录、CSRF 防护、退出与会话过期处理。
- 服务端角色权限、员工个人范围、主管部门范围、自我审批拦截。
- 采购申请、价格快照、审批/驳回、从已审批申请创建订单。
- 多商品明细、供应商拆单、分批收货、采购退货与补收、原子库存扣减、库存预警和流水。
- 关联原领用记录的归还、库存版本校验、盘盈盘亏、盘点记录。
- 个人资料、自助修改密码、旧登录失效；站内通知、未读筛选、已读处理和未读数轮询。
- 构建后的页面稳定运行、守护重启、登录 Windows 后启动、自动在线备份、校验和离线恢复。
- 创建申请和领用操作的持久化幂等标识；重复下单与收货保护。
- 商品和供应商创建、成员创建、业务操作审计。
- 商品和供应商资料编辑，价格变更保留历史申请快照，业务发生后锁定计量单位。
- 申请人修改待审批申请、修改并重新提交被驳回申请；审批与编辑均检查记录版本。
- 编辑成员姓名、部门和角色、启用/停用账号；变更后旧会话失效，并保护管理员账号。
- 工作台统计、筛选搜索、申请列表客户端分页、CSV 导出。
- 响应式布局、表单校验、弹窗键盘焦点管理、减少动画设置。
- SQL 版本迁移、自动化集成测试、构建脚本、CI、容器配置。

## 工程结构

```text
backend/
  src/main/java/com/qingzhou/procurement/
    SecurityConfig.java       登录、会话和请求保护
    Account.java              当前账号与角色检查
    ApiController.java        API 边界、输入校验
    ProcurementService.java   申请、审批、拆单、权限与幂等
    LineItems.java            商品快照与明细读取
    FulfillmentService.java   分批收货、采购退货和数量守恒
    InventoryOperations.java  盘点与领用归还
    PeopleService.java        个人资料与密码
    Notifications.java        站内通知
    BackupService.java        在线备份与自动调度
    SeedData.java             开发数据与首次管理员初始化
  src/main/resources/db/migration/  数据库迁移
  src/test/                   业务与接口集成测试
frontend/
  src/App.vue                 工作台、业务页面、交互表单
  src/components/             多商品、收退货、盘点、个人中心、通知与备份页面
  src/api.ts                  类型、统一请求与 CSRF 管理
  src/style.css               样式、响应式与动效
docs/
  architecture.md             状态、事务与数据库设计
  api.md                      接口与权限说明
start.ps1 / stop.ps1           稳定模式启动与停止
supervise.ps1                 异常退出后重启
restore.ps1                   备份校验与离线恢复
compose.yml                   PostgreSQL + Java + Nginx
```

页面与业务服务已按采购明细、收退货、盘点归还、个人中心、通知和备份拆分。数据库访问采用参数化 JDBC，使库存条件更新和行锁的行为清晰可见。业务说明见 [五项功能](docs/five-features.md)，启动和恢复见 [运维说明](docs/operations.md)。

## 验证

```powershell
cd backend
mvn -B -s settings.xml -gs settings.xml "-Dmaven.repo.local=../.cache/m2" verify
```

```powershell
cd frontend
npm.cmd run build
```

后端测试覆盖权限、登录、申请与库存规则，多商品拆单、分批收退货、并发收货、盘点、归还、个人资料、密码、通知、备份恢复和云部署校验。密码最低长度为 8 字符；服务器密码恢复步骤见 [密码恢复说明](docs/password-recovery.md)。业务测试使用独立数据库，不改变本地业务数据。

CI 已配置 H2 测试和 PostgreSQL 测试任务。要在独立 PostgreSQL 测试库运行相同测试，设置 `TEST_DB_URL`、`DB_USER`、`DB_PASSWORD`；测试库必须为空且可被写入，不能指向业务数据库。

## PostgreSQL 与 Docker

线上业务应用采用阿里云 Ubuntu 服务器，由 Nginx 提供 HTTPS，Spring Boot 和 PostgreSQL 在服务器本机运行。站点配置、证书和更新步骤见 [阿里云上线流程](docs/public-launch.md)。

服务器上的模型配置写在 `/etc/qingzhou/server.env`。编辑后执行 `sudo systemctl restart qingzhou`，再从智能体入口测试真实问句。遇到上游错误可用 `sudo journalctl -u qingzhou --since "10 minutes ago" --no-pager` 查看 `Inventory AI` 日志。

仓库也保留 [Render 与 Neon 免费云部署方案](docs/cloud-free-deployment.md)。

安装 Docker 后，复制 `.env.example` 为 `.env`，填写数据库密码与至少 8 位的管理员初始密码，再运行：

```powershell
docker compose up --build -d
```

页面为 http://127.0.0.1:8088，账号为 `admin`，密码使用自己配置的 `BOOTSTRAP_ADMIN_PASSWORD`。此模式不初始化演示账号和业务数据，先创建供应商、商品和团队成员，再进行业务操作。数据库通过命名卷持久化。示例默认只监听本机。

不用 Docker 时，设置 `SPRING_PROFILES_ACTIVE=postgres`、`DB_URL=jdbc:postgresql://...`、`DB_USER`、`DB_PASSWORD`、`BOOTSTRAP_ADMIN_PASSWORD` 后启动 JAR。密码只在数据库无用户时初始化，不用于后续重置密码。对外服务时需要 HTTPS，并设置 `COOKIE_SECURE=true`。

## 当前边界

- 当前仍为单组织、单仓库和单级审批。
- 未实现草稿、撤销、采购取消、退款、发票、多仓库、多租户、复杂审批流和支付；采购退货按补货处理。
- 商品、供应商和成员支持创建、查询与编辑，成员支持启用/停用，个人中心支持改密；尚无忘记密码找回。
- 请求列表在客户端筛选与分页；库存流水、审计最多读取最近 500 笔。大数据量前应增加服务端分页与索引验证。
- 会话保存在单个应用实例中；横向扩容前需引入共享会话等部署设计。
- 容器、PostgreSQL、真实负载和公网发布尚未在这台电脑验证。本机备份仅能防止数据误操作，需另行保留异盘副本以应对磁盘故障。

建议下一步按业务需要增加采购取消/退款、预算与对账、正式部门实体和服务端分页，再考虑多租户或消息队列。
