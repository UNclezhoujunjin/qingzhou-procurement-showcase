# 管理员密码恢复与八位最低长度

网站创建成员、自助改密、初始管理员、云部署检查及服务器恢复统一最低 8 字符；继续保留 BCrypt 的 72 UTF-8 字节限制。成员与自助改密最多 64 字符。现有密码保持有效。

服务器恢复需要先更新程序包，再上传 deploy/aliyun/reset-admin-password.sh 至 /home/admin 并运行：

```bash
sudo bash /home/admin/reset-admin-password.sh
```

新密码交互输入且隐藏，不进入命令参数或 shell 历史。程序离线生成 BCrypt 哈希，脚本先触发既有备份，再通过本机 PostgreSQL 管理员连接，在事务中仅修改启用的 admin 管理员密码、提升账号版本并记录审计。不新建用户、不提升权限、不修改业务数据。

恢复不要求旧网站密码；服务器 sudo 权限属于恢复权限。修改 BOOTSTRAP_ADMIN_PASSWORD 环境变量不会重置已有账号。reset-admin-password.sh 依赖已安装的 qingzhou-backup.service，备份失败将终止重置。

脚本语法检查和本机构建不等于服务器上的恢复已执行，实际执行结果需核验。
