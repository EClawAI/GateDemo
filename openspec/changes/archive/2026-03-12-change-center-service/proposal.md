## Why

客户端启动时需要获取一些基础配置信息：
1. **客户端版本**：检查是否需要更新
2. **SDK地址**：客户端需要知道SDK服务器地址
3. **Login地址**：客户端需要知道登录服务地址
4. **公告信息**：游戏公告、维护通知等

这些信息相对稳定，适合统一由CenterService提供。

## What Changes

1. **新增CenterService**：作为中心配置服务
2. **版本检查**：返回客户端版本信息
3. **配置信息**：返回SDK地址、Login地址等
4. **公告信息**：返回游戏公告

## Capabilities

### New Capabilities
- `capability-center-service`: 中心服务能力

## Impact

- 影响新增 `center-service` 模块

## 客户端使用流程

```
客户端启动
     ↓
请求CenterService获取配置
     ↓
获取版本 → 版本不匹配提示更新
获取配置 → 继续请求LoginService
```

## 接口设计

### 配置接口

**接口**: `GET /api/v1/config`

**响应**:
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
    "sdk": {
      "host": "sdk.example.com",
      "port": 8443
    },
    "login": {
      "host": "login.example.com",
      "port": 8081
    },
    "announcement": {
      "title": "欢迎来到游戏",
      "content": "游戏公告内容",
      "type": "normal"
    }
  }
}
```

## 实现

- **CenterService**: `center-service` 模块
- **ConfigController**: 配置接口
