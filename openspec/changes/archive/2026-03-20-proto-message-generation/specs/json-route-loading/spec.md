## ADDED Requirements

### Requirement: MessageRouteRegistry 从 JSON 加载路由表
`MessageRouteRegistry` SHALL 提供 `loadFromJson(String resourcePath)` 静态方法，从 classpath 资源读取 `message_registry.json` 并填充内部路由映射。

#### Scenario: 正常加载
- **WHEN** classpath 中存在 `message_registry.json` 且格式正确
- **THEN** 所有消息的 id/name/service 被注册到 Registry，`getByMsgId()`、`getByName()`、`getTargetService()` 均可正确查询

#### Scenario: JSON 文件不存在
- **WHEN** classpath 中不存在 `message_registry.json`
- **THEN** 抛出异常或记录 ERROR 日志并快速失败，提示开发者运行 `tools/gen_proto.sh`

#### Scenario: JSON 格式错误
- **WHEN** `message_registry.json` 内容不是合法 JSON 或缺少必要字段
- **THEN** 抛出异常，附带具体解析错误信息

### Requirement: 启动时自动加载替代 Scanner
gate-service 启动配置 SHALL 调用 `MessageRouteRegistry.loadFromJson("message_registry.json")`，替代原有的 `MessageRouteScanner.scan()` 调用。

#### Scenario: 启动后路由表可用
- **WHEN** gate-service 启动完成
- **THEN** `MessageRouteRegistry.getByMsgId(crc32("AuthRequest"))` 返回 RouteInfo(service="gate")，`MessageRouteRegistry.getByMsgId(crc32("CgBattleMove"))` 返回 RouteInfo(service="game")

### Requirement: 移除运行时 descriptor 扫描
`MessageRouteScanner` 类和 `MessageRouteConfig`（gate-service）SHALL 被删除，不再在运行时扫描 proto FileDescriptor。

#### Scenario: 代码中无 Scanner 引用
- **WHEN** 删除完成后编译项目
- **THEN** `mvn compile` 成功，无对 `MessageRouteScanner` 或 `MessageRouteConfig` 的引用
