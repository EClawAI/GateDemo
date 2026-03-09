## Why

当前游戏架构存在以下问题：

1. **客户端获取版本信息困难**：客户端需要知道当前版本才能进行后续操作
2. **Gate连接信息不透明**：客户端不知道应该连接哪个Gate
3. **游戏服路由不明确**：客户端不知道应该连接哪个Game

引入CenterService（中心服务）可以解决以上问题：
- 提供HTTP接口供客户端获取版本信息和Gate连接信息
- 返回gameId供客户端连接对应的Game服

## What Changes

1. **新增CenterService**：作为入口服务，提供HTTP接口
2. **版本检查接口**：返回当前客户端版本
3. **Gate信息接口**：返回可用的Gate服务器列表
4. **游戏服路由接口**：返回gameId（新角色返回推荐服，已登录返回上次登录的服）

## Capabilities

### New Capabilities
- `capability-center-service`: 中心服务能力

### Modified Capabilities
- 无

## Impact

- 影响新增 `center-service` 模块
- 影响 `gate-service` 新增状态上报
- 影响 `game-service` 新增登录记录

## 客户端使用流程

```
客户端启动
     ↓
请求CenterService获取版本和Gate信息
     ↓
获取版本 → 版本不匹配提示更新
获取版本 → 版本匹配继续
     ↓
请求CenterService获取gameId
     ↓
新角色 → 返回推荐服gameId
已登录 → 返回上次登录的gameId
     ↓
客户端连接Gate → Gate转发到对应Game
```
