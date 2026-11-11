# Postman 测试步骤

导入同目录 collection 和 environment 两个 JSON，选择环境。集合共 31 个请求。集合是分场景的操作模板，不能直接 Run 整个集合。

## 先准备

- 本机 Postman 桌面版；baseUrl 默认 https://www.example.com，不加末尾 /api。也可改成实际运行的本机测试站点。
- 当前前五项更新尚未确认上传，新版 browse、drafts、close 等接口应在对应版本部署后测试。
- 在网站成员管理创建员工、主管、采购、仓管测试账号。员工与主管部门要一致。生产环境没有默认 employee/buyer 等演示账号。
- 填写环境 username、password，密码只在自己电脑保存，不要导出含密码的环境分享出去。
- 写操作会修改当前连接数据库。采购、收退货、取消、停用等使用专用测试商品和单据；不要拿正常业务单据练习。

## 登录顺序

先执行 01 获取 CSRF → 02 登录 → 03 登录后重新获取 CSRF → 04 当前账号。
集合自动保存 csrfToken；Postman Cookie Jar 保持同域会话，不需要 Bearer Token，也不需要手填 JSESSIONID。
后续写请求自动带 X-CSRF-TOKEN。每次换账号都重新执行 01～04，确认角色再操作。

## ID、版本和防重标识

- 06 保存第一个启用商品的 productId、productVersion、stockVersion。执行前确认它确实是测试商品，必要时自己修改 productId。
- A 新建申请后保存 requestId 和 requestVersion。09 读取该申请当前版本。
- C 生成订单后保存第一张订单的 orderId、orderVersion、orderLineId。多供应商会拆成多张，需逐张查看。
- 11 读取当前订单并更新 orderVersion、第一条 orderLineId；收货、退货、结束后都重新读取再操作。
- H 停用后恢复商品前，使用 07 搜索其 SKU（或通过网站查看）读取最新 version，手工更新 productVersion。不要直接重跑 06，它可能选到另一件启用商品并覆盖 productId。
- 每个全新的业务操作先把 requestKey 改成一个新 UUID 或至少 8 位唯一字符串。重复验证同一次请求时，保留原 requestKey 和完全相同的请求体，不改变数量、原因或版本。
- 不能把不同申请/不同草稿提交都复用同一个 CREATE_REQUEST 标识，否则按设计返回 409。
- 草稿 ID 可保留；保存成功后集合更新 draftVersion。草稿示例中的 productId 使用环境变量，提交前检查请求体。

## 正常流程

1. 员工登录：A 申请采购 4 件。
2. 指定的审批负责人登录：09 读取申请，B 审批通过。申请人不能审批自己申请。
3. 采购登录：C 生成订单，再执行 11 查看订单。
4. 仓管登录：使用新 requestKey，D 收货 2 件，11 查看净收/待收和版本；库存应增加 2。
5. 仓管：使用新 requestKey，E 退货 1 件，11 查看；净收 1、待收/补货 3，库存相对初始增加 1。
6. 采购：11 读取后使用新 requestKey，G 终止剩余交付；库存不变、状态 TERMINATED，不能再收货。

不同分支另建单据测试：F 撤回需要仍待审批的申请；未收货订单 G 应为 CANCELLED；已全部收齐不能取消。

## 应重点验证的异常

| 场景 | 预期 |
| --- | --- |
| 未登录读取业务 | 401 |
| 缺 CSRF 的写操作 | 403 |
| 员工访问订单/成员管理等无权限接口 | 403 |
| 申请人审批自己 | 403 |
| 负数量、空用途、错误字段 | 400 |
| 旧版本审批、停用、收货、结束 | 409 |
| 停用商品创建新申请 | 409 |
| 同一 key、同一内容重发收货/领用/申请 | 不重复创建或改变库存 |
| 同一 key 改数量或原因再提交 | 409 |
| 超过待收量、净收量或现有库存 | 409，库存和业务记录不应部分写入 |
| 员工读取其他人的草稿/申请 | 404 或权限范围不可见 |
| 已撤回申请审批、已取消订单收货 | 409 |
| 查询其他用户的 requestKey | committed=false，不泄露他人记录 |

“非法版本”等错误若先被权限校验挡住，会返回 403；应使用有权限的账号测试版本冲突。退出登录后先测 N4，再重新登录以继续测试。

## 断网与重试

Postman 超时或断网不代表服务端没有提交。先恢复网络，发送 12 查询原 requestKey：committed=true 表示有已提交记录，结合对应单据再确认。
committed=false 也可能是原请求尚在处理中。不能据此换新 key 再创建同一笔业务；按相同 key 和原请求体重试有防重保障的操作。
没有 Idempotency-Key 的版本更新操作先读取记录，核对当前值和版本。

## 响应和数据库一起核对

集合提供基本状态码断言，结果在 Postman 的 Test Results 查看。验证业务时还要检查响应内容、实际列表、库存以及 audit_log；只看到 200 并不等于数量一定正确。
例如 4 件采购只收 2 件，应检查订单净收 2、剩余 2、商品库存增加 2；同 key 重发后这些数字都不再变化。
在 Navicat 中刷新表数据即可，不需要手工修改库存表。

参考：[Postman Cookie 管理](https://learning.postman.com/docs/use/send-requests/response-data/cookies)、[环境变量](https://learning.postman.com/docs/use/send-requests/variables/environment-variables)。

新版本创建/重提申请必须提供 approverId。先执行 06B 查询可审批人员，选择其中一人的 ID 填到环境变量 approverId；审批时必须切换到该人员账号。无人可选时先在网站配置其他管理员或同部门主管。
