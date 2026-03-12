## Context

CenterService作为中心配置服务，提供HTTP接口给客户端调用。

## Architecture

```
┌─────────────┐     HTTP      ┌─────────────┐
│   客户端    │ ◄──────────► │  CenterService │
└─────────────┘              └─────────────┘
```

## 核心接口

### 配置接口

**接口**: `GET /api/v1/config`

客户端启动时请求此接口获取配置信息。

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

### 模块结构

```
center-service/
├── controller/
│   └── ConfigController     # 配置接口
├── service/
│   └── ConfigService      # 配置服务
└── config/
```

### 核心类

1. **ConfigController**
   - `GET /api/v1/config` - 获取配置
