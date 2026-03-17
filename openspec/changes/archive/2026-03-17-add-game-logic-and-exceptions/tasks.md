## 1. GameMessageHandler echo/broadcast

- [x] 1.1 实现 echo 消息类型：收到后原样回发给发送者
- [x] 1.2 实现 broadcast 消息类型：收到后转发给同房间/同游戏内所有在线玩家
- [x] 1.3 定义或复用「房间」「广播域」的获取逻辑（从 Session、Game 上下文等）
- [x] 1.4 验证 WebSocket→Gate→gRPC→Game 全链路可收发 echo/broadcast

## 2. battle.move 与 chat.message 替换 stub

- [x] 2.1 将 battle.move 的 stub 替换为实际逻辑（如解析坐标并 broadcast 或转发给 Game）
- [x] 2.2 将 chat.message 的 stub 替换为实际逻辑（如解析内容并 broadcast）
- [x] 2.3 移除 TODO 与仅日志的占位实现

## 3. Netty exceptionCaught

- [ ] 3.1 在 WebSocket Handler 的 exceptionCaught 中增加统一处理
- [ ] 3.2 在 TCP Handler（若有）的 exceptionCaught 中增加统一处理
- [ ] 3.3 实现：记录日志 → 若可写则发送错误响应 → 不可恢复则关闭连接
- [ ] 3.4 定义错误响应的协议格式（与业务消息格式一致或单独错误消息类型）

## 4. login-service REST 全局异常处理

- [x] 4.1 根据 login-service 使用的 HTTP 框架，添加 Filter 或 Interceptor
- [x] 4.2 在捕获异常时构造统一 JSON 格式并返回
- [x] 4.3 按异常类型设置 HTTP 状态码（400、401、500 等）

## 5. center-service REST 全局异常处理

- [x] 5.1 根据 center-service 使用的 HTTP 框架，添加 Filter 或 Interceptor
- [x] 5.2 复用与 login-service 相同的错误 JSON 格式
- [x] 5.3 验证未捕获异常时能返回统一错误响应
