## 1. gate-service 核心类测试

- [ ] 1.1 创建 PlayerServiceTest，覆盖玩家注册、查询、踢出及异常路径
- [ ] 1.2 创建 ConnectionManagerTest，覆盖连接建立、断开、按玩家查找
- [ ] 1.3 创建 MessageDispatcherTest，覆盖消息路由与分发、未注册类型处理
- [ ] 1.4 创建 RateLimiterTest，覆盖 tryAcquire 在窗口内/超限、reset、滑动窗口
- [ ] 1.5 创建 CircuitBreakerTest，覆盖 CLOSED→OPEN、OPEN→HALF_OPEN→CLOSED、allowRequest 行为
- [ ] 1.6 创建 TokenServiceTest，覆盖 token 生成与校验（或 mock 依赖）

## 2. game-service 核心类测试

- [ ] 2.1 创建 GameMessageHandlerTest，覆盖消息处理与响应格式
- [ ] 2.2 创建 GameStatusServiceTest，覆盖状态更新与查询

## 3. login/center Controller 测试

- [ ] 3.1 为 login-service 的 HTTP 接口创建测试（请求解析、响应序列化、业务 mock）
- [ ] 3.2 为 center-service 的 HTTP 接口创建测试（同上）

## 4. 覆盖率配置与验证

- [ ] 4.1 配置 JaCoCo（或等价工具）生成覆盖率报告
- [ ] 4.2 执行完整测试套件，确认 gate-service、game-service、login-service、center-service 覆盖率≥60%
- [ ] 4.3 在 CI 配置中加入覆盖率检查（可选，如低于阈值则失败）

## 5. 文档与整理

- [ ] 5.1 在 README 或开发文档中说明如何运行测试与查看覆盖率报告
- [ ] 5.2 确保所有新增测试可独立运行且不依赖外部服务
