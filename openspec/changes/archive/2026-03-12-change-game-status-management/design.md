## Context

当前LoginService无法知道Game服务器的实际状态。引入Game状态管理机制，使LoginService能够根据Game的实际状态进行路由决策。

## Architecture

```
┌─────────────┐         ┌─────────────┐
│  Game       │ ──────► │   Redis     │
│  (心跳同步)  │         │ (存储状态)   │
└─────────────┘         └──────┬──────┘
                               │
                               │ 查询/写入
                               ▼
                        ┌─────────────┐
                        │   Login     │
                        │ (路由决策)   │
                        └─────────────┘
```

## 核心设计原则

**无需任何外部通知**：LoginService在返回gameId给客户端时，直接将该gameId记录为玩家的"最后登录服务器"，不需要Gate或Game通知登录结果。

## 登录流程

```
1. 玩家请求登录
       ↓
2. Login查询Redis获取Game状态
       ↓
3. Login决定路由到哪个Game（基于状态过滤）
       ↓
4. Login直接记录 playerId -> gameId（此时立即记录）
       ↓
5. Login返回Gate地址和gameId给客户端
       ↓
6. 客户端连接Gate，Gate转发到对应Game
```

**关键点**：
- Login在返回gameId给客户端时，就认为这个gameId是玩家的"最后登录服务器"
- 不需要Gate或Game通知Login登录成功或失败
- 即使客户端最终没有成功登录Game，Login仍然保持这个记录
- 下次玩家登录时，Login会再次检查Game状态，如果不可用会自动切换

## Game状态

### 状态定义

| 状态 | 值 | 说明 |
|------|-----|------|
| NOT_STARTED | 0 | 服务未启动 |
| STARTED_NOT_LOGIN | 1 | 服务已启动但不可登录（如维护中、爆满） |
| STARTED_CAN_LOGIN | 2 | 服务已启动且可以登录 |

### Redis Key设计

```
# Game状态
game:status:{gameId} = {status}:{online}:{lastUpdateTime}

# 玩家最后登录记录
player:lastgame:{playerId} = {gameId}:{timestamp}
```

**示例**：
- `game:status:1001` = `2:1500:1709900000000` (可登录，在线1500人)
- `player:lastgame:12345` = `1001:1709900000000`

## Game服务实现

### 状态管理

```java
public enum GameStatus {
    NOT_STARTED(0),           // 服务未启动
    STARTED_NOT_LOGIN(1),    // 已启动但不可登录
    STARTED_CAN_LOGIN(2);    // 可登录
    
    private final int value;
    GameStatus(int value) { this.value = value; }
    public int getValue() { return value; }
}
```

### 启动流程

```
应用启动
    ↓
设置状态 = STARTED_NOT_LOGIN (1)  -- "游戏维护中"
    ↓
初始化完成（如加载配置、连接数据库）
    ↓
设置状态 = STARTED_CAN_LOGIN (2)   -- "可以登录"
    ↓
启动心跳同步（每30秒）
```

### 关闭流程

```
收到关闭信号
    ↓
设置状态 = NOT_STARTED (0)
    ↓
等待正在处理的请求
    ↓
关闭应用
```

### 心跳同步

```java
// 通过 ScheduledExecutorService 以配置的 heartbeat-interval（默认 30s）周期调度
public void syncStatus() {
    String value = String.format("%d:%d:%d", 
        status.getValue(), 
        getOnlinePlayerCount(), 
        System.currentTimeMillis());
    
    redisTemplate.opsForValue().set(
        "game:status:" + gameId, 
        value, 
        60, TimeUnit.SECONDS);
}
```

## LoginService实现

### 路由决策

```java
public RouteResult route(Long playerId) {
    // 1. 获取玩家上次登录的Game
    Integer lastGameId = getLastLoginGame(playerId);
    
    // 2. 检查上次Game状态
    if (lastGameId != null) {
        GameStatus status = getGameStatus(lastGameId);
        
        if (status == GameStatus.STARTED_CAN_LOGIN) {
            // 可用，直接返回，并更新记录时间
            saveLastLoginGame(playerId, lastGameId);
            return RouteResult.ok(lastGameId);
        } else {
            // 不可用，尝试推荐服
            Integer recommendId = getRecommendGame();
            GameStatus recommendStatus = getGameStatus(recommendId);
            
            if (recommendStatus == GameStatus.STARTED_CAN_LOGIN) {
                // 推荐服可用，记录并返回重定向
                saveLastLoginGame(playerId, recommendId);
                return RouteResult.redirect(recommendId, "上次登录服不可用，已为您切换到推荐服");
            }
        }
    }
    
    // 3. 无记录或都不可用，使用推荐服
    Integer recommendId = getRecommendGame();
    GameStatus status = getGameStatus(recommendId);
    
    if (status == GameStatus.STARTED_CAN_LOGIN) {
        saveLastLoginGame(playerId, recommendId);
        return RouteResult.ok(recommendId);
    }
    
    // 4. 没有可用的Game
    return RouteResult.error("暂时没有可用的服务器，请稍后重试");
}
```

### 响应结构

```json
// 正常返回
{
  "code": 0,
  "data": {
    "gate": {"id": "gate-1", "host": "gate.example.com", "port": 8888},
    "gameId": 1001,
    "gameHost": "game1.example.com",
    "gamePort": 9090
  }
}

// 需要重定向
{
  "code": 0,
  "data": {
    "gate": {"id": "gate-1", "host": "gate.example.com", "port": 8888},
    "gameId": 1002,
    "gameHost": "game2.example.com",
    "gamePort": 9090,
    "redirect": true,
    "redirectMessage": "上次登录服务器不可用，已为您切换到推荐服"
  }
}

// 无可用服务器
{
  "code": 1,
  "message": "暂时没有可用的服务器，请稍后重试"
}
```

## 配置示例

### Game服务配置

```yaml
game:
  id: 1001
  status:
    heartbeat-interval: 30000  # 心跳间隔(毫秒)
  redis:
    host: localhost
    port: 6379
```

### LoginService配置

```yaml
login:
  redis:
    host: localhost
    port: 6379
  recommend-games:
    - gameId: 1001
      name: "推荐服1"
      priority: 1
    - gameId: 1002
      name: "推荐服2"
      priority: 2
```

## 错误码

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1 | 暂时没有可用的服务器 |
| 2 | 参数错误 |
| 3 | 认证失败 |
