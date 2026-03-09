## Why

当前Gate服务与Game服务之间的连接是静态配置的：
- Gate启动时从配置文件中读取Game服务列表
- Gate与每个Game建立固定的gRPC连接

这种方式在K8s部署时存在问题：
1. **Pod扩缩容**：Gate Pod数量变化时，需要重新建立与所有Game的连接
2. **平滑更新**：Gate滚动更新时，旧Pod断开连接，新Pod需要重新建立连接
3. **Game服务变化**：Game服务扩缩容时，Gate无法感知

引入服务发现机制可以解决以上问题：
- Gate可以动态感知Game服务的上线/下线
- Gate可以动态注册/注销自身，供Game服务发现
- 实现真正的无状态Gate

**特殊考虑**：
当Game服务节点数量超过10k时，需要特别设计以避免压力：
1. 单个Gate不应该连接所有Game节点（资源限制）
2. Gate滚动更新时不应同时触发大量ZooKeeper操作
3. 需要实现连接池限制和负载策略

**负载均衡考虑**：
当每个Gate只连接部分Game时，需要解决：
1. 玩家如何知道连接哪个Gate？
2. Gate如何知道将玩家路由到哪个Game？
3. 如何保证同一玩家始终路由到同一Game（会话保持）？

## What Changes

1. **引入服务发现**：使用ZooKeeper/Nacos实现服务注册与发现
2. **动态连接管理**：Gate根据服务发现动态建立/断开与Game的连接
3. **连接数限制**：限制单个Gate连接的Game数量（可配置）
4. **负载策略**：实现连接分配策略，避免单点压力
5. **健康检测**：Gate和Game相互感知对端的健康状态
6. **路由策略**：实现Gate层负载均衡，支持玩家路由

## Capabilities

### New Capabilities
- `capability-service-discovery`: 服务发现能力
- `capability-routing`: 负载路由能力

### Modified Capabilities
- `capability-grpc-stream`: 从静态配置改为动态发现

## Impact

- 影响 `gate-service` 的 `grpc/GameGrpcClientPool` 类
- 影响 `gate-service` 新增服务发现组件
- 影响 `gate-service` 新增路由组件
- 影响 `game-service` 新增服务注册逻辑
