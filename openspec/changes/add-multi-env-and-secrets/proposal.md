## Why

当前配置存在以下问题：
- 仅一份配置文件通过环境变量覆盖，无法区分日志级别、Redis 集群、TLS 开关等
- Redis 密码在配置文件中明文（如 redistest），敏感信息暴露

## What Changes

实现多环境与敏感信息安全管理：
- 为每个服务创建 `config-dev.yml`、`config-prod.yml` 多环境配置文件
- 敏感信息通过 `${ENV_VAR}` 占位符外置，避免硬编码
- dev/prod 差异化配置（日志、Redis、TLS 等）

## 核心功能

1. **多环境配置文件**
   - config-dev.yml：开发环境
   - config-prod.yml：生产环境
   - 通过启动参数或环境变量 `APP_ENV=dev|prod` 切换

2. **敏感信息外置**
   - 密码、密钥等通过 `${ENV_VAR}` 占位符
   - 运行时由环境变量注入

3. **dev/prod 差异化配置**
   - 日志级别（dev debug / prod info）
   - Redis 单机 vs 集群
   - TLS 开关（dev 关闭 / prod 开启）

## Impact

- 影响全部服务的配置文件及新增 profile 文件
- 影响部署脚本需设置相应环境变量
