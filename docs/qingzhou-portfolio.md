# 轻舟｜采购与库存协同系统

轻舟是一个个人全栈项目，覆盖采购申请、审批、下单、收货入库和库存预警。前端使用 Vue 3 和 TypeScript，后端使用 Spring Boot、Spring Security、JDBC 和 Flyway，线上业务应用使用 PostgreSQL。

## 业务设计

- 采购申请和库存补货分别记录业务类型。申请、待办和订单都可以追踪业务来源。
- 员工查看自己的申请，主管查看职责范围内的申请，采购和仓库角色分别处理下单和收货。服务端校验角色和数据范围，并拦截申请人自行审批。
- 收货支持分批处理，系统记录库存变化和操作日志。

## 智能体实现节选

自然语言模式由模型生成查询计划。以下是计划解析的简化节选，省略了 JSON 格式检查等外围代码。后端最多接收三个不同的允许操作。执行查询时，服务端沿用当前登录用户的数据范围。补货建议可以带入申请表单，由用户核对后提交。

```java
var actions = new LinkedHashSet<Action>();
for (var node : nodes) {
    String tool = node.path("tool").asText();
    var queryNode = node.path("query");
    if (!queryNode.isMissingNode() && !queryNode.isTextual()) continue;
    String query = queryNode.asText().strip();
    if (Set.of("inspect", "shortages", "tasks", "guide").contains(tool)
            && query.length() <= 80) {
        actions.add(new Action(tool, query));
    }
    if (actions.size() == 3) break;
}
```

模型不可用时，低库存、待办和使用指南的快捷查询仍由后端直接处理。

## 验证与部署

- 业务应用部署在阿里云 Ubuntu 服务器上，Nginx 提供 HTTPS，systemd 管理 Java 服务。数据库配置了每日备份，并用临时数据库验证过恢复。
- 2026 年 11 月 11 日，在本地使用 DeepSeek Flash 和合成数据运行智能体评测。单商品补货、低库存、个人待办和商品重名四个用例全部通过，单条耗时约 0.79–1.37 秒。
- 上述评测是本地固定场景的单轮结果。线上运行指标尚未采集。
