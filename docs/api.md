# API 与权限

所有接口前缀 `/api`。业务 JSON 使用数据库字段风格返回（例如 `product_name`），输入 DTO 使用 camelCase。金额为 JSON 数值，后端以 DECIMAL/BigDecimal 计算。成功为 200，退出为 204；错误格式为 `{"message":"可读提示"}`。

## 登录

`GET /auth/config` 返回当前环境是否启用演示模式，例如 `{"demo":true}`；非演示模式登录页不显示演示账号。

1. `GET /auth/csrf` → `{"token":"…","headerName":"X-CSRF-TOKEN"}`，保留服务端会话 Cookie。
2. `POST /auth/login`，Content-Type 为 `application/x-www-form-urlencoded`，提交 `username`、`password`，附上 `X-CSRF-TOKEN`。
3. 登录成功后重新 `GET /auth/csrf`，再获取 `GET /auth/me`。
4. 后续请求携带同源 Cookie；所有写请求附 CSRF header。
5. `POST /auth/logout` 使会话失效。

`GET /auth/me` 返回：

```json
{"id":1,"username":"admin","name":"陈予安","department":"管理中心","role":"ADMIN"}
```

## 业务接口

| 方法 | 路径 | 权限 / 语义 |
| --- | --- | --- |
| GET | /overview | 当前账号可见范围的采购统计；无库存价值权限时对应值为 null |
| GET | /products | 所有已登录账号 |
| POST | /products | ADMIN / BUYER |
| PUT | /products/{id} | ADMIN / BUYER；需传 data 和 version；不支持直接修改库存 |
| GET, POST | /suppliers | ADMIN / BUYER |
| PUT | /suppliers/{id} | ADMIN / BUYER；需传 data 和 version |
| GET | /requests | 员工仅本人、主管仅本部门、其他业务角色全部 |
| POST | /requests | ADMIN / EMPLOYEE / MANAGER，必须有 Idempotency-Key |
| PUT | /requests/{id} | 仅申请人；待审批或已驳回可编辑；保存后回到待审批 |
| POST | /requests/{id}/review | ADMIN / MANAGER；禁止自审；主管限本部门 |
| POST | /requests/{id}/order | ADMIN / BUYER；重复请求返回原订单 |
| GET | /orders | ADMIN / BUYER / WAREHOUSE |
| POST | /orders/{id}/receive | ADMIN / WAREHOUSE；重复确认不重复入库 |
| POST | /orders/{id}/return | ADMIN / WAREHOUSE；关联明细采购退货，需幂等 key |
| GET | /orders/{id}/events | ADMIN / BUYER / WAREHOUSE；分批收货与退货记录 |
| POST | /inventory/issue | ADMIN / WAREHOUSE，必须有 Idempotency-Key |
| GET | /movements | ADMIN / BUYER / WAREHOUSE，最多 500 笔 |
| GET | /inventory/issues | ADMIN / WAREHOUSE；未全部归还的原领用记录 |
| POST | /inventory/issues/{id}/return | ADMIN / WAREHOUSE；领用归还，需幂等 key |
| GET, POST | /inventory/counts | ADMIN / WAREHOUSE；盘点记录与差异调整，写入需幂等 key |
| GET | /profile | 本人的资料、联系方式和版本 |
| PUT | /profile | 本人修改姓名、邮箱和电话；成功后保持登录，资料版本递增 |
| POST | /profile/password | 校验当前密码后修改；成功后所有旧登录失效 |
| GET | /notifications | 本人通知，page/size/unread 筛选 |
| POST | /notifications/{id}/read | 本人通知标已读 |
| POST | /notifications/read-all | 本人全部通知标已读 |
| GET, POST | /admin/backups | ADMIN；备份列表和在线备份 |
| GET | /admin/backups/{name} | ADMIN；下载备份 ZIP |
| GET | /admin/backups/{name}/verify | ADMIN；SHA-256 校验 |
| GET | /admin/backups/{name}/checksum | ADMIN；下载校验文件 |
| GET | /audit | ADMIN，最多 500 笔 |
| GET, POST | /users | ADMIN；返回不含密码哈希 |
| PUT | /users/{id} | ADMIN；编辑资料、角色和 enabled；版本变更使已有登录失效 |

非角色允许范围的操作返回 403。未登录返回 401。业务状态冲突、库存不足、幂等 key 内容不一致返回 409。输入错误返回 400，记录不存在返回 404。

## 请求示例

创建采购申请（最多 100 项不同商品，各数量 1～100000，用途不超过 500 字）：

```json
{"lines":[{"productId":1,"quantity":3},{"productId":2,"quantity":3}],"reason":"新增研发工位","approverId":3}
```

审批负责人 ID 必须从 `GET /requests/approvers` 的候选人员中选择；示例 ID 仅用于说明。候选为启用且无需强制改密的其他管理员、同部门主管。无人可选时先保存草稿并配置审批人员。新申请仅指定负责人可审批；历史未指定的单据保留原角色/部门规则。

审批（decision 仅 APPROVED / REJECTED；驳回时 note 必填）：

```json
{"decision":"APPROVED","note":"预算确认，可以采购","version":0}
```

领用（reason 不超过 100 字）：

```json
{"productId":1,"quantity":1,"reason":"林小满 / 产品研发部入职领用"}
```

创建商品（SKU 唯一，参考价为正数、最多两位小数）：

```json
{"sku":"IT-004","name":"无线会议麦克风","category":"数码设备","unit":"套","price":699.00,"minStock":3,"supplierId":1}
```

创建供应商：

```json
{"name":"示例供应商有限公司","contact":"李经理","phone":"0571-88001234"}
```

创建成员（username 为 3～64 位字母、数字、下划线或短横线；密码 8～64 字符且 UTF-8 不超过 72 字节）：

```json
{"username":"new_employee","name":"示例员工","department":"产品研发部","role":"EMPLOYEE","password":"ExampleOnly@2026"}
```

## 编辑接口

商品、供应商、申请的 PUT 请求使用 `{"data":{...原创建字段},"version":0}`。version 使用 GET 返回记录的当前版本，保存成功后版本递增；旧版本返回 409。申请修改后更新价格快照、清除当前审批结果并回到 PENDING。已审批或已下单的申请不允许编辑。旧版单商品 `productId/quantity/reason` 仍兼容，但不能与 lines 同时使用。

商品已有申请或库存流水时不允许修改计量单位。单独修改商品参考价不会改变既有申请的价格快照；申请人主动修改申请时，会按商品当前单价重新计算。

成员 PUT 示例：

```json
{"name":"示例成员","department":"采购部","role":"BUYER","enabled":true,"version":0}
```

账号名和密码不通过此接口修改。任何成员变更都会使该账号旧版本的会话失效；停用时禁止登录，再启用后也必须重新登录。管理员不能停用自己或移除自己的管理员角色，服务端还会检查至少保留一名启用的管理员。编辑操作均记录审计。

目前没有 OpenAPI 自动文档端点。CSV 由前端基于当前筛选数据生成，并对公式起始字符进行转义。

## 分批收货与退货

订单读取返回 lines、version、fulfillment_status；每个明细包含 quantity、received_quantity、returned_quantity、net_received、remaining。申请下单返回首张订单和 orders 数组，按供应商拆单。

收货和采购退货均传 `Idempotency-Key`：

```json
{"lines":[{"orderLineId":"明细ID","quantity":6}],"reason":"本次首批到货","version":0}
```

收货 quantity 不能超过 remaining，退货 quantity 不能超过 net_received，且实物库存必须足够。退货必须填写 reason。原无请求体的整单收货 API 仍兼容，但新页面使用逐项数量与版本校验。

## 盘点与领用归还

盘点请求：

```json
{"lines":[{"productId":1,"countedQuantity":8,"stockVersion":3}],"reason":"月末实物复核"}
```

stockVersion 必须使用商品返回的 stock_version，期间库存变化会导致 409，整单回滚。实盘数可为 0；零差异只留盘点记录。

领用归还向原领用流水 ID 提交 `{"quantity":1,"reason":"工位调整，归还仓库"}`。以上写入均需幂等 key。库存流水返回 business_type，可区分 OPENING、ISSUE、PURCHASE_RECEIPT、PURCHASE_RETURN、ISSUE_RETURN、COUNT_ADJUSTMENT。

## 删除团队成员

POST `/users/{id}/remove`，仅管理员，需 CSRF 和 `Idempotency-Key`。

```json
{"version":0,"username":"member_login","reason":"测试账号清理"}
```

需输入目标成员的准确登录账号确认。不允许自删或删除存在待审批申请的成员；版本冲突返回 409。成功后逻辑删除并撤销登录，保留业务历史；GET `/users` 不再返回该成员，编辑和密码重置返回 404，原登录账号名称保持占用。

## 个人资料与密码

PUT /profile：`{"name":"姓名","email":"name@example.com","phone":"123456","version":0}`。

POST /profile/password：`{"currentPassword":"当前密码","newPassword":"新的至少8位密码"}`。新密码不能与旧密码相同，UTF-8 不超过 72 字节。请勿把真实密码写入脚本、日志或示例文件。
