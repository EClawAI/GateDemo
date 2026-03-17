## Why

当前部署配置与实际架构不一致：
- Gate 容器使用 GAME_HOST/GAME_PORT（HTTP）环境变量，但代码已切换为 gRPC 架构
- README 描述"无 Redis 版本，HTTP 直连"与实际实现不符
- docker-compose.yml 中缺少 login、center 等容器定义

## What Changes

更新部署配置以对齐当前 gRPC 架构：
- 更新 docker-compose.yml 环境变量， Gate 使用 GAME_GRPC_HOST/GAME_GRPC_PORT 等
- 补充 login、center 容器定义
- 重写 README.md，准确描述架构与启动方式

## 核心功能

1. **docker-compose 环境变量对齐**
   - Gate 使用 gRPC 相关环境变量
   - 各服务端口、主机名与代码一致

2. **补充容器定义**
   - 增加 login-service 容器
   - 增加 center-service 容器
   - 完善服务间依赖与网络

3. **README 重写**
   - 准确描述 gRPC 架构
   - 更新启动步骤与依赖说明
   - 移除过时的 HTTP 直连描述

## Impact

- 影响 `docker-compose.yml` 环境变量与容器定义
- 影响 `README.md` 全文
