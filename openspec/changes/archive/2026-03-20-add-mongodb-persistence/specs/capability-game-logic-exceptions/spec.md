## MODIFIED Requirements

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
