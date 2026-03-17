# P011: 韧性提升

## 1. gRPC 重连改造

- [x] 1.1 将现有 new Thread + Thread.sleep 重连逻辑替换为 ScheduledExecutorService
- [x] 1.2 实现指数退避：初始 delay → 2x → 4x → … 直至 maxDelay
- [ ] 1.3 配置 maxRetries 最大重试次数，超限后停止重连
- [x] 1.4 在 JVM shutdown 时取消 ScheduledExecutorService 的调度任务

## 2. WebSocket 连接数限制

- [x] 2.1 新增 gate.max-connections 配置项
- [x] 2.2 在 WebSocket handler 的 channelActive 中获取当前连接计数（原子变量或共享计数器）
- [x] 2.3 若超过上限则拒绝连接，关闭 channel（达上限时直接关闭，握手前无法返回结构化错误）

## 3. 优雅关闭编排

- [x] 3.1 使用 @PreDestroy 编排 GracefulShutdownManager 优先执行
- [x] 3.2 阶段一：停止接受新连接（Netty Server 停止 bind）
- [x] 3.3 阶段二：等待已有连接 drain（通知玩家 + 超时）
- [x] 3.4 阶段三：由 NettyWebSocketServer @PreDestroy 关闭 EventLoopGroup 等资源
- [x] 3.5 设置 30s drain 超时，超时后由 Netty 强制关闭剩余连接

## 4. 配置与回滚

- [x] 4.1 新增 gate.shutdown.enabled 开关，支持回退原有关闭行为
- [x] 4.2 将 initialDelay、maxDelay、multiplier 等参数可配置化
