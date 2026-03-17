## 1. GateClusterManager 接入

- [x] 1.1 在 gate-service 启动流程中初始化 GateClusterManager（Guice 或手动）
- [x] 1.2 启动完成后调用 GateClusterManager 注册本实例到 Redis
- [x] 1.3 使用 ScheduledExecutorService 实现周期性心跳（如 30 秒）
- [x] 1.4 在关闭时停止心跳并可选显式注销 Redis 中的实例信息

## 2. NettyTcpServer 接入

- [x] 2.1 在配置中增加 gate.tcp.enabled、gate.tcp.port
- [x] 2.2 在 gate-service 启动流程中根据 gate.tcp.enabled 判断是否初始化 NettyTcpServer
- [x] 2.3 启用时创建并启动 NettyTcpServer，监听 gate.tcp.port
- [x] 2.4 在关闭时关闭 NettyTcpServer 并释放 EventLoopGroup 等资源

## 3. TCP 消息复用 MessageDispatcher

- [x] 3.1 确认 NettyTcpServer 的 pipeline 中消息解码后的数据结构与 WebSocket 兼容
- [x] 3.2 将解码后的消息交由 MessageDispatcher 处理
- [ ] 3.3 验证 echo、broadcast 等消息类型在 TCP 路径下正常工作

## 4. DI 与启动顺序

- [x] 4.1 若使用 Guice，在模块中绑定 NettyTcpServer、GateClusterManager 并在启动时调用
- [x] 4.2 若不使用 Guice，在 main() 中手动创建并注册到启动器
- [x] 4.3 确保 GateClusterManager 注册先于或与 NettyTcpServer 启动并行，不阻塞主流程

## 5. 配置与文档

- [x] 5.1 文档化 gate.tcp.enabled、gate.tcp.port、Gate 实例 Redis key 格式
- [ ] 5.2 验证 login-service 能正确从 Redis 读取 Gate 实例列表（若已有逻辑）
- [ ] 5.3 补充 TCP 客户端连接与消息格式说明
