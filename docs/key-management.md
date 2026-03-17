# Key Management Strategy

本文档描述 GateDemo 项目中 JWT 与通用密钥的轮换、存储及安全管理策略。

## 1. JWT 密钥轮换策略

### 1.1 当前实现

- login-service 使用 HMAC-SHA256 签发 JWT，密钥通过 `JWT_SECRET` 环境变量配置
- gate-service、game-service 使用相同密钥验证 Token

### 1.2 轮换流程（建议）

1. **生成新密钥**：创建新的 HMAC 密钥，并分配版本号（如 v2）
2. **并行期**：新密钥用于签发新 Token，旧密钥仍可用于验证历史 Token
3. **过渡时长**：建议覆盖最长 Token 有效期（如 2 小时）的 2–3 倍，确保旧 Token 基本失效
4. **切换**：所有服务更新为仅使用新密钥；移除旧密钥

### 1.3 密钥存储

- **生产**：优先使用 HashiCorp Vault 或云 KMS（如 AWS KMS、阿里云 KMS）
- 密钥路径按服务与环境区分，支持多版本读取
- 敏感配置通过环境变量或 Secret 注入，不写入代码或配置文件

### 1.4 Fallback

- Vault/KMS 不可用时，可配置 fallback 到环境变量（仅限非生产或应急）
- 严格模式：生产环境可禁用 fallback，强制使用外部密钥服务

---

## 2. 多版本密钥支持

### 2.1 KeyProvider 设计

- 加密/签发：始终使用最新版本密钥
- 解密/验证：支持多版本密钥，按 Token 头或密文中的版本标识选择对应密钥
- 版本标识：可在 JWT header（如 `kid`）或自定义 claim 中携带

### 2.2 轮换操作步骤

1. 在 Vault/KMS 中创建新版本密钥
2. 更新 KeyProvider 配置，使其能拉取新版本
3. 部署服务，新签发的 Token 使用新密钥
4. 等待过渡期结束（旧 Token 过期）
5. 从 KeyProvider 中移除旧版本密钥的访问权限

---

## 3. 与现有配置的衔接

- 当前 `JWT_SECRET` 可保留为开发/测试默认值
- 生产环境必须覆盖为从 Vault/KMS 或安全存储中获取的值
