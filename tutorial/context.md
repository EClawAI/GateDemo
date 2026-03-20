# Tutorial Generator Context

## 项目信息
- 路径: /Users/lizhiwei/Work/Lzw/GateDemo
- 技术栈: Java 17 / Spring Boot 3.2 / Netty 4.1 / gRPC 1.59 / Protobuf 3.24 / Redis / Maven
- 规模: ~114 个 Java 源文件，6 个 Maven 模块
- Git: 有，共 78 个 commit（2026-03-04 ~ 2026-03-19）
- OpenSpec: 有，21 个归档变更 + 3 个活跃变更
- 设计文档: 6 个有内容的文档 + 3 个根目录 README

## 目标用户
- 级别: Level 4 — 游戏/网关领域专家
- 策略: 跳过基础概念，聚焦架构对比和实现细节。关注设计取舍、性能优化、生产部署方案。
- 生成模式: 一次性批量生成

## 演进节点与决策

### Phase 1: Java/Spring Boot 初始架构 (Mar 4-5)
- 选择 Java 17 + Spring Boot 3.2
- WebSocket + Redis Streams
- Maven 多模块

### Phase 3: 去 Redis 简化 (Mar 5)
- HTTP 直连替代 Redis Streams
- ConcurrentHashMap 替代 Redis
- Gate 独立为纯 Netty 服务

### Phase 4: Netty WebSocket (Mar 5)
- Spring WebSocket → Netty WebSocket
- 更精细的 Pipeline 控制

### Phase 5: gRPC 集成 (Mar 6)
- HTTP → gRPC (3-5x 性能提升)
- 连接池管理
- Game 转为纯 gRPC 服务

### Phase 6: 完整网关功能 (Mar 6)
- 自定义二进制协议
- Token 认证、AES 加密、限流、熔断
- TCP 服务器、心跳
- Redis 离线消息

### Phase 7: gRPC Stream (Mar 9)
- 单次请求 → 双向流
- body 从 string 改 bytes

### Phase 8: 微服务拆分 (Mar 9-13)
- CenterService (配置中心)
- LoginService (认证/路由)
- Game 状态管理 + 服务发现

### Phase 9: 生产级强化 (Mar 17-19)
- P002-P021: 部署、CI/CD、JWT、TLS、限流、公共模块
- 单元测试、集成测试
- Prometheus 监控、OpenTelemetry
- 安全加固、弹性设计
- K8s 部署、Redis HA
- TCP + 集群管理

## 确认的大纲

第0章: 课程概述
第1章: 技术选型与架构奠基
第2章: 通信架构演进 — 去 Redis 与 Gate 独立
第3章: Netty 网关核心 — WebSocket 与 TCP 双协议
第4章: 自定义二进制协议设计
第5章: gRPC 通信 — 从单次调用到双向流
第6章: 微服务拆分 — Center、Login 与服务发现
第7章: 安全体系 — 认证、加密与防护
第8章: 弹性设计 — 限流、熔断与优雅关停
第9章: 可观测性 — 指标、日志与追踪
第10章: 生产部署 — 多环境、Docker 与 Kubernetes
第11章: 公共模块与工程实践
第12章: 演进复盘与未来规划

注意: 去掉了 Python 原型相关内容，项目从一开始就使用 Java。
生成模式: 一次性批量生成
