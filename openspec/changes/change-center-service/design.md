## Context

CenterService作为中心服务，提供HTTP接口给客户端调用。

## Architecture

```
┌─────────────┐     HTTP      ┌─────────────┐
│   客户端    │ ◄──────────► │  CenterService │
└─────────────┘              └───────┬─────┘
                                           │
                                           │ 查询
                                           ▼
                                    ┌─────────────┐
                                    │    Redis    │
                                    └─────────────┘
```

## 功能模块

### 1. 版本检查

返回当前服务端支持的客户端版本信息。

**接口**: `GET /api/v1/version`

**响应**:
```json
{
  "code": 0,
  "data": {
    "version": "1.0.0",
    "minVersion": "1.0.0",
    "forceUpdate": false,
    "updateUrl": "https://example.com/update"
  }
}
```

### 2. Gate信息

返回可用的Gate服务器列表。

**接口**: `GET /api/v1/gate/list`

**响应**:
```json
{
  "code": 0,
  "data": {
    "gates": [
      {"id": "gate-01", "host": "gate1.example.com", "port": 8888, "online": 100},
      {"id": "gate-02", "host": "gate2.example.com", "port": 8888, "online": 50}
    ]
  }
}
```

### 3. 游戏服路由

返回玩家应该连接的Game服gameId。

**接口**: `POST /api/v1/game/route`

**请求**:
```json
{
  "playerId": 12345,
  "token": "xxx"
}
```

**响应**:
```json
{
  "code": 0,
  "data": {
    "gameId": 1001,
    "gameHost": "game1.example.com",
    "gamePort": 9090
  }
}
```

### 4. 推荐服逻辑

**新角色（首次登录）**：
- 从配置中获取推荐服列表
- 选择负载最低的推荐服
- 返回推荐服的gameId

**已登录玩家**：
- 从Redis查询上次登录的gameId
- 检查该Game服是否在线
- 如果在线，返回原gameId
- 如果不在线，返回推荐服

### 5. 登录记录

当玩家登录Game服后，需要通知CenterService更新登录记录。

**接口**: `POST /api/v1/game/login-record`

**请求**:
```json
{
  "playerId": 12345,
  "gameId": 1001
}
```

## 数据模型

### Redis Key设计

```
# 玩家登录记录
player:login:{playerId} -> {gameId}:{timestamp}

# Gate在线人数
gate:online:{gateId} -> count

# 推荐服配置
config:recommend:games -> [gameId1, gameId2, gameId3]
```

### 推荐服配置

```yaml
center:
  recommend:
    games:
      - gameId: 1001
        name: "推荐服1"
        priority: 1
      - gameId: 1002
        name: "推荐服2"  
        priority: 2
```

## Gate状态上报

Gate服务需要定期上报状态到CenterService：

**接口**: `POST /api/v1/gate/heartbeat`

**请求**:
```json
{
  "gateId": "gate-01",
  "host": "192.168.1.1",
  "port": 8888,
  "online": 100
}
```

## 配置示例

```yaml
server:
  port: 8080

center:
  recommend:
    games:
      - gameId: 1001
        name: "推荐服1"
      - gameId: 1002
        name: "推荐服2"
      
redis:
  host: localhost
  port: 6379
```

## 实现

### 模块结构

```
center-service/
├── controller/
│   ├── VersionController     # 版本接口
│   ├── GateController       # Gate接口
│   └── GameController       # 游戏服接口
├── service/
│   ├── VersionService       # 版本服务
│   ├── GateService         # Gate服务
│   └── GameRouteService    # 路由服务
├── model/
│   ├── VersionInfo
│   ├── GateInfo
│   └── GameRoute
└── config/
```

### 核心接口

1. **VersionController**
   - `GET /api/v1/version` - 获取版本信息

2. **GateController**
   - `GET /api/v1/gate/list` - 获取Gate列表
   - `POST /api/v1/gate/heartbeat` - Gate心跳

3. **GameController**
   - `POST /api/v1/game/route` - 获取gameId
   - `POST /api/v1/game/login-record` - 记录登录
