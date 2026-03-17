## Why

当前存在以下问题：
- GameMessageHandler.handleGameMessage 中 battle.move 和 chat.message 处理为 stub，仅做日志输出和 TODO，无实际业务逻辑
- gate-service 异常处理为零散的 try/catch + log，缺乏统一处理机制
- REST 服务无全局异常处理器，错误响应格式不统一

## What Changes

完善游戏消息处理与统一异常处理：
- 实现 echo/broadcast 示例，证明 WebSocket→Gate→gRPC→Game 全链路可用
- 在 Netty Handler 增加统一 exceptionCaught 异常捕获
- login/center 添加 HTTP Filter/Interceptor 实现全局异常捕获，统一错误响应格式

## 核心功能

1. **GameMessageHandler 业务逻辑补全**
   - 实现 echo、broadcast 等示例消息处理
   - 替换 battle.move、chat.message 的 stub 实现

2. **Netty 统一异常处理**
   - Handler 层增加 exceptionCaught 钩子
   - 统一异常日志与客户端错误通知

3. **REST 全局异常处理**
   - 为 login-service、center-service 添加 HTTP Filter/Interceptor 全局异常处理
   - 统一 REST API 错误响应格式（JSON 错误码 + 消息）

## Impact

- 影响 game-service（GameMessageHandler 业务逻辑）
- 影响 gate-service（Netty 异常处理）
- 影响 login-service（全局异常处理 Filter）
- 影响 center-service（全局异常处理 Filter）
