## 1. ZooKeeper集成

- [ ] 1.1 添加ZooKeeper依赖 (Curator)
- [ ] 1.2 创建ZooKeeper配置类
- [ ] 1.3 创建ZooKeeper连接池

## 2. 服务注册

- [ ] 2.1 创建ServiceRegistry接口
- [ ] 2.2 实现ZooKeeperServiceRegistry
- [ ] 2.3 Gate启动时注册自身
- [ ] 2.4 Gate关闭时注销自身

## 3. 服务发现

- [ ] 3.1 创建ServiceDiscovery接口
- [ ] 3.2 实现ZooKeeperServiceDiscovery
- [ ] 3.3 实现Watch监听Game节点变化
- [ ] 3.4 Game服务上线时自动建立连接

## 4. 动态连接管理

- [ ] 4.1 修改GameGrpcClientPool，支持动态添加/移除连接
- [ ] 4.2 实现Game服务下线时断开连接
- [ ] 4.3 实现连接健康检测

## 5. Game服务修改

- [ ] 5.1 Game服务注册到ZooKeeper
- [ ] 5.2 Game服务监听Gate节点变化
- [ ] 5.3 允许Gate连接/断开连接

## 6. 测试

- [ ] 6.1 单元测试服务注册/发现
- [ ] 6.2 集成测试Gate↔Game动态连接
- [ ] 6.3 K8s模拟测试（可选）
