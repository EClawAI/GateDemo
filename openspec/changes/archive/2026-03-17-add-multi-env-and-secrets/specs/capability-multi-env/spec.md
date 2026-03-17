# capability-multi-env (Delta)

## Purpose
支持多环境配置（dev/prod）与敏感信息外置，通过 APP_ENV 切换、环境变量注入实现环境差异化与安全配置管理。

## ADDED Requirements

### Requirement: 多环境配置文件

系统 SHALL 为每个服务提供 config-dev.yml、config-prod.yml 多环境配置文件；SHALL 根据 APP_ENV 环境变量或启动参数决定加载哪个文件；当 APP_ENV=dev 时 MUST 加载 config-dev.yml，当 APP_ENV=prod 时 MUST 加载 config-prod.yml；MUST 支持通过 -DAPP_ENV 或 env APP_ENV 指定。

#### Scenario: APP_ENV=dev 时加载开发配置
- **WHEN** 启动时 APP_ENV=dev（或 -DAPP_ENV=dev）
- **THEN** 系统加载 config-dev.yml
- **AND** 使用其中的 Redis 单机、日志 debug、TLS 关闭等配置

#### Scenario: APP_ENV=prod 时加载生产配置
- **WHEN** 启动时 APP_ENV=prod
- **THEN** 系统加载 config-prod.yml
- **AND** 使用其中的 Redis Sentinel、日志 info、TLS 开启等配置

### Requirement: 敏感信息通过环境变量外置

系统 SHALL 在配置文件中使用 ${ENV_VAR} 占位符表示敏感信息；SHALL 在配置加载时从环境变量或系统属性中解析并替换占位符；MUST 不在配置文件中明文存储密码、密钥等敏感信息；MUST 在必需的环境变量缺失时（prod 环境）明确报错并拒绝启动。

#### Scenario: 占位符被环境变量替换
- **WHEN** 配置中有 redis.password: ${REDIS_PASSWORD} 且环境变量 REDIS_PASSWORD 已设置
- **THEN** 加载后的配置中 redis.password 为实际环境变量值
- **AND** 敏感信息不出现在配置文件中

#### Scenario: 必需环境变量缺失时报错
- **WHEN** prod 环境下配置了 ${REDIS_PASSWORD} 但该环境变量未设置
- **THEN** 应用启动失败并输出明确错误信息
- **AND** 指明缺失的环境变量名称

### Requirement: dev/prod 日志级别差异化

系统 SHALL 在 config-dev.yml 中将日志级别设置为 debug；SHALL 在 config-prod.yml 中将日志级别设置为 info 或更严格级别；MUST 根据加载的配置文件生效，无需额外代码逻辑。

#### Scenario: 开发环境日志为 debug
- **WHEN** 使用 config-dev.yml 启动
- **THEN** 日志输出 debug 级别信息
- **AND** 便于开发调试

#### Scenario: 生产环境日志为 info
- **WHEN** 使用 config-prod.yml 启动
- **THEN** 日志输出 info 及以上级别
- **AND** 减少生产环境日志量

### Requirement: dev/prod Redis 与 TLS 差异化

系统 SHALL 在 dev 配置中支持 Redis 单机模式；SHALL 在 prod 配置中支持 Redis Sentinel 或集群；SHALL 在 dev 中默认关闭 TLS；SHALL 在 prod 中默认开启 TLS（若服务支持）；MUST 通过配置文件差异实现，无需硬编码环境判断。

#### Scenario: dev 使用 Redis 单机
- **WHEN** config-dev.yml 生效
- **THEN** Redis 连接配置为单节点（host + port）
- **AND** 无需 Sentinel 即可运行

#### Scenario: prod 使用 Redis Sentinel
- **WHEN** config-prod.yml 生效
- **THEN** Redis 连接配置为 Sentinel 模式
- **AND** 支持高可用故障切换

### Requirement: 部署脚本支持环境变量

系统 SHALL 在部署文档中说明各服务所需的 APP_ENV 及敏感信息相关环境变量；SHALL 提供 docker-compose、K8s 等示例，展示如何设置 APP_ENV 与 REDIS_PASSWORD 等；MUST 确保部署后配置正确加载。

#### Scenario: 部署时设置环境变量
- **WHEN** 通过 docker-compose 或 K8s 部署
- **THEN** 在 env 或 envFrom 中设置 APP_ENV、REDIS_PASSWORD 等
- **AND** 应用启动时正确加载对应配置
