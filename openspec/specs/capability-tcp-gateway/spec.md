# TCP网关能力

## 概述

TCP网关能力负责处理客户端的TCP长连接，支持二进制协议通信和心跳检测。

## 需求

### REQUIREMENT: TCP服务器启动

系统 SHALL 在应用启动时自动启动TCP服务器，监听配置的端口。

### REQUIREMENT: 客户端连接

系统 SHALL 接受客户端的TCP连接请求，并为其分配唯一的Channel。

### REQUIREMENT: 心跳检测

系统 SHALL 定期检测TCP连接的心跳状态。

## 场景

### Scenario: TCP客户端连接

- **GIVEN** 客户端发起TCP连接到 localhost:8889
- **WHEN** 完成TCP握手
- **THEN** 建立TCP连接

### Scenario: TCP心跳超时

- **GIVEN** TCP连接建立
- **WHEN** 超过心跳间隔未收到心跳
- **THEN** 关闭连接

## 实现

- **TCP服务器**: `tcp/NettyTcpServer`
- **消息处理**: `tcp/TcpMessageHandler`
- **心跳处理**: `tcp/heartbeat/TcpHeartbeatHandler`
