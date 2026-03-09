# 连接管理能力

## 概述

连接管理能力负责管理玩家的连接状态，包括连接注册、认证、心跳和断开处理。

## 需求

### REQUIREMENT: 连接注册

系统 SHALL 在玩家WebSocket连接建立时将其注册到连接管理器。

### REQUIREMENT: 认证状态

系统 SHALL 区分已认证和未认证的连接，未认证连接只能发送认证消息。

### REQUIREMENT: 心跳续期

系统 SHALL 在收到玩家心跳消息时更新最后心跳时间。

### REQUIREMENT: 连接注销

系统 SHALL 在玩家断开连接时从连接管理器中移除。

### REQUIREMENT: 心跳超时

系统 SHALL 在玩家超过300秒未发送心跳时关闭连接。

## 场景

### Scenario: 玩家登录

- **GIVEN** 玩家建立WebSocket连接
- **WHEN** 发送登录消息并认证成功
- **THEN** 标记为已认证
- **AND** 注册到在线玩家列表

### Scenario: 玩家断开

- **GIVEN** 玩家已认证
- **WHEN** 连接断开
- **THEN** 从在线列表移除
- **AND** 触发离线回调

## 实现

- **连接管理器**: `service/ConnectionManager`
- **连接模型**: `model/PlayerConnection`
- **玩家服务**: `service/PlayerService`
