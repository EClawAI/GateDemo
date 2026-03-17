# add-key-mgmt-backpressure-backup 任务清单

## 1. 密钥外部化与 Vault/KMS 集成

- [ ] 1.1 部署 HashiCorp Vault 或配置云 KMS（AWS KMS/阿里云 KMS 等）
- [ ] 1.2 在 Vault/KMS 中创建密钥存储路径，迁移现有 AES 密钥（支持版本号）
- [ ] 1.3 在 gate-service、game-service 中引入 Vault Java 客户端或 KMS SDK
- [ ] 1.4 实现 KeyProvider 或等效：启动时从 Vault/KMS 获取密钥，支持版本号
- [ ] 1.5 支持 Vault 地址、路径、认证信息通过环境变量配置
- [ ] 1.6 实现 Vault/KMS 不可用时的 fallback（环境变量），可配置严格模式
- [ ] 1.7 移除代码中的硬编码密钥

## 2. 密钥版本轮换

- [ ] 2.1 设计轮换流程：新密钥加密、旧密钥并行解密期
- [ ] 2.2 在 KeyProvider 中支持多版本密钥获取与选择（加密用最新、解密支持多版本）
- [ ] 2.3 文档化轮换操作步骤与并行期建议时长
- [ ] 2.4 测试轮换流程，确保历史数据可解密

## 3. gRPC Stream 背压

- [ ] 3.1 在 gate-service 的 gRPC Stream 调用处集成 onReady 回调
- [ ] 3.2 实现发送速率控制：仅在 onReady 时发送，或配合批量/间隔配置
- [ ] 3.3 在 game-service 的 gRPC Stream 服务端（若为流式）同样遵循背压语义
- [ ] 3.4 添加配置项：批量大小、发送间隔等，便于调优
- [ ] 3.5 文档说明背压行为与配置

## 4. Redis Stream 背压

- [ ] 4.1 实现 Redis Stream pending 监控（XPENDING 或等效 API）
- [ ] 4.2 定义 pending 阈值配置项
- [ ] 4.3 在 Gate 向 Redis Stream 生产消息前检查 pending，超阈值时暂停或排队
- [ ] 4.4 实现定期或事件驱动的 pending 检查，pending 下降后恢复生产
- [ ] 4.5 超阈值时记录告警或日志
- [ ] 4.6 文档说明配置与调优建议

## 5. Redis 持久化与备份

- [ ] 5.1 配置 Redis RDB：save 规则或 BGSAVE 策略
- [ ] 5.2 开启 Redis AOF，配置 appendfsync（如 everysec）
- [ ] 5.3 文档说明 Redis 持久化配置、数据目录、备份位置
- [ ] 5.4 编写 Redis 恢复 SOP（从 RDB 或 AOF 恢复）
- [ ] 5.5 在测试环境执行恢复演练，验证 SOP 有效性

## 6. 数据库备份（若引入）

- [ ] 6.1 若项目引入 MySQL/PostgreSQL 等，配置定期备份（mysqldump、pg_dump 或物理备份）
- [ ] 6.2 配置备份脚本或工具，将备份文件上传至异地存储
- [ ] 6.3 编写数据库恢复 SOP 文档
- [ ] 6.4 根据 RPO/RTO 确定备份频率与保留策略
- [ ] 6.5 若尚未引入数据库，在文档中标注为「待引入时执行」
