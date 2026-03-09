## Context

LoginService作为登录服务，提供HTTP接口给客户端调用。

## Architecture

```
┌─────────────┐     HTTP      ┌─────────────┐
│   客户端    │ ◄──────────► │  LoginService │
└─────────────┘              └───────┬─────┘
                                           │
                                           │ 查询
                                           ▼
                                    ┌─────────────┐
                                    │    Redis    │
                                    └─────────────┘
```

## 核心接口

### 登录接口

**接口**: `POST /api/v1/login`

玩家请求登录，获取Gate地址和gameId。

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

## 路由决策

| 场景 | 决策 | 说明 |
|------|------|------|
| 未登录 | 推荐服 | 从配置中选择负载最低的推荐服 |
| 已登录 | 上次服 | 返回Redis中记录的gameId |
| 上次Game已下线 | 推荐服 | 返回推荐服 |

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
login:
  recommend:
    games:
      - gameId: 1001
        name: "推荐服1"
        priority: 1
      - gameId: 1002
        name: "推荐服2"  
        priority: 2
```

## 实现

### 模块结构

```
login-service/
├── controller/
│   ├── LoginController     # 登录接口
│   └── GateController     # Gate心跳
├── service/
│   ├── LoginService      # 登录服务
│   ├── GateService      # Gate服务
│   └── GameRouteService # 路由服务
└── config/
```

### 核心类

1. **LoginController**
   - `POST /api/v1/login` - 登录接口

2. **GateController**
   - `POST /api/v1/gate/heartbeat` - Gate心跳

3. **GameController**
   - `POST /api/v1/game/login-record` - 登录记录
