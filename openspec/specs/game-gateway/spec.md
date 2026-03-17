# 游戏网关服务 (Game Gateway)

## 项目概述

游戏网关是游戏架构中的核心组件，负责客户端与游戏服务之间的消息转发和协议转换。

## 架构

```
┌─────────────┐     WebSocket/TCP      ┌─────────────┐
│   客户端    │ ◄────────────────────► │   游戏网关   │
└─────────────┘                        └──────┬──────┘
                                               │
                                               │ gRPC
                                               ▼
                                        ┌─────────────┐
                                        │  游戏服务   │
                                        └─────────────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │    Redis    │
                                        └─────────────┘
```

## 能力模块

| 能力ID | 名称 | 状态 | 描述 |
|--------|------|------|------|
| capability-websocket-gateway | WebSocket网关 | IMPLEMENTED | 处理WebSocket连接 |
| capability-tcp-gateway | TCP网关 | IMPLEMENTED | 处理TCP连接 |
| capability-message-protocol | 消息协议 | IMPLEMENTED | 二进制协议 |
| capability-connection-management | 连接管理 | IMPLEMENTED | 连接状态管理 |
| capability-security | 安全 | IMPLEMENTED | 认证、加密、限流 |
| capability-offline-message | 离线消息 | IMPLEMENTED | Redis Stream存储 |
| capability-cluster | 集群管理 | IMPLEMENTED | 多实例管理 |
| capability-monitoring | 监控 | IMPLEMENTED | TraceId、日志 |

## 技术栈

- Java 21
- Netty 4.x
- Redis (Lettuce)
- gRPC
- Protobuf

## 配置

### gate

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| gate.id | gate-01 | 网关ID |
| gate.host | 0.0.0.0 | 监听地址 |
| gate.port | 8888 | WebSocket端口 |

### gate.redis

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| gate.redis.host | localhost | Redis地址 |
| gate.redis.port | 6379 | Redis端口 |
| gate.redis.password | | Redis密码 |

### offline-message

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| offline-message.threshold | 200 | 离线消息阈值 |
| offline-message.enabled | true | 是否启用 |

## 版本

- 1.0.0 (2026-03-09)
