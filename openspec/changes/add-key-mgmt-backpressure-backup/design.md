# 密钥管理、背压与备份 (design.md)

## Context

- **当前状态**：AES 密钥硬编码，无轮换机制，泄露后无法快速失效。消息处理无流控，下游慢时上游可能 OOM。Redis 单节点无备份策略，数据丢失风险高。
- **问题**：密钥管理薄弱，存在泄露与长期复用风险；无背压导致内存与连接堆积；数据持久化与恢复能力不足。
- **约束**：不使用 Spring Boot/Spring Cloud；gate-service、game-service 为 plain Java + Netty/gRPC；密钥集成与背压逻辑需与现有架构兼容。

## Goals / Non-Goals

**Goals:**
- 密钥存储外部化（Vault/KMS），支持密钥版本号，新旧密钥并行解密期轮换
- gRPC Stream 使用 onReady 回调控制发送速率；Redis Stream 监控 pending 消息数，超阈值暂停生产
- 开启 Redis RDB 定期快照 + AOF 持久化
- 若引入持久化数据库，配置定期备份与异地存储

**Non-Goals:**
- 不实现完整的密钥轮换自动化编排（可由外部流程触发）
- 不引入 Spring Security 或 Spring Vault
- 不改变现有业务消息格式或协议

## Decisions

1. **密钥存储与获取**
   - 使用 HashiCorp Vault 或云 KMS（如 AWS KMS、阿里云 KMS）存储 AES 密钥；应用启动时通过 Vault API 或 KMS SDK 获取密钥；支持通过密钥版本号或别名获取；密钥 ID/路径通过环境变量配置。
   - **理由**：密钥与代码分离，支持集中管理与审计。

2. **密钥版本与轮换**
   - 支持多版本密钥；新密钥用于加密，旧密钥在并行解密期内仍可用于解密；轮换时写入新版本，应用通过配置或 API 获取最新版本；并行期结束后废弃旧密钥。
   - **理由**：平滑轮换，避免加密数据无法解密。

3. **gRPC Stream 背压**
   - 在 gRPC 双向 Stream 中，使用 StreamObserver.onReady 回调判断对端接收能力；当 onReady 触发时才发送下一批消息；避免无限制发送导致对端背压或 OOM。
   - **理由**：符合 gRPC 流控语义，保护下游。

4. **Redis Stream 背压**
   - 监控 Redis Stream 的 pending 消息数（XPENDING 或等效）；当 pending 超过设定阈值时，上游（如 Gate）暂停向 Stream 生产消息，或降速、排队；待 pending 下降后再恢复。
   - **理由**：防止消费者慢导致消息堆积、内存膨胀。

5. **Redis 持久化**
   - 开启 RDB 定期快照（save 或 BGSAVE）；开启 AOF 持久化；根据数据重要性选择 appendfsync 策略（everysec 为平衡）；定期演练恢复流程。
   - **理由**：降低 Redis 故障时的数据丢失风险。

6. **数据库备份（若引入）**
   - 若项目引入 MySQL/PostgreSQL 等持久化数据库，配置定期备份（逻辑或物理备份）；备份文件异地存储；编写恢复 SOP 文档。
   - **理由**：与 Redis 备份形成完整数据保护策略。

## Risks / Trade-offs

- **[风险]** Vault/KMS 不可用导致启动失败 → 支持 fallback 到环境变量或本地加密配置（降级）；或启动时重试，超过次数再失败。
- **[权衡]** 背压过严导致吞吐下降 → 阈值可配置，根据业务调优；文档说明如何调整。
- **[风险]** RDB 快照期间性能抖动 → 合理设置 save 间隔，避免过于频繁；AOF 的 appendfsync everysec 可平衡性能与安全。
- **[风险]** 密钥轮换误操作导致数据无法解密 → 严格测试轮换流程；保留旧密钥至并行期结束；做好备份。

## Migration Plan

- **实现顺序**：先部署 Vault/KMS 并迁移密钥 → 应用集成密钥获取与版本支持 → 实现 gRPC onReady 背压 → 实现 Redis Stream pending 监控与背压 → 开启 Redis RDB+AOF → 若有 DB 则配置备份 → 恢复演练与 SOP。
- **部署**：Vault 需先就绪；应用分步切换，先双模式（环境变量 + Vault）验证，再切纯 Vault；Redis 持久化需评估磁盘与性能影响。
- **回滚**：保留环境变量密钥 fallback；背压可配置关闭；Redis 持久化可临时关闭（不推荐长期关闭）。

## Open Questions

- 项目当前使用的加密场景具体有哪些？仅消息体 AES 加密，还是有其他（如 Token 签名）？需明确密钥用途以设计轮换策略。
- Redis 数据是否允许部分丢失？若为缓存可接受，RDB 间隔可放宽；若为关键状态，需 AOF 且 appendfsync 更严格。
- 是否已规划引入持久化数据库？若否，数据库备份部分可标注为「待引入时执行」。
