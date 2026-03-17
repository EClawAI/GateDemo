# P013: 多环境与敏感信息管理

## 1. 配置加载逻辑

- [x] 1.1 实现 APP_ENV 读取（启动参数 -DAPP_ENV=dev 或环境变量 APP_ENV）
- [x] 1.2 根据 APP_ENV 加载 config-dev.yml 或 config-prod.yml，支持 config-{env}.yml 命名
- [x] 1.3 定义加载顺序：公共配置 → config-{env}.yml，后者覆盖前者
- [x] 1.4 实现占位符解析器：${ENV_VAR} 从 System.getenv() 替换；支持 ${VAR:default} 默认值语法

## 2. 多环境配置文件拆分

- [x] 2.1 为 gate、game、login、center 各服务创建 config-dev.yml、config-prod.yml
- [x] 2.2 将现有配置项抽取到对应环境文件；公共项放 config/common.yml 或主配置
- [x] 2.3 dev/prod 日志级别：dev 使用 debug，prod 使用 info
- [x] 2.4 dev/prod Redis：dev 单机配置，prod 使用 Sentinel 或集群配置
- [x] 2.5 dev/prod TLS：dev 关闭 gate.tls.enabled，prod 开启

## 3. 敏感信息外置

- [x] 3.1 将 Redis 密码、DB 密码等改为 ${REDIS_PASSWORD}、${DB_PASSWORD} 占位符
- [x] 3.2 启动时检查必需环境变量，缺失时明确报错
- [x] 3.3 文档列出各服务所需 ENV 清单及含义

## 4. 部署与文档

- [x] 4.1 更新部署脚本、docker-compose、K8s 配置，设置 APP_ENV 及密码环境变量
- [x] 4.2 在 README 中说明环境切换方式与敏感变量注入方式
