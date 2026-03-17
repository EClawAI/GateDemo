## Why

当前存在以下集成问题：
- NettyTcpServer 类已存在但未注册到应用生命周期中，未在启动流程中调用，TCP 端口从未开启
- GateClusterManager 已实现实例注册、心跳、剔除逻辑但未被注入或调用

## What Changes

将已有能力接入应用生命周期：
- NettyTcpServer 通过依赖注入容器（如 Guice）或手动注册接入启动流程，增加 gate.tcp.enabled 开关
- GateClusterManager 在启动后注册本实例到 Redis，持续心跳

## 核心功能

1. **TCP Server 生命周期集成**
   - NettyTcpServer 注册到 DI 容器或在 main() 中手动初始化
   - 启动时根据配置初始化并监听 TCP 端口

2. **TCP 配置开关**
   - gate.tcp.enabled 控制是否启用 TCP 服务
   - 便于按环境或需求开关

3. **Gate 集群注册与心跳**
   - GateClusterManager 在服务启动后注册本实例到 Redis
   - 使用 ScheduledExecutorService 周期性心跳维持在线状态
   - 实例下线时由 Redis 或心跳超时剔除

## Impact

- 影响 `gate-service` NettyTcpServer 改造、GateClusterManager 注入与调用
- 影响 `login-service` 依赖 Gate 集群信息时的数据源（Redis 实例列表）
