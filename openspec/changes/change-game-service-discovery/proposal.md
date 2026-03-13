## Why

当前Gate连接哪些Game是在gate的配置文件中手动指定的：
```yaml
gate:
  games:
    - id: 1001
      host: localhost
      port: 9090
```

这种方式存在以下问题：
- 新增Game时需要手动修改所有Gate的配置
- 无法动态感知Game的上下线状态

## What Changes

实现Game服务注册与发现机制：
- Game启动时自动注册到Redis
- Game关闭时自动注销
- Gate从Redis发现可用Game列表
- 支持Game状态变更通知

## Redis Key设计

```
# Game服务注册
game:registry:{gameId} = {host}:{port}:{status}:{timestamp}

# Gate订阅通道
game:events -> 发布Game变更事件
```

## 核心功能

1. **Game服务注册**
   - 启动时注册
   - 关闭时注销
   - 定时心跳保活

2. **Gate服务发现**
   - 启动时获取全量Game列表
   - 订阅Game变更事件
   - 动态更新Game连接

## Impact

- 影响 `game-service` 新增服务注册逻辑
- 影响 `gate-service` 新增服务发现逻辑
