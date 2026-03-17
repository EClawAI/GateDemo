## Why

当前测试覆盖率过低：
- 整体覆盖率约 5%，gate-service 仅有 2 个测试文件
- 远低于生产标准（一般要求 >60%）
- 核心路径缺乏测试，重构或修复容易引入回归

## What Changes

为核心路径补充单元测试：
- gate-service 核心类：PlayerService、ConnectionManager、MessageDispatcher、RateLimiter、CircuitBreaker、TokenService
- game-service 核心类：GameMessageHandler、GameStatusService
- login/center Controller 层测试

## 核心功能

1. **gate-service 核心测试**
   - PlayerService：玩家注册、查询、踢出
   - ConnectionManager：连接建立、断开、按玩家查找
   - MessageDispatcher：消息路由与分发
   - RateLimiter：限流逻辑与边界
   - CircuitBreaker：状态转换与熔断
   - TokenService：token 生成与校验

2. **game-service 测试**
   - GameMessageHandler：消息处理与响应
   - GameStatusService：状态更新与查询

3. **login/center Controller 测试**
   - HTTP 接口契约测试
   - 请求/响应序列化与校验

## Impact

- 影响全部服务 `test` 目录
- gate-service、game-service、login-service、center-service 均新增或补充测试类
