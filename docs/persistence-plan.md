# 持久化存储规划文档

## 1. 数据库选型：MySQL 与 PostgreSQL

### 1.1 对比

| 维度 | MySQL | PostgreSQL | 建议 |
|------|-------|------------|------|
| 游戏行业普及度 | 高 | 中 | MySQL 更常见 |
| JSON 支持 | JSON 类型，性能一般 | jsonb 原生，索引友好 | PostgreSQL 略优 |
| 事务与 ACID | 支持 | 支持，更严格 | 平手 |
| 扩展性 | 主从、分库分表成熟 | 分区、逻辑复制 | 平手 |
| 运维熟悉度 | 高 | 中 | MySQL 更易招人 |
| 复杂查询 | 够用 | 更强（CTE、窗口函数） | PostgreSQL 略优 |

### 1.2 选型建议

- **推荐 MySQL**：项目规模适中、团队熟悉度高、生态成熟。
- **可选 PostgreSQL**：若业务需要强 JSON 查询、复杂分析或更强一致性，可考虑 PostgreSQL。

---

## 2. 数据模型

### 2.1 用户账号 (user_account)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| username | VARCHAR(64) UNIQUE | 登录名 |
| password_hash | VARCHAR(128) | 密码哈希 |
| status | TINYINT | 0=正常 1=封禁 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 2.2 玩家/角色 (player)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 玩家ID，与业务 playerId 对应 |
| user_id | BIGINT FK | 关联 user_account |
| nickname | VARCHAR(64) | 昵称 |
| server_id | INT | 当前/常驻服务器ID |
| level | INT | 等级 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 2.3 登录记录 (login_record)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| player_id | BIGINT | 玩家ID |
| game_id | INT | 游戏服ID |
| device_id | VARCHAR(128) | 设备标识 |
| ip | VARCHAR(64) | 登录IP |
| login_at | DATETIME | 登录时间 |

### 2.4 游戏状态 (game_state)（示例）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| player_id | BIGINT | 玩家ID |
| game_id | INT | 游戏服ID |
| state_json | JSON/TEXT | 游戏内状态快照 |
| version | INT | 乐观锁版本号 |
| updated_at | DATETIME | 更新时间 |

### 2.5 订单（可选扩展）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| player_id | BIGINT | 玩家ID |
| order_no | VARCHAR(64) UNIQUE | 外部订单号 |
| amount | DECIMAL(10,2) | 金额 |
| status | TINYINT | 0=待支付 1=已支付 2=已退款 |
| created_at | DATETIME | 创建时间 |

---

## 3. Redis 与 DB 职责划分

### 3.1 Redis 职责

| 场景 | 说明 |
|------|------|
| 缓存 | 热点 player/账号 数据缓存 |
| 会话 | JWT 黑名单、玩家在线状态、连接会话 |
| 服务发现 | Gate 心跳、Game 注册 |
| 临时状态 | 心跳、限流计数器、离线消息队列 |
| 消息队列 | Redis Stream 离线消息 |

### 3.2 DB 职责

| 场景 | 说明 |
|------|------|
| 持久化业务数据 | 用户账号、玩家、登录记录 |
| 游戏状态 | 存档、背包、任务进度 |
| 订单与支付 | 订单表、支付流水 |
| 审计日志 | 重要操作日志（可选） |

### 3.3 数据流示意

```
                    ┌─────────────┐
                    │    Redis    │
                    │ 缓存/会话   │
                    │ 临时状态    │
                    └──────┬──────┘
                           │ 回写/过期
                           ▼
                    ┌─────────────┐
                    │  MySQL/DB   │
                    │ 持久化数据  │
                    └─────────────┘
```

---

## 4. 迁移路径

### 4.1 阶段一：表结构设计

1. 按上述数据模型创建 DDL。
2. 增加索引：`player_id`、`user_id`、`login_at`、`order_no` 等。
3. 预留扩展字段（如 `ext_json`）便于后续演进。

### 4.2 阶段二：JPA/JDBC 实现

1. **JPA**：适用于 CRUD 为主的业务（user_account、player、login_record）。
2. **JDBC/JdbcTemplate**：适用于批量写入、复杂 SQL、高吞吐场景（如登录记录）。
3. **MyBatis**：若已有 XML/SQL 习惯，可作为替代。

### 4.3 阶段三：与现有 Redis 的配合

- 登录成功后：DB 写 `login_record`，Redis 写 `player:lastgame:{playerId}`。
- 下线时：DB 可不落库（或写 logout 记录），Redis 清理会话相关 key。
- 缓存策略：`player` 表数据可缓存至 Redis，TTL 按业务设定（如 10 分钟）。

### 4.4 后续 Change 建议

- 新增 `add-persistence` change：引入 spring-boot-starter-data-jpa、MySQL 驱动。
- 实现 `UserAccountRepository`、`PlayerRepository`、`LoginRecordRepository`。
- 提供 Flyway/Liquibase 迁移脚本管理表结构版本。
