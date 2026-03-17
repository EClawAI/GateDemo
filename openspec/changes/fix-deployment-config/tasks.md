# fix-deployment-config 任务清单

## 1. docker-compose 环境变量更新

- [ ] 1.1 将 Gate 容器的 GAME_HOST、GAME_PORT 等变量替换为 GAME_GRPC_HOST、GAME_GRPC_PORT
- [ ] 1.2 核对 gate-service 代码中实际读取的配置键，确保 docker-compose 变量名与之一致
- [ ] 1.3 为 Game 容器配置正确的 gRPC 端口（如 50051）及 Redis 连接信息
- [ ] 1.4 为 login、center 容器配置 Redis 等依赖的环境变量

## 2. 补充 login-service 与 center-service 容器

- [ ] 2.1 在 docker-compose.yml 中新增 login-service 服务定义，包含 image、ports、environment、depends_on
- [ ] 2.2 在 docker-compose.yml 中新增 center-service 服务定义，包含 image、ports、environment、depends_on
- [ ] 2.3 配置 login/center 与 redis 的 depends_on 及网络
- [ ] 2.4 为 login/center 添加健康检查（若健康检查能力已实现）

## 3. 服务依赖与网络

- [ ] 3.1 配置 gate-service 的 depends_on 包含 redis、game-service（或按实际启动顺序）
- [ ] 3.2 确保各服务在同一 docker-compose 网络中，可通过 service 名互相访问
- [ ] 3.3 暴露必要端口供本地调试或外部访问

## 4. README 重写

- [ ] 4.1 撰写架构说明：gRPC、Redis、五服务职责与交互关系
- [ ] 4.2 更新快速启动：docker-compose up 命令与验证步骤
- [ ] 4.3 补充各服务端口列表与健康检查说明
- [ ] 4.4 移除"无 Redis"、"HTTP 直连"等过时内容
- [ ] 4.5 添加环境变量说明与可配置项
