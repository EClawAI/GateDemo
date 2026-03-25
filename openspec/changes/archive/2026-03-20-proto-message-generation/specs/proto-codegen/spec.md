## ADDED Requirements

### Requirement: 脚本解析 proto 文件提取消息定义
脚本 SHALL 扫描 `proto/` 目录下所有 `.proto` 文件，提取每个 `message` 块的名称，并检测是否包含 `option (gateoptions.route_to)` 声明。

#### Scenario: 正常解析多个 proto 文件
- **WHEN** `proto/` 目录包含 `gate_protocol.proto`（含 AuthRequest、AuthResponse 等）和 `game_messages.proto`（含 CgBattleMove 等）
- **THEN** 脚本提取出所有 message 名称及其 route_to 值（有则提取，无则标记为下行消息）

#### Scenario: proto 文件不存在
- **WHEN** `proto/` 目录为空或不存在
- **THEN** 脚本输出错误提示并以非零退出码退出

### Requirement: CRC32 哈希生成消息 ID
脚本 SHALL 对每个消息名（UTF-8 编码）计算 CRC32 哈希值，产出 32 位无符号整数作为 msg_id。

#### Scenario: 确定性 ID 生成
- **WHEN** 消息名为 "AuthRequest"
- **THEN** 每次运行产出相同的 CRC32 值

#### Scenario: 不同消息名产出不同 ID
- **WHEN** 消息名分别为 "AuthRequest" 和 "CgBattleMove"
- **THEN** 两者的 CRC32 值不同

### Requirement: 上行消息必须标记 route_to
脚本 SHALL 校验：所有包含 `route_to` option 的消息（上行消息）的 `route_to` 值不为空。无 `route_to` 的消息视为下行消息，不做此校验。

#### Scenario: 上行消息缺少 route_to 值
- **WHEN** 某 message 声明了 `option (gateoptions.route_to)` 但值为空字符串
- **THEN** 脚本报错，输出消息名和所在文件名，以非零退出码退出

#### Scenario: 下行消息无 route_to
- **WHEN** 某 message（如 AuthResponse）没有声明 `route_to` option
- **THEN** 脚本正常处理，将其 direction 标记为 "downstream"，service 标记为 null

### Requirement: 消息 ID 碰撞检测
脚本 SHALL 在所有消息（上行 + 下行）间检查 CRC32 ID 是否有重复。

#### Scenario: 无碰撞
- **WHEN** 所有消息名的 CRC32 哈希互不相同
- **THEN** 脚本正常继续后续步骤

#### Scenario: 存在碰撞
- **WHEN** 两个消息名 CRC32 哈希相同
- **THEN** 脚本输出两个碰撞消息的名称和对应的 ID 值，提示开发者修改其中一个消息名，以非零退出码退出

### Requirement: 生成 message_registry.json
脚本 SHALL 生成 `common/config/message_registry.json`，包含所有消息的路由注册信息。

#### Scenario: 正常生成
- **WHEN** 校验全部通过
- **THEN** 生成的 JSON 包含 `generated_at`（ISO 时间戳）、`hash_algorithm`（"CRC32"）、`messages` 数组，每条含 `id`（十进制整数）、`name`、`service`（上行为服务名，下行为 null）、`direction`（"upstream"/"downstream"）、`file`（来源 proto 文件名）

### Requirement: 调用 protoc 编译 Java 类
脚本 SHALL 在生成 JSON 后调用 `mvn -pl common compile` 编译 proto 生成 Java 类。

#### Scenario: protoc 编译成功
- **WHEN** proto 文件语法正确且 Maven 环境正常
- **THEN** 脚本输出编译成功信息，退出码为 0

#### Scenario: protoc 编译失败
- **WHEN** proto 文件有语法错误
- **THEN** 脚本透传 Maven 错误输出，以非零退出码退出

### Requirement: 脚本输出摘要
脚本 SHALL 在成功时输出人类可读的摘要信息。

#### Scenario: 成功运行
- **WHEN** 全部步骤通过
- **THEN** 输出包括：解析的 proto 文件数、消息总数、上行/下行消息数、JSON 输出路径
