# Service Governance Plan: Nacos Integration

本文档描述 GateDemo 项目从现有 Redis 手工注册向 Nacos 服务治理的迁移方案。

## 1. 配置中心（Configuration Center）

### 1.1 Nacos SDK 集成

- 引入 `nacos-client` 依赖，各服务（Gate、Login、Center、Game）在启动时初始化 `ConfigService`
- 配置拉取：从 Nacos 拉取 DataId/Group 配置
  - DataId 与 Group 按服务、环境区分，如：`gate-service.yml` / `login-service-prod`
- Fallback：Nacos 不可用时使用本地 `application.yml` 或环境变量
- 在 JVM shutdown hook 中注销 Nacos 客户端、释放资源

### 1.2 配置热更新

- 使用 `ConfigService.addListener` 注册配置变更回调
- 回调内解析新配置，更新内存中的运行时参数：限流阈值、日志级别、心跳间隔等
- 使用 `volatile` 或原子引用保存配置，避免读时并发修改
- 与现有配置对象或 DI 容器协调，确保业务代码读取到最新值

---

## 2. 服务注册（Service Registration）

### 2.1 Nacos Naming Service

- Game 启动完成后调用 `NamingService.registerInstance` 注册到 Nacos
- 健康检查通过后再注册；关闭时调用 `deregisterInstance`
- 替代原有 Redis 手工注册逻辑

### 2.2 Gate 服务发现

- Gate 通过 `NamingService.selectInstances` 获取 Login、Center 实例列表
- 实现负载均衡（随机或轮询）选择实例
- 定期或监听 Nacos 事件刷新实例列表；Nacos 故障时保留上一次成功列表作为缓存
- 移除 Login、Center 的硬编码地址，改为动态发现

---

## 3. 迁移步骤（从 Redis 到 Nacos）

### 3.1 双写阶段

- 保留原有 Redis 注册逻辑，同时向 Nacos 注册
- 配置开关控制：`discovery.backend=redis|nacos|dual`
- 在 `dual` 模式下，Gate 仍从 Redis 读取实例，Nacos 仅作为数据同步目标

### 3.2 流量切换

- 切换 `discovery.backend=nacos`，Gate 从 Nacos 拉取实例
- 观察稳定性与延迟，必要时可快速切回 Redis

### 3.3 清理

- 移除 Redis 注册/发现相关代码
- 移除双写逻辑与开关

---

## 4. 生产配置建议

- **Nacos 地址**：通过环境变量 `NACOS_SERVER_ADDR` 配置，如 `nacos.example.com:8848`
- **DataId/Group 命名规范**：`{service-name}-{env}.yml`，Group 建议 `DEFAULT_GROUP` 或按业务划分
- **持久化**：生产环境建议 Nacos 集群 + MySQL 持久化
