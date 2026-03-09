## Why

客户端获取配置信息后，需要请求登录服务获取Gate地址和gameId。

LoginService负责：
1. **玩家认证**：验证玩家身份
2. **Gate分配**：返回可用的Gate服务器
3. **Game路由**：返回玩家应该连接的Game服

## What Changes

1. **新增LoginService**：作为登录服务
2. **Gate分配**：返回可用的Gate服务器
3. **Game路由**：返回gameId（新角色返回推荐服，已登录返回上次登录的服）
4. **登录记录**：记录玩家登录信息

## Capabilities

### New Capabilities
- `capability-login-service`: 登录服务能力

## Impact

- 影响新增 `login-service` 模块
- 影响 `gate-service` 新增状态上报
- 影响 `game-service` 新增登录记录

## 客户端使用流程

```
请求CenterService获取配置
     ↓
版本通过 → 请求LoginService
     ↓
返回：Gate地址 + gameId
     ↓
连接Gate → 转发到Game
```

## 接口设计

### 登录接口

**接口**: `POST /api/v1/login`

**请求**:
```json
{
  "playerId": 12345,
  "token": "xxx",
  "deviceId": "xxx"
}
```

**响应**:
```json
{
  "code": 0,
  "data": {
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

### 登录记录接口

**接口**: `POST /api/v1/game/login-record`

玩家登录Game服后通知LoginService更新登录记录。

**请求**:
```json
{
  "playerId": 12345,
  "gameId": 1001
}
```

### Gate心跳接口

**接口**: `POST /api/v1/gate/heartbeat`

Gate服务定期上报状态。

**请求**:
```json
{
  "gateId": "gate-01",
  "host": "192.168.1.1",
  "port": 8888,
  "online": 100
}
```

## 路由逻辑

| 场景 | 决策 | 说明 |
|------|------|------|
| 未登录 | 推荐服 | 从配置中选择负载最低的推荐服 |
| 已登录 | 上次服 | 返回Redis中记录的gameId |
| 上次Game已下线 | 推荐服 | 返回推荐服 |
