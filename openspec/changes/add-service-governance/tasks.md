# P019: 服务治理

## 1. Nacos SDK 集成

- [ ] 1.1 引入 nacos-client 依赖，各服务在启动时初始化 ConfigService、NamingService
- [ ] 1.2 实现配置拉取：从 Nacos 拉取 DataId/Group 配置，DataId 与 Group 按服务、环境区分
- [ ] 1.3 支持 fallback：Nacos 不可用时使用本地配置或环境变量
- [ ] 1.4 在 JVM shutdown hook 中注销 Nacos 客户端、释放资源

## 2. 配置热更新

- [ ] 2.1 使用 ConfigService.addListener 注册配置变更回调
- [ ] 2.2 回调内解析新配置，更新内存中的运行时参数（限流阈值、日志级别、心跳间隔等）
- [ ] 2.3 使用 volatile 或原子引用保存配置，避免读时并发修改
- [ ] 2.4 与现有配置对象或 DI 容器协调，确保业务代码读取到最新值

## 3. Game 服务注册

- [ ] 3.1 Game 启动完成后调用 NamingService.registerInstance 注册到 Nacos
- [ ] 3.2 健康检查通过后再注册；关闭时调用 deregisterInstance
- [ ] 3.3 替代原有 Redis 手工注册逻辑；可先双写（Redis + Nacos），再切流量

## 4. Gate 服务发现

- [ ] 4.1 Gate 通过 NamingService.selectInstances 获取 Login、Center 实例列表
- [ ] 4.2 实现负载均衡（随机或轮询）选择实例
- [ ] 4.3 定期或监听 Nacos 事件刷新实例列表；Nacos 故障时保留上一次成功列表作为缓存
- [ ] 4.4 移除 Login、Center 的硬编码地址，改为动态发现

## 5. 迁移与回滚

- [ ] 5.1 保留原有硬编码与 Redis 注册逻辑开关，支持配置切回
- [ ] 5.2 文档说明 Nacos 地址配置、DataId/Group 命名规范；生产建议 Nacos 集群 + MySQL 持久化
