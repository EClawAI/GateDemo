## Why

当前游戏架构存在以下问题：

1. **客户端获取版本信息困难**：客户端需要知道当前版本才能进行后续操作
2. **Gate连接信息不透明**：客户端不知道应该连接哪个Gate
3. **游戏服路由不明确**：客户端不知道应该连接哪个Game

引入CenterService（中心服务）可以解决以上问题：
- 提供HTTP接口供客户端一次性获取版本、Gate信息和gameId
- 返回gameId供客户端连接对应的Game服

## What Changes

1. **新增CenterService**：作为入口服务，提供HTTP接口
2. **版本检查接口**：返回当前客户端版本和Gate信息
3. **游戏服路由接口**：返回gameId（新角色返回推荐服，已登录返回上次登录的服）

## Capabilities

### New Capabilities
- `capability-center-service`: 中心服务能力

### Modified Capabilities
- 无

## Impact

- 影响新增 `center-service` 模块
- 影响 `gate-service` 新增状态上报
- 影响 `game-service` 新增登录记录

## 客户端使用流程（优化后）

```
客户端启动
     ↓
请求CenterService（一次请求）
     ↓
返回：版本 + Gate信息 + gameId
     ↓
客户端连接Gate → 转发到对应Game
```

## 一次请求设计

### 请求

```
POST /api/v1/enter
Content-Type: application/json

{
  "playerId": 12345,     // 可选，登录后带
  "token": "xxx",         // 可选，登录后带
  "deviceId": "xxx"      // 设备ID
}
```

### 响应

```json
{
  "code": 0,
  "data": {
    "version": {
      "version": "1.0.0",
      "minVersion": "1.0.0",
      "forceUpdate": false,
      "updateUrl": "https://example.com/update"
    },
    "gate": {
      "id": "gate-01",
      "host": "gate1.example.com",
      "port": 8888
    },
    "gameId": 1001,
    "gameHost": "game1.example.com",
    "gamePort": 9090
  }
}
```

### 路由逻辑

| 场景 | 返回 |
|------|------|
| 未登录（首次） | 推荐服gameId |
| 已登录，返回登录 | 上次登录的gameId |
| 上次Game已下线 | 推荐服gameId |
