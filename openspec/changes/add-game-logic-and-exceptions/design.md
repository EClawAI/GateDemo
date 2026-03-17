# 游戏逻辑与异常处理 (design.md)

## Context

- **当前状态**：GameMessageHandler 中 battle.move、chat.message 为 stub 实现，仅日志和 TODO；gate-service 异常处理为零散的 try/catch + log；login-service、center-service 的 REST 接口无全局异常处理，错误响应格式不统一。
- **问题**：无法验证 WebSocket→Gate→gRPC→Game 全链路；异常时可能仅日志无客户端反馈；REST 错误格式各异，前端难以统一处理。
- **约束**：不使用 Spring 的 @ControllerAdvice 等；使用 Netty Handler 的 exceptionCaught、HTTP Filter/Interceptor 等纯 Java/框架原生能力。

## Goals / Non-Goals

**Goals:**
- 实现 GameMessageHandler 的 echo、broadcast 示例，替换 battle.move、chat.message 的 stub
- 在 Netty Handler 增加统一 exceptionCaught 异常捕获（记录 + 关闭连接或发送错误响应）
- login/center 添加 HTTP Filter/Interceptor 实现全局异常处理，返回统一 JSON 错误格式

**Non-Goals:**
- 不实现完整战斗或聊天业务，仅示例级别
- 不引入 Spring Boot 或 Spring MVC 异常处理
- 不改变 gRPC 或 WebSocket 协议定义

## Decisions

1. **echo/broadcast 示例实现**
   - echo：收到消息后原样回发给发送者；broadcast：收到消息后向同房间/同游戏内所有在线玩家转发。
   - **理由**：可验证 WebSocket→Gate→gRPC→Game 全链路；实现简单，便于测试；为后续 battle.move、chat.message 提供模板。

2. **battle.move、chat.message 替换**
   - battle.move：可解析移动指令并转发给 Game 逻辑层，或先实现为「收到后 broadcast 给同房间」的简化版；chat.message：解析聊天内容并 broadcast 给同房间。
   - **理由**：移除 stub，证明链路可用；具体业务规则可在后续迭代细化。

3. **Netty exceptionCaught 统一处理**
   - 在 WebSocket/TCP Handler 的 exceptionCaught 中：记录异常日志（含 channel 信息）；向客户端发送错误响应（若 channel 仍可写）；关闭连接（若不可恢复）。
   - **理由**：避免异常吞没；给客户端明确反馈；防止异常连接占用资源。

4. **REST 全局异常处理**
   - 在 login-service、center-service 的 HTTP 框架中，使用 Filter 或 Interceptor 捕获未处理异常；返回统一 JSON 格式：如 `{"code":500,"message":"内部错误","detail":"..."}`；设置合适的 HTTP 状态码。
   - **理由**：各服务可能使用不同 HTTP 框架（如 JAX-RS、纯 Servlet），需适配对应机制，保持框架无关性。

5. **统一错误格式**
   - 定义错误 JSON 结构：code（业务或 HTTP 状态码）、message（可展示给用户）、detail（可选，调试用）；在所有 REST 错误响应中遵循该格式。
   - **理由**：前端可统一解析；便于问题排查。

## Risks / Trade-offs

- **[风险]** exceptionCaught 中写响应可能再次抛异常 → 对 write 操作做 try-catch，失败则直接关闭；避免在 exceptionCaught 中执行复杂逻辑。
- **[权衡]** 关闭连接可能影响用户体验 → 对于可恢复错误（如参数校验失败），可先发送错误响应再保持连接；对于不可恢复错误，关闭更安全。
- **[风险]** 不同 HTTP 框架的 Filter/Interceptor 实现方式不同 → 在各自服务中按框架文档实现；抽象统一错误格式接口，便于复用。

## Migration Plan

- **实现顺序**：先实现 Netty exceptionCaught → 再实现 echo/broadcast 及 battle.move、chat.message 替换 → 最后为 login/center 添加 REST 全局异常处理。
- **部署**：无数据迁移；观察异常日志与客户端错误响应是否正常。
- **回滚**：恢复 stub 或移除 exceptionCaught 中的关闭逻辑，可快速回退。

## Open Questions

- battle.move、chat.message 的「房间」或「广播域」如何定义？需与 Game 端协议约定。
- 错误 code 的枚举或规范？可先使用 HTTP 状态码 + 简单 message，后续再细化业务错误码体系。
