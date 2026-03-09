## 1. ZooKeeper集成

- [ ] 1.1 添加ZooKeeper依赖 (Curator Framework)
- [ ] 1.2 创建ZooKeeper配置类
- [ ] 1.3 创建ZooKeeper连接池
- [ ] 1.4 实现连接重试机制

## 2. 服务注册

- [ ] 2.1 创建ServiceRegistry接口
- [ ] 2.2 实现ZooKeeperServiceRegistry
- [ ] 2.3 Gate启动时注册自身
- [ ] 2.4 Gate关闭时注销自身
- [ ] 2.5 实现心跳维持

## 3. 服务发现

- [ ] 3.1 创建ServiceDiscovery接口
- [ ] 3.2 实现ZooKeeperServiceDiscovery
- [ ] 3.3 实现Watch监听Game节点变化
- [ ] 3.4 Game服务上线时自动建立连接

## 4. 大规模场景支持（10k+节点）

- [ ] 4.1 实现连接数限制（默认100）
- [ ] 4.2 实现一致性哈希分片策略
- [ ] 4.3 实现渐进式重连（批量+间隔）
- [ ] 4.4 实现本地缓存
- [ ] 4.5 实现ZooKeeper限流

## 5. 路由策略

- [ ] 5.1 创建RoutingStrategy接口
- [ ] 5.2 实现ConsistentHashRouting一致性哈希路由
- [ ] 5.3 实现分片映射表
- [ ] 5.4 实现路由失败重试
- [ ] 5.5 实现健康Game故障转移

## 6. 动态连接管理

- [ ] 6.1 修改GameGrpcClientPool，支持动态添加/移除连接
- [ ] 6.2 实现Game服务下线时断开连接
- [ ] 6.3 实现连接健康检测

## 7. Game服务修改

- [ ] 7.1 Game服务注册到ZooKeeper
- [ ] 7.2 Game服务监听Gate节点变化
- [ ] 7.3 允许Gate连接/断开连接

## 8. 测试

- [ ] 8.1 单元测试服务注册/发现
- [ ] 8.2 单元测试一致性哈希路由
- [ ] 8.3 单元测试分片策略
- [ ] 8.4 集成测试Gate↔Game动态连接
- [ ] 8.5 压力测试（模拟10k+节点）
