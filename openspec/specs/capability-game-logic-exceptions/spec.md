# capability-game-logic-exceptions Specification

## Purpose
TBD - created by archiving change add-game-logic-and-exceptions. Update Purpose after archive.
## Requirements
### Requirement: echo 与 broadcast 示例实现

系统 SHALL 实现 GameMessageHandler 的 echo 消息处理：收到 echo 消息后原样回发给发送者；SHALL 实现 broadcast 消息处理：收到 broadcast 消息后向同房间或同游戏内所有在线玩家转发；MUST 替换 battle.move、chat.message 的 stub 实现，至少实现为可验证链路的简化逻辑（如转发或 broadcast）；MUST 证明 WebSocket→Gate→gRPC→Game 全链路可用。

新增消息类型：
- `player.login`: GameMessageHandler SHALL 处理 `player.login` 消息类型，调用 `PlayerDataManager.load(playerId)` 加载或创建玩家数据，更新 `lastLoginTime` 和 `loginCount`，调用 `saveNow(playerId)` 立即写入，并通过 sink 将玩家数据回发给客户端。
- `player.save`: GameMessageHandler SHALL 处理 `player.save` 消息类型，调用 `PlayerDataManager.saveNow(playerId)` 立即将当前内存中的玩家数据写入 MongoDB，并通过 sink 回发确认消息。

GameMessageHandler MUST 注入 `PlayerDataManager` 依赖。

#### Scenario: echo 消息回显
- **WHEN** 客户端发送 echo 类型消息
- **THEN** 服务端将该消息原样回发给该客户端
- **AND** 可验证上行与下行消息一致

#### Scenario: broadcast 消息广播
- **WHEN** 客户端发送 broadcast 类型消息
- **THEN** 服务端将该消息转发给同房间/同游戏内所有在线玩家
- **AND** 发送者以外的玩家能收到该消息

#### Scenario: battle.move 与 chat.message 非 stub
- **WHEN** 客户端发送 battle.move 或 chat.message
- **THEN** 系统执行实际处理逻辑（如解析并转发、broadcast）
- **AND** 不再仅做日志或 TODO

#### Scenario: player.login 首次登录
- **WHEN** 客户端发送 `player.login` 消息且该玩家在 MongoDB 中无记录
- **THEN** 自动生成模拟数据并立即写入 MongoDB
- **AND** 通过 sink 将完整玩家数据回发给客户端

#### Scenario: player.login 再次登录
- **WHEN** 客户端发送 `player.login` 消息且该玩家在 MongoDB 中已有记录
- **THEN** 加载现有数据，更新 lastLoginTime 和 loginCount
- **AND** 立即写入 MongoDB 并通过 sink 回发玩家数据

#### Scenario: player.save 显式保存
- **WHEN** 客户端发送 `player.save` 消息
- **THEN** 当前内存中的玩家数据立即写入 MongoDB
- **AND** 通过 sink 回发确认消息

### Requirement: Netty Handler 统一 exceptionCaught

系统 SHALL 在 WebSocket 与 TCP 的 Netty Handler 中实现 exceptionCaught；SHALL 在异常发生时记录日志（含 channel、异常堆栈）；SHALL 在 channel 可写时向客户端发送错误响应；对于不可恢复异常 MUST 关闭连接；MUST 避免异常被吞没或无响应。

#### Scenario: 可恢复异常时发送错误响应
- **WHEN** Handler 中发生可恢复异常（如参数校验失败）
- **THEN** 记录日志并向客户端发送错误响应
- **AND** 连接保持，客户端可继续请求

#### Scenario: 不可恢复异常时关闭连接
- **WHEN** Handler 中发生不可恢复异常（如协议解析失败、底层 IO 错误）
- **THEN** 记录日志，若可行则发送错误响应
- **AND** 关闭 channel 并释放资源

### Requirement: REST 全局异常处理

系统 SHALL 为 login-service、center-service 添加 HTTP Filter 或 Interceptor 实现全局异常捕获；SHALL 捕获未被业务代码处理的异常；MUST 返回统一 JSON 错误格式；MUST 设置合适的 HTTP 状态码（如 400、500）。

#### Scenario: 业务异常被全局捕获
- **WHEN** REST 接口执行过程中抛出未捕获异常
- **THEN** Filter/Interceptor 捕获并返回统一 JSON 格式
- **AND** 响应体包含 code、message，可选 detail

#### Scenario: 统一错误格式
- **WHEN** 任意外部可观测的错误响应
- **THEN** JSON 结构为 {"code": ..., "message": "...", "detail": "..."} 或等效
- **AND** 前端可统一解析并展示

### Requirement: 错误响应可区分类型

系统 SHALL 支持通过 code 或 HTTP 状态码区分错误类型（如 400 参数错误、401 未认证、500 内部错误）；SHALL 在 message 中提供可展示给用户的描述；MUST 在 detail 中可选提供调试信息（prod 可脱敏）。

#### Scenario: 参数错误返回 400
- **WHEN** 请求参数非法
- **THEN** 返回 HTTP 400 及 JSON 错误体
- **AND** message 指明具体参数问题

#### Scenario: 内部错误返回 500
- **WHEN** 发生未预期异常
- **THEN** 返回 HTTP 500 及 JSON 错误体
- **AND** message 为通用提示，detail 可含调试信息（仅 dev）

