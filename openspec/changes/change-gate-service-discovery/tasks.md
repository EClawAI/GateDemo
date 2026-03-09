## 1. 项目结构调整

- [ ] 1.1 创建 enter-service 模块
- [ ] 1.2 添加 enter-service 依赖
- [ ] 1.3 配置模块间通信

## 2. EnterServer服务

- [ ] 2.1 创建EnterServer应用入口
- [ ] 2.2 实现LoginHandler登录处理
- [ ] 2.3 实现GameRouter路由决策
- [ ] 2.4 实现SessionManager会话管理
- [ ] 2.5 实现PlayerService玩家数据服务

## 3. 服务发现

- [ ] 3.1 创建ServiceRegistry接口
- [ ] 3.2 实现ZooKeeperServiceRegistry
- [ ] 3.3 EnterServer注册到ZooKeeper
- [ ] 3.4 Game服务注册到ZooKeeper

## 4. 负载均衡

- [ ] 4.1 实现Game负载查询
- [ ] 4.2 实现负载均衡选择算法
- [ ] 4.3 实现会话保持逻辑

## 5. Redis集成

- [ ] 5.1 配置Redis连接
- [ ] 5.2 实现SessionStorage会话存储
- [ ] 5.3 实现玩家数据缓存

## 6. Gate服务修改

- [ ] 6.1 Gate服务注册到ZooKeeper
- [ ] 6.2 Gate发现EnterServer
- [ ] 6.3 实现按需连接Game

## 7. Game服务修改

- [ ] 7.1 Game服务注册到ZooKeeper
- [ ] 7.2 上报负载信息
- [ ] 7.3 实现玩家会话同步

## 8. 测试

- [ ] 8.1 单元测试登录流程
- [ ] 8.2 单元测试路由决策
- [ ] 8.3 集成测试EnterServer↔Game
- [ ] 8.4 压力测试
