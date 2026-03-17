# API Versioning Strategy

本文档描述 GateDemo 项目的 API 与 Proto 版本策略，以及 breaking change 检测方法。

## 1. REST API 版本策略

### 1.1 方式：URL 前缀

项目采用 **URL 前缀** 版本化：

- `/api/v1/`：第一版 REST 接口
- `/api/v2/`：第二版（breaking change 时新建）

与 login-service、center-service 现有路由对应：

- `login-service`：`/api/v1/login`、`/api/v1/token` 等
- `center-service`：`/api/v1/version`、`/api/v1/announcements` 等

### 1.2 向后兼容原则

- 新增可选字段、新增端点：**不**创建新版本
- 删除字段、修改语义、修改路径：需创建新版本（如 v2）
- 废弃端点：添加 `@Deprecated`，在响应头或文档中注明 `X-API-Deprecated: true` 及迁移目标

### 1.3 废弃流程

1. **deprecated 标记**：在代码与 OpenAPI 中标记 `deprecated: true`
2. **过渡期**：至少保留一个大版本周期（如 6 个月），文档说明替代方案
3. **removed 时机**：过渡期结束后，在新大版本中移除

---

## 2. Proto 版本策略

### 2.1 方式：package 命名

- `com.clawai.gatedemo.v1`：第一版 gRPC 服务
- `com.clawai.gatedemo.v2`：第二版（breaking change 时新建）

### 2.2 规则

- 新增 optional 字段、新增 RPC：不破坏兼容性，沿用当前版本
- 删除字段、重命名字段、修改字段类型：需新建 package 版本
- 废弃字段：使用 `reserved` 保留编号，避免复用

---

## 3. buf breaking change 检测

### 3.1 工具

使用 [buf](https://buf.build/docs/breaking/overview) 在 CI 中检测 Proto breaking change。

### 3.2 典型规则

- `BUF_CHECK_BREAKING_CONFIG` 或默认规则：禁止删除/重命名字段、修改类型等
- 配置于 `buf.yaml` 的 `breaking` 段

### 3.3 用法

```bash
# 检测当前目录相对上次 committed 的 breaking 变更
buf breaking --against '.git#branch=main'

# 或 against 某路径
buf breaking --against 'proto/v1'
```

### 3.4 修复与例外

- **修复**：撤销 breaking 变更，或新建 v2 package 后迁移
- **例外**：若确需 breaking，通过 `buf.gen.yaml` 或 `buf.yaml` 的 `ignore` 配置豁免（需团队评审）

---

## 4. Proto 文档生成（protoc-gen-doc）

### 4.1 插件

使用 `protoc-gen-doc` 或 `buf doc` 从 `.proto` 生成 HTML/Markdown。

### 4.2 使用方式

参见 `common/pom.xml` 中 protobuf-maven-plugin 的 `protoc-gen-doc` 配置，或执行：

```bash
# 若使用 buf
buf generate --template buf.gen.doc.yaml

# 若使用 protoc 直接调用
protoc --doc_out=./docs/proto --doc_opt=html,index.html proto/*.proto
```

### 4.3 输出位置

- 生成文件输出至 `docs/proto/`
- 可在 CI 中自动生成，或由开发者本地生成后提交
