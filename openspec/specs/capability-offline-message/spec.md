# 离线消息能力

## 概述

离线消息能力使用Redis Stream存储玩家离线时的消息，在玩家上线时推送。

## 需求

### REQUIREMENT: 离线消息存储

系统 SHALL 在玩家离线时将其接收到的消息存储到Redis Stream。

### REQUIREMENT: 消息数量检查

系统 SHALL 在玩家上线时检查离线消息数量。

### REQUIREMENT: 正常模式

系统 SHALL 当离线消息数量≤阈值时，逐条推送给玩家。

### REQUIREMENT: Relogin模式

系统 SHALL 当离线消息数量>阈值时，发送relogin通知并清除离线消息。

## 场景

### Scenario: 消息发送给离线玩家

- **GIVEN** 玩家A在线，玩家B离线
- **WHEN** 玩家A发送消息给玩家B
- **THEN** 消息存储到Redis Stream
- **AND** 玩家B的离线消息计数+1

### Scenario: 玩家上线-正常模式

- **GIVEN** 玩家有≤200条离线消息
- **WHEN** 玩家上线
- **THEN** 逐条推送离线消息

### Scenario: 玩家上线-Relogin模式

- **GIVEN** 玩家有>200条离线消息
- **WHEN** 玩家上线
- **THEN** 发送relogin通知
- **AND** 清除离线消息

## 实现

- **离线消息服务**: `service/OfflineMessageService`
- **消息队列生产者**: `queue/MessageQueueProducer`
- **消息队列消费者**: `queue/MessageQueueConsumer`
- **Redis配置**: `config/RedisConfig`
