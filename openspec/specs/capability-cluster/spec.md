# 集群管理能力

## 概述

集群管理能力负责管理多Gateway实例，支持实例注册、心跳检测、自动剔除不健康实例。

## 需求

### REQUIREMENT: 实例注册

系统 SHALL 在应用启动时注册本实例信息。

### REQUIREMENT: 心跳

系统 SHALL 每30秒发送一次心跳。

### REQUIREMENT: 健康检查

系统 SHALL 每30秒检查实例健康状态。

### REQUIREMENT: 自动剔除

系统 SHALL 移除超过120秒未发送心跳的实例。

## 场景

### Scenario: 实例启动

- **GIVEN** 应用启动
- **WHEN** 注册实例信息
- **THEN** 实例加入集群

### Scenario: 实例宕机

- **GIVEN** 实例网络故障
- **WHEN** 超过120秒未发送心跳
- **THEN** 自动从集群移除

## 实现

- **集群管理器**: `cluster/GateClusterManager`
- **实例信息**: `cluster/GateClusterManager.GateInstance`
