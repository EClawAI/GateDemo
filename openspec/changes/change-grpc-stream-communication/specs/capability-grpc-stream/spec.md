# gRPC Stream通信能力

## ADDED Requirements

### Requirement: Gate与Game建立双向Stream连接

系统 SHALL 在Gate启动时与每个Game服务建立双向gRPC Stream连接。

#### Scenario: Gate启动时建立连接
- **WHEN** Gate服务启动
- **THEN** 与配置的所有Game服务建立Bidirectional Stream连接

### Requirement: 消息通过Stream传输

系统 SHALL 通过已建立的Stream发送和接收消息，而不是使用Unary调用。

#### Scenario: Gate发送消息到Game
- **WHEN** Gate需要转发玩家消息到Game
- **THEN** 通过Stream写入消息，不建立新连接

#### Scenario: Game推送消息到Gate
- **WHEN** Game通过Stream推送消息
- **THEN** Gate接收消息并转发给对应玩家

### Requirement: 消息体使用二进制格式

系统 SHALL 将消息体从JSON字符串改为Protobuf二进制格式。

#### Scenario: 发送二进制消息
- **WHEN** Gate发送游戏消息
- **THEN** 消息体使用bytes类型传输

### Requirement: Stream连接健康检测

系统 SHALL 定期检测Stream连接状态，断开时自动重连。

#### Scenario: Stream断开重连
- **WHEN** Stream连接断开
- **THEN** 自动尝试重新建立连接

### Requirement: 消息序列化

系统 SHALL 使用Protobuf对消息体进行序列化和反序列化。

#### Scenario: 消息序列化
- **WHEN** 发送游戏消息
- **THEN** 消息体序列化为Protobuf二进制

#### Scenario: 消息反序列化
- **WHEN** 接收游戏消息
- **THEN** 消息体反序列化为业务对象
