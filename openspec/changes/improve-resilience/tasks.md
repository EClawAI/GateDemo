# P011: 韧性提升

## 1. gRPC 重连改造

- [ ] 1.1 将现有 new Thread + Thread.sleep 重连逻辑替换为 ScheduledExecutorService
- [ ] 1.2 实现指数退避：初始 delay → 2x → 4x → … 直至 maxDelay
- [ ] 1.3 配置 maxRetries 最大重试次数，超限后停止重连
- [ ] 1.4 在 JVM shutdown 时取消 ScheduledExecutorService 的调度任务

## 2. WebSocket 连接数限制

- [ ] 2.1 新增 gate.ws.max-connections 配置项
- [ ] 2.2 在 WebSocket handler 的 channelActive 中获取当前连接计数（原子变量或共享计数器）
- [ ] 2.3 若超过上限则拒绝连接，关闭 channel 并返回「服务繁忙」类错误响应

## 3. 优雅关闭编排

- [ ] 3.1 注册 JVM shutdown hook，统一编排各组件关闭顺序
- [ ] 3.2 阶段一：停止接受新连接（Netty Server 停止 bind）
- [ ] 3.3 阶段二：等待已有连接 drain（无活跃 write 或超时）
- [ ] 3.4 阶段三：关闭 EventLoopGroup、gRPC Channel、Redis 客户端等资源
- [ ] 3.5 设置 30s 总超时，超时后强制关闭剩余连接

## 4. 配置与回滚

- [ ] 4.1 新增 gate.graceful-shutdown.enabled 开关，支持回退原有关闭行为
- [ ] 4.2 将 initialDelay、maxDelay、maxRetries 等参数可配置化
