## Why

当Game数量过多时，存在以下性能问题：
- 新增Gate时会一次性连接所有Game
- 新增Game时所有Gate都会连接该Game
- 导致瞬间压力过大，网络拥塞

## What Changes

实现Gate与Game的分组成员管理：
- Gate和Game分组管理
- 新增Gate时只连接部分Game
- 新增Game时只通知部分Gate
- 支持分组权重配置

## 核心功能

1. **分组管理**
   - Gate分组配置
   - Game分组配置
   - 分组权重策略

2. **渐进式连接**
   - 分批连接新加入的Game
   - 随机延迟避免惊群效应
   - 连接数上限控制

3. **负载均衡**
   - 根据分组分配流量
   - 支持权重配置
   - 动态调整

## 分组策略

```
# 分组配置示例
gate:
  group: A
  max-connections-per-group: 5

game:
  groups: [A, B]
```

## Impact

- 影响 `gate-service` 新增分组管理
- 影响 `game-service` 新增分组配置
- 影响 `login-service` 路由策略调整
