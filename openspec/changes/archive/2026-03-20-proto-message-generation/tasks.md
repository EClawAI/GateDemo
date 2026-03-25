## 1. Proto 文件清理

- [x] 1.1 从 `gate_options.proto` 移除 `msg_id` extension field（保留 `route_to`）
- [x] 1.2 从 `gate_protocol.proto` 移除所有 `option (gateoptions.msg_id)` 行
- [x] 1.3 从 `game_messages.proto` 移除所有 `option (gateoptions.msg_id)` 行

## 2. 脚本开发

- [x] 2.1 创建 `tools/gen_proto.py`：解析 `proto/` 目录下所有 `.proto` 文件，提取 message 名称和 route_to option
- [x] 2.2 实现 CRC32 哈希 ID 生成逻辑（对消息名 UTF-8 编码做 CRC32，产出 32 位无符号十进制整数）
- [x] 2.3 实现上行消息 route_to 校验（有 route_to 的消息值不能为空）
- [x] 2.4 实现消息 ID 碰撞检测（所有消息间检查 CRC32 ID 唯一性，碰撞时报错并给出两个冲突消息名）
- [x] 2.5 实现 `message_registry.json` 生成（输出到 `common/config/message_registry.json`，并确保 `common/pom.xml` 将 `config/` 目录配置为额外 resource 目录）
- [x] 2.6 实现成功摘要输出（proto 文件数、消息总数、上行/下行消息数、JSON 路径）
- [x] 2.7 创建 `tools/gen_proto.sh` 包装脚本（环境检查、调用 Python 脚本、调用 `mvn -pl common compile`）

## 3. Java 加载逻辑

- [x] 3.1 在 `MessageRouteRegistry` 中新增 `loadFromJson(String resourcePath)` 方法，从 classpath 加载 JSON 填充路由映射
- [x] 3.2 删除 `common` 模块中的 `MessageRouteScanner` 类
- [x] 3.3 删除 `gate-service` 中的 `MessageRouteConfig` 配置类
- [x] 3.4 在 gate-service 启动配置中改为调用 `MessageRouteRegistry.loadFromJson("message_registry.json")`

## 4. 验证

- [x] 4.1 运行 `tools/gen_proto.sh` 验证 JSON 生成正确
- [x] 4.2 执行 `mvn compile` 确认全项目编译通过
