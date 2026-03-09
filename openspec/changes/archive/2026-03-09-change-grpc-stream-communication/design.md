## Context

当前Gate与Game之间使用gRPC通信，消息体使用JSON字符串格式传输。这种方式存在以下问题：

1. **性能开销**：每次消息需要JSON序列化和反序列化
2. **带宽浪费**：JSON文本格式比二进制大
3. **连接频繁建立**：使用unary调用，每次请求都建立新连接

当前架构：
```
Gate → gRPC Unary (JSON) → Game
每次请求: 建立连接 → 序列化JSON → 传输 → 反序列化 → 处理 → 响应 → 关闭连接
```

## Goals / Non-Goals

**Goals:**
- 将gRPC通信改为双向Stream模式
- 消息体从JSON字符串改为二进制格式（Protobuf）
- 减少序列化/反序列化开销
- 建立持久连接，减少连接建立开销

**Non-Goals:**
- 不改变消息的业务语义
- 不修改Game的业务逻辑实现

## Decisions

### Decision 1: 使用Protobuf而非MessagePack

**选择**: Protobuf

**理由**:
- 与gRPC原生集成更好
- 已有proto定义，只需修改字段类型
- 性能优秀，跨语言支持好

### Decision 2: 双向Stream而非单向Stream

**选择**: Bidirectional Stream

**理由**:
- Gate可以主动推送消息给Game
- Game可以主动推送消息给Gate
- 实时性更好

### Decision 3: 消息体格式

**修改前**:
```protobuf
string body = 7;  // JSON字符串
```

**修改后**:
```protobuf
bytes body = 7;   // 二进制Protobuf数据
```

## Risks / Trade-offs

1. **[风险]** 二进制格式调试困难
   - **解决**: 保留JSON转换工具用于调试

2. **[风险]** Stream连接断开重连
   - **解决**: 实现自动重连机制

3. **[风险]** Game服务需要同步修改
   - **解决**: 同步修改game-service的proto定义
