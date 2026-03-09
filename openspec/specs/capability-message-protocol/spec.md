# 消息协议能力

## 概述

消息协议能力实现自定义二进制消息协议，支持压缩、加密、请求/响应/推送模式。

## 需求

### REQUIREMENT: 消息格式

系统 SHALL 支持固定的14字节消息头 + 可变长消息体的二进制格式。

### REQUIREMENT: 消息头头 SHALL字段

消息 包含以下字段：
- flags (2字节): 标志位（压缩、加密、模式）
- sequence (2字节): 序列号
- messageId (2字节): 消息ID
- bodyLength (4字节): 消息体长度
- requestId (4字节): 请求ID

### REQUIREMENT: 消息压缩

系统 SHALL 对大于64字节的消息体进行DEFLATE压缩。

### REQUIREMENT: 消息模式

系统 SHALL 支持三种消息模式：
- REQUEST: 客户端请求
- RESPONSE: 服务器响应
- PUSH: 服务器推送

### REQUIREMENT: 消息ID映射

系统 SHALL 支持从消息名称自动生成消息ID（使用FNV哈希算法）。

## 场景

### Scenario: 客户端发送请求

- **GIVEN** 客户端已连接
- **WHEN** 发送REQUEST模式消息
- **THEN** 服务端处理消息
- **AND** 返回RESPONSE模式消息

### Scenario: 服务器推送消息

- **GIVEN** 玩家在线
- **WHEN** 服务器需要通知玩家
- **THEN** 发送PUSH模式消息
- **AND** 不期望客户端响应

## 实现

- **编码器**: `protocol/codec/GameMessageEncoder`
- **解码器**: `protocol/codec/GameMessageDecoder`
- **消息头**: `protocol/model/MessageHeader`
- **消息体**: `protocol/model/MessageBody`
- **ID生成**: `protocol/MessageIdGenerator`
