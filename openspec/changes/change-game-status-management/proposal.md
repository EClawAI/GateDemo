## Why

当前LoginService无法知道Game服务器的实际状态：
- 无法知道Game是否已启动
- 无法知道Game是否允许玩家登录
- 玩家可能被路由到不可用的Game服务器

引入Game状态管理机制：
- Game状态存储在Redis中，供LoginService查询
- Game定期同步状态到Redis
- LoginService根据状态过滤不可用的Game

**重要变更**：
- 删除之前实现的"玩家登录Game服后通知LoginService更新登录记录"逻辑
- 改为：Login在决定路由到某个Game时，直接记录该GameId为玩家的"最后登录服务器"
- 即使玩家最终登录失败，Login仍然记录该GameId
- 不需要Gate或Game通知Login登录结果

## What Changes

1. **Game状态存储**：Game状态存储在Redis中
2. **Game心跳上报**：Game定期上报状态到Redis
3. **Login状态过滤**：Login根据状态过滤不可用的Game
4. **Login记录逻辑**：Login路由时直接记录最后登录的GameId
5. **删除通知逻辑**：删除所有Gate/Game通知Login的逻辑
6. **客户端引导**：Login返回是否跳转到推荐服
7. **删除登录记录逻辑**：删除Game通知LoginService的逻辑
8. **文档补全**：检查并补全相关系统文档

## Game状态定义

| 状态值 | 说明 |
|--------|------|
| 0 | 服务未启动 |
| 1 | 服务已启动但不可登录 |
| 2 | 服务已启动且可以登录 |

## 登录流程（简化后）

```
1. 玩家请求登录
2. Login查询Redis获取Game状态
3. Login决定路由到哪个Game
4. Login直接记录 playerId -> gameId（此时就记录，不需要等Game通知）
5. Login返回Gate地址和gameId给客户端
6. 客户端连接Gate，Gate转发到对应Game
```

**关键点**：
- Login在返回gameId给客户端时，就记录这个gameId为"最后登录服务器"
- 不需要Gate或Game通知Login登录成功或失败
- 即使客户端最终没有成功登录Game，Login仍然认为该Game是"最后登录的服务器"

## Capabilities

### New Capabilities
- `capability-game-status`: Game状态管理能力

### Modified Capabilities
- `capability-login-service`: 删除登录记录接口
- `capability-login-service`: 简化为只记录路由结果

## Impact

- 影响 `game-service` 新增状态同步，删除登录通知
- 影响 `login-service` 简化登录记录逻辑，新增状态过滤，删除登录记录接口
- 无需Gate通知Login
- 需要补全Game和Login服务的详细设计文档

## 文档要求

在提案完成后，需要检查并补全以下文档：

1. **Game服务详细设计文档**
   - 状态管理机制
   - 心跳同步实现
   - 启动/关闭流程

2. **LoginService详细设计文档**
   - 路由决策逻辑
   - 状态过滤实现
   - 客户端重定向逻辑

如果 `docs/` 目录下不存在相关文档，需要创建补全。
