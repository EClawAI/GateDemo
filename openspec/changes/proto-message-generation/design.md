## Context

当前项目使用 protobuf custom option (`msg_id`, `route_to`) 在 `.proto` 文件中声明消息路由元数据，Java 运行时通过 `MessageRouteScanner` 扫描 proto FileDescriptor 填充 `MessageRouteRegistry`。随着消息规模预计增长到几百条，手动分配 msg_id 容易冲突且无编译期校验。前一次迭代已将 messageId 从 16 位扩展到 32 位（协议头从 14 字节扩到 16 字节），为哈希自动生成 ID 做好了空间准备。

现有代码路径：`proto/*.proto` → `protobuf-maven-plugin` → Java 生成类 → `MessageRouteScanner.scan()` → `MessageRouteRegistry`（内存）。

## Goals / Non-Goals

**Goals:**
- 消息 ID 由消息名 CRC32 哈希自动计算，开发者不再手写 `msg_id`
- 独立 shell 脚本完成：解析 proto → 计算哈希 → 校验 → 生成 JSON → 调用 protoc
- 校验上行消息（有 `route_to`）必须标记目标服务
- 校验所有消息 ID 无碰撞，碰撞时明确提示冲突的消息名
- 生成 `message_registry.json` 供 Java 启动加载和其他语言客户端消费
- Java 启动改为从 JSON 加载路由表，移除运行时 descriptor 扫描

**Non-Goals:**
- 不自动集成到 Maven build（手动调用脚本）
- 不生成 Java 常量类（JSON 加载即可）
- 不处理 proto 文件的创建/模板生成（开发者仍手写 proto）
- 不做 proto 向后兼容性检查（如字段号变更检测）

## Decisions

### D1: 哈希算法选择 CRC32

**决定**：使用 CRC32 对消息名（UTF-8）做哈希，产出 32 位无符号整数作为 msg_id。

**理由**：CRC32 分布均匀，500 条消息碰撞概率约 0.003%；各语言标准库均有实现（Java `java.util.zip.CRC32`、Python `binascii.crc32`、C# `System.IO.Hashing.Crc32`）；确定性且无外部依赖。

**替代方案**：
- FNV-1a：分布同样好，但非标准库常有
- MurmurHash3：分布最优但实现稍重，跨语言一致性需额外验证

### D2: 脚本使用 Python 内核 + Shell 包装

**决定**：核心逻辑用 Python 实现（`tools/gen_proto.py`），外层 `tools/gen_proto.sh` 做环境检查和调用。

**理由**：Python 处理文本解析、CRC32 计算、JSON 生成比纯 shell 更可靠；shell 包装负责检查 protoc 是否安装、proto 目录是否存在等环境事项。

**替代方案**：
- 纯 shell + awk/sed：proto 解析容易出错，难以维护
- Node.js：项目以 Java 为主，Python 更普遍

### D3: JSON 放入 common/config 目录

**决定**：`message_registry.json` 放在 `common/config/` 目录下，Maven 通过 `<resource>` 配置将 `config/` 加入 classpath，Java 启动时从 classpath 加载。

**理由**：与 `src/main/resources/` 中的手写配置分离，明确区分"生成产物"和"手写资源"；common 模块是所有服务的公共依赖，打包进 JAR 后各服务自动可见。

**替代方案**：
- 放在 `common/src/main/resources/`：生成文件与手写资源混在一起，不易区分
- 放在项目根目录，各模块 copy：冗余且易不同步
- 放在 gate-service resources：其他服务无法使用

### D4: proto 中移除 msg_id option，保留 route_to

**决定**：从 `gate_options.proto` 删除 `msg_id` extension；各 `.proto` 文件移除 `option (gateoptions.msg_id)` 行；保留 `route_to` option 供脚本读取和校验。

**理由**：msg_id 由脚本自动计算，proto 中声明无意义且可能与哈希值矛盾。route_to 仍需留在 proto 中作为消息归属服务的声明性元数据（脚本从中提取方向和目标服务）。

### D5: 脚本解析 proto 的方式

**决定**：用正则表达式解析 proto 文件，提取 `message` 块和 `route_to` option，不依赖 protoc 的 descriptor set。

**理由**：proto 文件结构简单规整（`message Foo {` 和 `option (gateoptions.route_to) = "xxx";`），正则足够可靠；避免引入 protoc descriptor set 生成和解析的额外复杂度。

## Risks / Trade-offs

- **[风险] 消息重命名导致 ID 变更** → 这是有意设计：ID 从名字派生，改名即改 ID。协议版本兼容需在更高层面处理（客户端/服务端同步更新）。
- **[风险] 开发者忘记运行脚本** → 启动时 `MessageRouteRegistry.loadFromJson()` 若读不到 JSON 或 JSON 为空则快速失败并提示运行脚本。
- **[风险] CRC32 碰撞** → 概率极低（0.003%/500条），脚本会明确报错并给出改名建议。
- **[权衡] 正则解析 vs protoc descriptor** → 正则更轻量但可能对非常规 proto 格式误解析；当前项目 proto 格式统一可控。
