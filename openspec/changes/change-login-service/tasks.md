## 1. 项目结构调整

- [ ] 1.1 创建 login-service 模块
- [ ] 1.2 添加 login-service 依赖
- [ ] 1.3 配置Redis连接

## 2. LoginService服务

- [ ] 2.1 创建LoginService应用入口
- [ ] 2.2 配置HTTP端口

## 3. 登录接口

- [ ] 3.1 创建LoginController
- [ ] 3.2 实现登录接口 `/api/v1/login`
- [ ] 3.3 实现Gate信息返回
- [ ] 3.4 实现gameId路由逻辑

## 4. 路由逻辑

- [ ] 4.1 查询玩家登录记录
- [ ] 4.2 实现推荐服选择
- [ ] 4.3 实现故障转移逻辑

## 5. 登录记录

- [ ] 5.1 创建登录记录接口 `/api/v1/game/login-record`
- [ ] 5.2 实现Redis存储

## 6. Gate心跳

- [ ] 6.1 创建Gate心跳接口 `/api/v1/gate/heartbeat`
- [ ] 6.2 实现Gate在线人数管理

## 7. Gate服务修改

- [ ] 7.1 添加心跳上报到LoginService

## 8. Game服务修改

- [ ] 8.1 登录成功后通知LoginService

## 9. 测试

- [ ] 9.1 单元测试登录接口
- [ ] 9.2 单元测试路由逻辑
- [ ] 9.3 集成测试
