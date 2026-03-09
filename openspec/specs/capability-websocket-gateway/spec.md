# WebSocket网关能力

## 概述

WebSocket网关能力负责处理客户端的WebSocket连接，实现玩家认证、消息收发和心跳检测。

## 需求

### REQUIREMENT: WebSocket服务器启动

系统 SHALL 在应用启动时自动启动WebSocket服务器，监听配置的端口。

### REQUIREMENT: 客户端连接

系统 SHALL 接受客户端的WebSocket连接请求，并为其分配唯一的Channel。

### REQUIREMENT: 协议升级

系统 SHALL 支持HTTP到WebSocket的协议升级，升级路径为/ws。

### REQUIREMENT: 心跳检测

系统 SHALL 在客户端连接空闲300秒时触发心跳超时事件。

## 场景

### Scenario: 客户端连接成功

- **GIVEN** 客户端发起WebSocket连接到 ws://localhost:8888/ws
- **WHEN** 完成HTTP握手
- **THEN** 建立WebSocket连接
- **AND** 系统记录连接信息

### Scenario: 客户端发送消息

- **GIVEN** 客户端已建立WebSocket连接
- **WHEN** 客户端发送二进制消息
- **THEN** 服务端解码消息
- **AND** 分发到对应的Handler处理

### Scenario: 心跳超时

- **GIVEN** 客户端连接建立
- **WHEN** 300秒内未收到任何消息
- **THEN** 关闭连接
- **AND** 触发断开回调

## 实现

- **核心类**: `ws/NettyWebSocketServer`
- **Handler**: `handler/GateNettyWebSocketHandler`
- **配置**: `config/GateConfig`
