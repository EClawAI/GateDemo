## Why

当前存在以下韧性不足问题：
- 各组件有销毁回调但无统一编排和 connection draining
- gRPC 重连使用 new Thread + 固定延迟，无退避策略
- PlayerService 对在线玩家数无上限控制，存在资源耗尽风险

## What Changes

提升系统韧性与稳定性：
- 配置 graceful shutdown，实现 stop accept → drain → close 的优雅关闭流程
- gRPC 重连改用 ScheduledExecutor + 指数退避
- WebSocket handler 增加连接数上限检查

## 核心功能

1. **优雅关闭 (stop accept → drain → close)**
   - 统一编排各组件销毁顺序
   - 停止接受新连接后 draining 已有连接
   - 等待请求处理完成再关闭

2. **指数退避重连**
   - gRPC 重连改用 ScheduledExecutor
   - 失败后按指数退避延迟重试，避免风暴

3. **连接数上限控制**
   - WebSocket handler 增加最大连接数检查
   - 超限拒绝新连接，保护服务稳定

## Impact

- 影响 `gate-service` 优雅关闭、重连策略、连接数限制
- 影响 `game-service` 优雅关闭、gRPC 重连策略
