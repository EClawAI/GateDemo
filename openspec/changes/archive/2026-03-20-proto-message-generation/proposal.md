## Why

当前 proto 消息的 msg_id 需要开发者手动在 `.proto` 文件的 custom option 中指定，随着消息数量增长到几百条，手动分配 ID 容易冲突且缺少编译期校验。同时缺少集中的消息清单，其他语言客户端（Unity C#、TypeScript）无法共享路由表。需要一套自动化工具从 proto 消息名哈希生成 ID，并产出跨语言可用的 JSON 注册表。

## What Changes

- **新增** `tools/gen_proto.sh` 脚本：解析所有 `.proto` 文件，提取 message 名称和 `route_to` option，用 CRC32 哈希消息名生成 32 位 msg_id，校验上行消息必须标记 `route_to` 且 ID 无碰撞，输出 `message_registry.json` 并调用 `protoc` 编译 Java 类
- **新增** `message_registry.json`（由脚本生成）：包含所有消息的 id/name/service/direction/file 信息
- **修改** `gate_options.proto`：移除 `msg_id` extension field，只保留 `route_to`
- **修改** 所有 `.proto` 文件：移除 `option (gateoptions.msg_id)` 声明
- **修改** `MessageRouteRegistry`：新增 `loadFromJson()` 方法，启动时从 JSON 加载路由表
- **删除** `MessageRouteScanner`：不再需要运行时扫描 proto descriptor
- **删除** `MessageRouteConfig`（gate-service）：不再需要手动调用 scanner

## Capabilities

### New Capabilities
- `proto-codegen`: 独立脚本从 proto 文件自动生成消息 ID（CRC32 哈希）、校验路由标记、产出 JSON 注册表和 Java protobuf 类
- `json-route-loading`: Java 启动时从 `message_registry.json` 加载消息路由表，替代运行时 proto descriptor 扫描

### Modified Capabilities

## Impact

- `proto/gate_options.proto` — 移除 `msg_id` extension
- `proto/gate_protocol.proto`, `proto/game_messages.proto` — 移除所有 `msg_id` option 行
- `common` 模块 — `MessageRouteRegistry` 增加 JSON 加载，删除 `MessageRouteScanner`；`common/config/` 存放生成的 JSON
- `gate-service` 模块 — 删除 `MessageRouteConfig`，启动改为从 JSON 加载
- 构建流程 — 开发者修改 proto 后需手动运行 `tools/gen_proto.sh`
- 跨语言 — `message_registry.json` 可被其他语言客户端直接消费
