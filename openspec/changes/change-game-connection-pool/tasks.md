## 1. Discovery 使用连接池

- [x] 1.1 在 GameDiscoveryService 中注入 GameGrpcClientPool，移除对 gameChannels 的独立维护
- [x] 1.2 connectToGame 改为调用 GameGrpcClientPool.addConnection；disconnectFromGame 改为调用 GameGrpcClientPool.removeConnection
- [x] 1.3 删除 GameDiscoveryService 中的 gameChannels 与 getChannel(int gameId)，确保所有连接仅由连接池提供

## 2. 统一连接入口与配置

- [x] 2.1 确认所有访问 Game gRPC 的调用均经 GameGrpcClientPool（PlayerService 等已使用池则仅做核对；若有使用 Discovery.getChannel 的调用则改为使用池）
- [x] 2.2 在 gate 配置中增加可选连接池参数（如 keepAlive 时间、超时），并在 GameGrpcClientPool 建连时使用（若已有则仅文档化）

## 3. 健康检查与重连

- [x] 3.1 确认 GrpcHeartbeatManager 与 GameGrpcClientPool 内的心跳/Stream onError 重连为唯一健康与重连路径，Discovery 不再重复维护连接状态
- [x] 3.2 心跳或 Stream 失败时确保失效连接从池中移除并可被重连或由 Discovery 再次注册触发重建（验证现有 scheduleReconnect / addConnection 行为）

## 4. 文档与测试

- [x] 4.1 在配置说明或 README 中文档化：Discovery 与 gate.games 并存时以何者为准（或明确允许重叠时的策略）
- [x] 4.2 补充或调整测试：Discovery 启用时注册/注销触发 pool.addConnection/removeConnection，且无独立 gameChannels 泄漏
