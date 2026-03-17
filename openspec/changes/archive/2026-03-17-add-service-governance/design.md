# 服务治理（配置中心与服务发现）(design.md)

## Context

- **当前状态**：各服务使用本地配置文件与环境变量，配置变更需重新部署。所有配置仅启动时加载，不可动态变更。Game 使用 Redis 手工注册，Gate→Login/Center 地址硬编码，缺乏统一服务发现。
- **问题**：配置与代码耦合，变更成本高；无法运行时调参；服务地址硬编码，扩容与故障转移困难。
- **约束**：不使用 Spring Cloud；通过 Nacos SDK 直接集成，非 Spring Cloud Alibaba；各服务为 plain Java + Netty/gRPC/HTTP 框架。

## Goals / Non-Goals

**Goals:**
- 引入 Nacos 作为配置中心与服务注册发现一体化方案
- 通过 Nacos SDK 监听配置变更回调，实现运行时热更新（限流阈值、日志级别、心跳间隔等）
- 统一服务注册与发现，替代 Gate→Login/Center 硬编码地址
- Game 接入 Nacos 注册中心，替代 Redis 手工注册

**Non-Goals:**
- 不引入 Spring Cloud、Spring Cloud Alibaba、@RefreshScope 等 Spring 能力
- 不实现配置加密（由 add-multi-env-and-secrets 或密钥管理负责）
- 不改变各服务既有业务协议

## Decisions

1. **Nacos SDK 集成方式**
   - 各服务通过 Nacos Java SDK（nacos-client）拉取配置、注册实例、订阅服务列表；使用 ConfigService、NamingService API；在应用启动时初始化，JVM shutdown hook 中注销。
   - **理由**：与 Spring 解耦，适用于 plain Java 服务。

2. **配置热更新**
   - 使用 ConfigService.addListener 注册配置变更回调；回调内解析新配置并更新内存中的运行时参数（如限流阈值、心跳间隔）；通过单例或 DI 容器（如 Guice）持有的配置对象进行更新，避免重启。
   - **理由**：满足运维调参需求，无需重新部署。

3. **服务注册**
   - Game、Login、Center 在启动完成后调用 NamingService.registerInstance 注册；健康检查通过后注册，关闭时 deregisterInstance；Gate 不对外暴露 HTTP/gRPC 服务则可不注册，或按需注册。
   - **理由**：统一由 Nacos 管理服务实例，替代 Redis 手工注册。

4. **服务发现**
   - Gate 通过 NamingService.selectInstances 获取 Login、Center 实例列表；使用负载均衡（随机/轮询）选择实例；定期或监听 Nacos 事件刷新实例列表；替代硬编码 IP:port。
   - **理由**：支持实例动态上下线，便于扩容与故障转移。

5. **配置命名与隔离**
   - DataId 与 Group 按服务、环境区分（如 gate-service-dev、game-service-prod）；敏感信息与业务配置分离，敏感项可继续使用环境变量或外部密钥管理。
   - **理由**：多环境隔离，降低配置泄露风险。

## Risks / Trade-offs

- **[风险]** Nacos 不可用导致启动失败 → 支持 fallback 到本地配置或环境变量；启动时 Nacos 连接失败可重试，超过重试次数再失败。
- **[权衡]** 配置热更新与业务线程并发 → 使用 volatile 或原子引用保存配置，避免读时并发修改；复杂对象需考虑拷贝或不可变。
- **[风险]** 服务发现列表短暂为空 → 保留上一次成功获取的列表作为缓存，Nacos 故障时继续使用；配合健康检查剔除不可用实例。

## Migration Plan

- **实现顺序**：先部署 Nacos 服务端 → 各服务集成 Nacos SDK（配置拉取）→ 实现配置热更新 → Game/Login/Center 服务注册 → Gate 服务发现替代硬编码 → 移除 Redis 注册逻辑。
- **部署**：Nacos 可 Docker/K8s 部署；各服务通过环境变量或本地配置指定 Nacos 地址；灰度切换，先双写（Redis + Nacos），再切流量。
- **回滚**：保留原有硬编码与 Redis 注册逻辑开关，配置切回即可回退。

## Open Questions

- 各服务是否已有 DI 容器（如 Guice）？配置热更新需与依赖注入协调，若无可引入轻量 Guice 或手动单例。
- Nacos 集群与持久化如何规划？生产建议 Nacos 集群 + MySQL 持久化。
