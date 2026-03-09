# 安全能力

## 概述

安全能力提供Token认证、消息加密、限流和熔断等安全机制，保护系统免受攻击。

## 需求

### REQUIREMENT: Token生成

系统 SHALL 在玩家登录成功时生成唯一的认证Token。

### REQUIREMENT: Token验证

系统 SHALL 在玩家发送消息时验证Token的有效性。

### REQUIREMENT: Token过期

系统 SHALL 为Token设置有效期，过期后需要重新登录。

### REQUIREMENT: 消息加密

系统 SHALL 支持使用AES算法对消息体进行加密。

### REQUIREMENT: 限流

系统 SHALL 对每个玩家请求进行限流，防止DDoS攻击。

### REQUIREMENT: 熔断

系统 SHALL 在下游服务失败率过高时触发熔断，暂停调用。

## 场景

### Scenario: 玩家登录

- **GIVEN** 玩家发送登录凭据
- **WHEN** Game服务验证成功
- **THEN** Gate生成Token
- **AND** 返回给客户端

### Scenario: 限流触发

- **GIVEN** 玩家发送请求
- **WHEN** 超过限流阈值
- **THEN** 拒绝请求
- **AND** 返回限流错误

## 实现

- **Token服务**: `auth/TokenService`
- **Token验证**: `auth/TokenValidator`
- **消息加密**: `security/MessageEncryptor`
- **限流器**: `ratelimit/RateLimiter`
- **熔断器**: `ratelimit/CircuitBreaker`
