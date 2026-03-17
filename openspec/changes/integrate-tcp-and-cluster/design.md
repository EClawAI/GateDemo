# TCP 与集群集成 (design.md)

## Context

- **当前状态**：NettyTcpServer 类已存在但未注册到应用生命周期中，TCP 端口从未开启；GateClusterManager 已实现实例注册、心跳、剔除逻辑但未被注入或调用；MessageDispatcher 已存在用于消息分发。
- **问题**：TCP 服务未启动，无法通过 TCP 接入；Gate 集群信息未注册到 Redis，其他服务（如 login）无法获取 Gate 实例列表；已有能力与主流程脱节。
- **约束**：不使用 Spring Boot；通过 Guice 或手动注册接入；复用现有 MessageDispatcher 处理 TCP 消息。

## Goals / Non-Goals

**Goals:**
- NettyTcpServer 通过 DI 容器（Guice）或手动注册接入启动流程
- 增加 gate.tcp.enabled 开关控制 TCP 服务是否启用
- GateClusterManager 在启动后注册本实例到 Redis，使用 ScheduledExecutorService 周期性心跳
- 复用 MessageDispatcher 处理 TCP 消息

**Non-Goals:**
- 不改变 TCP 协议或消息格式
- 不实现跨 Gate 的会话共享或状态同步（仅注册与心跳）
- 不在此 change 中实现 TCP 负载均衡策略

## Decisions

1. **NettyTcpServer 生命周期集成**
   - 在应用 main() 或 Guice 模块的启动阶段，根据 gate.tcp.enabled 判断是否初始化 NettyTcpServer；若启用，则创建并启动，监听配置的 TCP 端口；在 shutdown hook 中关闭。
   - **理由**：与 WebSocket 服务并列，统一由应用生命周期管理；开关便于按环境或需求关闭 TCP。

2. **gate.tcp.enabled 配置**
   - 新增配置项 gate.tcp.enabled，默认 false 或 true 根据产品需求；为 false 时不启动 NettyTcpServer，不占用 TCP 端口。
   - **理由**：部分部署可能仅用 WebSocket，TCP 作为可选项。

3. **GateClusterManager 注册与心跳**
   - GateClusterManager 在 gate-service 启动完成后，将本实例信息（如 host、port、instanceId）注册到 Redis；使用 ScheduledExecutorService 周期性（如 30 秒）发送心跳，刷新 Redis 中的 TTL；实例下线时由 Redis TTL 过期或显式注销剔除。
   - **理由**：login-service 等可从 Redis 拉取 Gate 实例列表做路由；ScheduledExecutorService 与 Spring 解耦。

4. **TCP 消息复用 MessageDispatcher**
   - TCP 接收到的业务消息，经解码后交由 MessageDispatcher 分发处理；MessageDispatcher 与 WebSocket 路径共用，保证协议与处理逻辑一致。
   - **理由**：避免重复实现；TCP 与 WebSocket 仅传输层不同，业务层统一。

5. **DI 方式**
   - 若项目已有 Guice，则通过 Guice 注入 NettyTcpServer、GateClusterManager 并在启动时调用；若无，则采用手动 new + 注册到统一启动器的方式。
   - **理由**：灵活性；不强制引入 Guice，但支持已有 DI 结构。

## Risks / Trade-offs

- **[风险]** TCP 与 WebSocket 同时启用时端口与资源竞争 → 两者监听不同端口，资源共享（如 EventLoopGroup）需评估；可先独立 EventLoopGroup，后续优化。
- **[风险]** GateClusterManager 心跳失败导致误剔除 → 心跳逻辑需健壮，网络短暂异常时重试；Redis TTL 设置合理（如 2 分钟），心跳周期小于 TTL。
- **[权衡]** 未使用服务发现框架 → 基于 Redis 的简易注册满足当前需求；若后续需 Consul/Etcd，可抽象接口扩展。

## Migration Plan

- **实现顺序**：先接入 GateClusterManager 注册与心跳（确保 Redis 中有 Gate 实例信息）→ 再接入 NettyTcpServer 到启动流程，并配置 gate.tcp.enabled → 验证 MessageDispatcher 处理 TCP 消息。
- **部署**：需确保 Redis 可用；gate.tcp.enabled 按需开启；观察 Redis 中 Gate 实例 key 的注册与过期。
- **回滚**：将 gate.tcp.enabled 设为 false 可关闭 TCP；GateClusterManager 可设开关或注释调用，快速回退。

## Open Questions

- Gate 实例信息在 Redis 中的 key 格式与结构？需与 login-service 等消费方约定。
- 是否支持同一机器多实例？instanceId 需唯一，可用 host:port:pid 或 UUID。
